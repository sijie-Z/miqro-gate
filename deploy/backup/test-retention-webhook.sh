#!/usr/bin/env bash
# Retention + webhook notification tests (G6.2), no Docker required.
set -euo pipefail

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
KEY_FILE="$WORK/key"
printf '%s' "$(openssl rand -base64 32)" > "$KEY_FILE"
chmod 400 "$KEY_FILE"

# Mock pg_dump so the full pipeline (dump->gzip->encrypt->retention) runs
# without a database.
mkdir -p "$WORK/bin"
cat > "$WORK/bin/pg_dump" <<'WRAP'
#!/usr/bin/env bash
printf 'MOCK-DUMP-%s
' "$(date +%s)"
WRAP
chmod +x "$WORK/bin/pg_dump"
export PATH="$WORK/bin:$PATH"

echo "== retention: cap = DAILY_KEEP + WEEKLY_KEEP =="
mkdir -p "$WORK/out"
for i in 1 2 3 4 5 6 7 8 9 10; do
  : > "$WORK/out/miqrokey-2026080${i}T000000Z.sql.gz.enc"
  : > "$WORK/out/miqrokey-2026080${i}T000000Z.sql.gz.enc.sha256"
done
MIQROKEY_BACKUP_PATH="$WORK/out" MIQROKEY_BACKUP_DAILY_KEEP=2 MIQROKEY_BACKUP_WEEKLY_KEEP=1 \
MIQROKEY_BACKUP_KEY_FILE="$KEY_FILE" \
MIQROKEY_DB_URL="jdbc:postgresql://localhost:5432/miqrokey" \
  bash deploy/backup/miqrokey-backup.sh >/dev/null
COUNT=$(ls -1 "$WORK"/out/miqrokey-*.sql.gz.enc | wc -l)
[ "$COUNT" -le 4 ] || { echo "FAIL: retention left $COUNT files (cap 4)" >&2; exit 1; }
echo "retention PASS: $COUNT files after cap"

echo "== webhook notification with HMAC signature =="
cat > "$WORK/hook.py" <<'PY'
import hashlib, hmac, http.server, json, sys
class H(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        sig = self.headers.get("X-MiQroKey-Signature", "")
        expected = "sha256=" + hmac.new(b"hook-secret", body, hashlib.sha256).hexdigest()
        ok = hmac.compare_digest(sig, expected)
        payload = json.loads(body)
        assert payload["type"] == "backup" and payload["status"] == "success", payload
        self.send_response(200 if ok else 401)
        self.end_headers()
        sys.exit(0)
    def log_message(self, *a): pass
s = http.server.HTTPServer(("127.0.0.1", 0), H)
print("LISTENING", s.server_address[1], flush=True)
s.handle_request()
PY
python "$WORK/hook.py" > "$WORK/hook.log" 2>&1 &
HOOK_PID=$!
for i in $(seq 1 20); do
  [ -s "$WORK/hook.log" ] && break
  sleep 0.5
done
PORT=$(grep -oE "[0-9]+" "$WORK/hook.log" | head -1)
mkdir -p "$WORK/out2"
MIQROKEY_BACKUP_PATH="$WORK/out2" MIQROKEY_BACKUP_KEY_FILE="$KEY_FILE" \
MIQROKEY_BACKUP_WEBHOOK_URL="http://127.0.0.1:$PORT/hook" \
MIQROKEY_BACKUP_WEBHOOK_SECRET="hook-secret" \
MIQROKEY_DB_URL="jdbc:postgresql://localhost:5432/miqrokey" \
  bash deploy/backup/miqrokey-backup.sh >/dev/null
wait "$HOOK_PID"
echo "webhook PASS: signed success payload delivered"
