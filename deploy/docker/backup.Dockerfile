# MiQroKey backup-job image (#479): PostgreSQL 17 client tools (same base as the
# database service, so pg_dump always matches the server major) plus the
# deploy/backup scripts. Schedule lives in backup-entrypoint.sh (daily 02:00,
# TZ-driven). Build context is the repository root:
#   docker build -f deploy/docker/backup.Dockerfile .
FROM postgres:17.6-alpine@sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94
RUN apk add --no-cache openssl curl tzdata
COPY deploy/backup/ /opt/miqrokey/backup/
COPY deploy/docker/backup-entrypoint.sh /usr/local/bin/backup-entrypoint.sh
RUN chmod +x /usr/local/bin/backup-entrypoint.sh /opt/miqrokey/backup/*.sh \
    && chown -R postgres:postgres /opt/miqrokey/backup \
    && mkdir -p /var/backups/miqrokey && chown postgres:postgres /var/backups/miqrokey
USER postgres
ENTRYPOINT ["/usr/local/bin/backup-entrypoint.sh"]
