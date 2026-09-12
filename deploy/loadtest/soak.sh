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
#   MQK_TTFB_P95_BUDGET_MS  optional: exit non-zero when TTFB p95 exceeds this
#                           budget (run on representative hardware to check the
#                           §10 product SLO of 30 ms)
#
# 红线档（§10 首版容量验收：50 条并发 SSE）：
#   MQK_CONCURRENCY=50 MQK_DURATION=180 bash deploy/loadtest/soak.sh
# TTFB 经 curl time_starttransfer 采集；网关侧开销 = TTFB − 上游首字节预算（自建 mock 按实际设置扣除）。
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
    read -r code ttfb < <(curl -s -o /dev/null -w '%{http_code} %{time_starttransfer}' --max-time 60 -N \
      -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
      -d '{"model":"mock-model","messages":[{"role":"user","content":"soak"}],"stream":true}' \
      "$BASE/v1/chat/completions" || echo "000 0")
    end=$(date +%s%3N)
    printf '%d %s %s\n' "$((end - start))" "$code" "$ttfb"
    i=$((i + 1))
  done
}
export -f run_stream
export BASE KEY DURATION

seq 1 "$CONCURRENCY" | xargs -P "$CONCURRENCY" -I{} bash -c 'run_stream' > "$OUT"

python - "$OUT" "$DURATION" <<'PY'
import sys
lines = [l.split() for l in open(sys.argv[1]) if len(l.split()) >= 2]
lat = sorted(int(a) for a, c, *_ in lines if c == "200")
ttfb = sorted(float(t) * 1000 for a, c, t, *_ in lines if c == "200" and len(l) >= 3)
errs = [c for a, c, *_ in lines if c != "200"]
n = len(lines)
dur = int(sys.argv[2])
print(f"requests: {n}  ({n/dur:.1f}/s)")
print(f"errors:   {len(errs)} ({100*len(errs)/max(n,1):.1f}%)  first: {errs[:5]}")
if lat:
    p = lambda q: lat[min(int(q*len(lat)), len(lat)-1)]
    print(f"latency p50={p(.5)}ms p90={p(.9)}ms p99={p(.99)}ms max={lat[-1]}ms")
if ttfb:
    p = lambda q: ttfb[min(int(q*len(ttfb)), len(ttfb)-1)]
    print(f"ttfb p50={p(.5):.1f}ms p95={p(.95):.1f}ms max={ttfb[-1]:.1f}ms"
          "  (网关侧开销 = ttfb - 上游首字节预算)")
PY

if [ -n "${MQK_TTFB_P95_BUDGET_MS:-}" ]; then
  python - "$OUT" "$MQK_TTFB_P95_BUDGET_MS" <<'PY'
import sys
lines = [l.split() for l in open(sys.argv[1]) if len(l.split()) >= 3]
t = sorted(float(x[2]) * 1000 for x in lines if x[1] == "200")
if t:
    p95 = t[min(int(0.95 * len(t)), len(t) - 1)]
    budget = float(sys.argv[2])
    print(f"ttfb p95 = {p95:.1f} ms (budget {budget:.0f} ms)")
    sys.exit(0 if p95 <= budget else 1)
PY
fi

echo "== usage queue (must stay 0) =="
curl -s --max-time 5 "$BASE/actuator/prometheus" | grep -E "miqrokey_usage_queue_dropped" | head -2 || echo "(metrics endpoint not exposed; skip)"
