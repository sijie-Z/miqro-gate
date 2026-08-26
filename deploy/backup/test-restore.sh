#!/usr/bin/env bash
# Real restore drill (G6.2): spins up two disposable Postgres containers,
# seeds the source, runs the real backup script, restores into the target
# and asserts row-count consistency. Requires Docker.
set -euo pipefail

IMAGE="postgres:17.6-alpine@sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94"
PASS="miqrokey-test"
WORK=$(mktemp -d)
KEY_FILE="$WORK/backup.key"
printf '%s' "$(openssl rand -base64 32)" > "$KEY_FILE"
chmod 400 "$KEY_FILE"

cleanup() {
  docker rm -f miqrokey-backup-src miqrokey-backup-dst >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT

echo "== starting source and target postgres =="
docker run -d --name miqrokey-backup-src -e POSTGRES_PASSWORD="$PASS" -e POSTGRES_DB=miqrokey "$IMAGE" >/dev/null
docker run -d --name miqrokey-backup-dst -e POSTGRES_PASSWORD="$PASS" -e POSTGRES_DB=miqrokey "$IMAGE" >/dev/null
for i in $(seq 1 30); do
  docker exec miqrokey-backup-src pg_isready -U postgres >/dev/null 2>&1 && break
  sleep 1
done
for i in $(seq 1 30); do
  docker exec miqrokey-backup-dst pg_isready -U postgres >/dev/null 2>&1 && break
  sleep 1
done

echo "== seeding source =="
docker exec -i miqrokey-backup-src psql -U postgres -d miqrokey <<'SQL' >/dev/null
CREATE TABLE accounts (id bigserial PRIMARY KEY, name text NOT NULL);
INSERT INTO accounts (name) SELECT 'user-' || g FROM generate_series(1, 1000) g;
SQL
SRC_COUNT=$(docker exec miqrokey-backup-src psql -U postgres -d miqrokey -tAc \
  "SELECT count(*) FROM accounts")

# The host may lack postgres client tools; wrap the container binaries.
mkdir -p "$WORK/bin"
cat > "$WORK/bin/pg_dump" <<'WRAP'
#!/usr/bin/env bash
exec docker exec -i -e PGUSER="$PGUSER" -e PGPASSWORD="$PGPASSWORD"   -e PGHOST="$PGHOST" -e PGPORT="$PGPORT" -e PGDATABASE="$PGDATABASE"   miqrokey-backup-src pg_dump "$@"
WRAP
cat > "$WORK/bin/pg_restore" <<'WRAP'
#!/usr/bin/env bash
exec docker exec -i -e PGUSER="$PGUSER" -e PGPASSWORD="$PGPASSWORD"   -e PGHOST="$PGHOST" -e PGPORT="$PGPORT" -e PGDATABASE="$PGDATABASE"   miqrokey-backup-dst pg_restore "$@"
WRAP
chmod +x "$WORK/bin/pg_dump" "$WORK/bin/pg_restore"
export PATH="$WORK/bin:$PATH"

echo "== running the real backup script =="
MIQROKEY_BACKUP_PATH="$WORK/out" \
MIQROKEY_BACKUP_KEY_FILE="$KEY_FILE" \
MIQROKEY_DB_URL="jdbc:postgresql://$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' miqrokey-backup-src):5432/miqrokey" \
MIQROKEY_DB_USERNAME=postgres MIQROKEY_DB_PASSWORD="$PASS" \
  "$(dirname "$0")/miqrokey-backup.sh"

BACKUP_FILE=$(ls "$WORK"/out/miqrokey-*.sql.gz.enc | head -1)
echo "== verifying the archive =="
MIQROKEY_BACKUP_KEY_FILE="$KEY_FILE" "$(dirname "$0")/miqrokey-verify.sh" "$BACKUP_FILE"

echo "== restoring into the target =="
MIQROKEY_BACKUP_KEY_FILE="$KEY_FILE" \
MIQROKEY_DB_URL="jdbc:postgresql://$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' miqrokey-backup-dst):5432/miqrokey" \
MIQROKEY_DB_USERNAME=postgres MIQROKEY_DB_PASSWORD="$PASS" \
  "$(dirname "$0")/miqrokey-restore.sh" "$BACKUP_FILE"

DST_COUNT=$(docker exec miqrokey-backup-dst psql -U postgres -d miqrokey -tAc \
  "SELECT count(*) FROM accounts")

echo "== asserting consistency =="
[ "$SRC_COUNT" = "$DST_COUNT" ] || { echo "FAIL: source=$SRC_COUNT target=$DST_COUNT" >&2; exit 1; }
echo "restore drill PASS: $SRC_COUNT rows intact"
