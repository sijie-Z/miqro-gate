#!/usr/bin/env bash
# Backup verification (G6.2): decrypts and dry-runs a schema check without
# touching any database — a corrupt archive fails here, not at restore time.
set -euo pipefail

# Git Bash (Windows) passes MSYS paths that native openssl cannot open.
if command -v cygpath >/dev/null 2>&1; then
  winpath() { cygpath -w "$1"; }
else
  winpath() { printf '%s' "$1"; }
fi

MIQROKEY_BACKUP_KEY_FILE="${MIQROKEY_BACKUP_KEY_FILE:?MIQROKEY_BACKUP_KEY_FILE is required}"
BACKUP_FILE="${1:?usage: miqrokey-verify.sh <file.sql.gz.enc>}"

MANIFEST="$BACKUP_FILE.sha256"
[ -f "$MANIFEST" ] || { echo "verify failed: missing manifest" >&2; exit 1; }
(cd "$(dirname "$BACKUP_FILE")" && sha256sum -c "$(basename "$MANIFEST")" >/dev/null 2>&1) \
  || { echo "verify failed: checksum mismatch" >&2; exit 1; }

openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass file:"$(winpath "$MIQROKEY_BACKUP_KEY_FILE")" \
  -in "$BACKUP_FILE" | gunzip | pg_restore --list >/dev/null

echo "verify ok: archive is decryptable and lists cleanly"
