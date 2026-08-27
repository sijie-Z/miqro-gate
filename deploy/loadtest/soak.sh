#!/usr/bin/env bash
# G6.4 soak test: drives the gateway through a mock upstream for a fixed
# duration and reports throughput, latency percentiles and error rate, plus
# the usage-queue drop counter (must stay 0).
#
# Requires: a running stack (deploy/compose.yaml) with the gateway on :8081
# and a mock upstream reachable via MIQROKEY_UPSTREAM_ALLOWED_CIDRS, plus a
# seeded virtual key. Env:
#   MQK_BASE_URL   gateway base (default http://localhost:8081)
#   MQK_VIRTUAL_KEY  the seeded key
#   MQK_DURATION   seconds (default 180)
#   MQK_CONCURRENCY (default 20)
set -euo pipefail

BASE="${MQK_BASE_URL:-http://localhost:8081}"
KEY="${MQK_VIRTUAL_KEY:?MQK_VIRTUAL_KEY is required (seed one via the API)}"
DURATION="${MQK_DURATION:-180}"
CONCURRENCY="${MQK_CONCURRENCY:-20}"
OUT=$(mktemp)
trap 'rm -f "$OUT"' EXIT

echo "soak: $DURATION s, $CONCURRENCY concurrent streams -> $BASE"

# Concurrent streaming chat requests; each line is "latency_ms status".
run_stream() {
  local i=0
  while [ "$i" -lt "$DURATION" ]; do
    local start end code
    start=$(date +%s%3N)
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 60 -N \
      -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
      -d '{"model":"mock-model","messages":[{"role":"user","content":"soak"}],"stream":true}' \
      "$BASE/v1/chat/completions" || true)
    end=$(date +%s%3N)
    printf '%d %s\n' "$((end - start))" "$code"
    i=$((i + 1))
  done
}
export -f run_stream
export BASE KEY DURATION

seq 1 "$CONCURRENCY" | xargs -P "$CONCURRENCY" -I{} bash -c 'run_stream' > "$OUT"

python - "$OUT" "$DURATION" <<'PY'
import sys, statistics
lines = [l.split() for l in open(sys.argv[1]) if len(l.split()) == 2]
lat = sorted(int(a) for a, c in lines if c == "200")
errs = [c for _, c in lines if c != "200"]
n = len(lines)
dur = int(sys.argv[2])
print(f"requests: {n}  ({n/dur:.1f}/s)")
print(f"errors:   {len(errs)} ({100*len(errs)/max(n,1):.1f}%)  first: {errs[:5]}")
if lat:
    p = lambda q: lat[min(int(q*len(lat)), len(lat)-1)]
    print(f"latency p50={p(.5)}ms p90={p(.9)}ms p99={p(.99)}ms max={lat[-1]}ms")
PY

echo "== usage queue (must stay 0) =="
curl -s --max-time 5 "$BASE/actuator/prometheus" | grep -E "miqrokey_usage_queue_dropped" | head -2 || echo "(metrics endpoint not exposed; skip)"
