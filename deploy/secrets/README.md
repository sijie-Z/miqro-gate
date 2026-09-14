# deploy/secrets — runtime secret files for compose.prod.yaml (#479)

This directory holds the secret **files** the production stack mounts into
containers at `/run/secrets/*`. Nothing here may ever be committed
(`.gitignore` excludes everything except this README).

## Files to create

| File | Content | Generation |
|---|---|---|
| `db_password` | PostgreSQL password (any strong string) | `openssl rand -base64 24 > db_password` |
| `master_key` | 32 raw bytes — credential AES-256-GCM master key | `openssl rand 32 > master_key` |
| `vk_hmac_key` | ≥32 raw bytes — Virtual Key HMAC pepper | `openssl rand 32 > vk_hmac_key` |
| `bootstrap_secret` | ≥16 chars — first-admin bootstrap secret (remove after bootstrap) | `openssl rand -base64 18 > bootstrap_secret` |
| `backup_key` | 32 bytes base64 — backup encryption key, **kept separate from master_key** | `openssl rand -base64 32 > backup_key` |
| `certs/fullchain.pem` + `certs/privkey.pem` | TLS certificate chain + private key for your domain | certbot / cloud provider |

Run from `deploy/`:

```bash
mkdir -p secrets/certs
openssl rand -base64 24 > secrets/db_password
openssl rand 32         > secrets/master_key
openssl rand 32         > secrets/vk_hmac_key
openssl rand -base64 18 > secrets/bootstrap_secret
openssl rand -base64 32 > secrets/backup_key
# certs: put fullchain.pem / privkey.pem into secrets/certs/
```

## Custody rules (deployment-and-operations §7)

- `master_key` and `backup_key` must be copied to offline storage **outside the
  server**; a host loss without them means encrypted credentials and backups are
  unrecoverable.
- Do **not** ship the master key inside database backups.
- Delete `bootstrap_secret` after the first administrator exists.
- Container access is set by the `secrets-init` service: it copies these files
  into the `secrets-store` volume on every stack start and applies ownership and
  modes there (master/vk-hmac/bootstrap `0400` uid 10001; db_password/backup_key
  `0440` 10001:70, readable by the app uid and the backup container's postgres
  uid). Docker Desktop ignores compose `secrets:` uid/gid/mode (synthetic 0777),
  so permissions are applied inside the volume — identical on dev and prod.
  Keep the host directory `chmod 700`.
