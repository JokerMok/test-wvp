#!/usr/bin/env bash
set -euo pipefail
umask 077

root=/srv/apps/test-wvp
[[ $EUID == 0 && $# == 1 && ! -e $1 ]]
destination=$1
mkdir -m 0700 -- "$destination"

mysqldump --defaults-file="$root/shared/config/mysql-admin.cnf" \
    --single-transaction --skip-lock-tables --no-tablespaces --set-gtid-purged=OFF \
    --column-statistics=0 wvp | gzip > "$destination/wvp.sql.gz"
gzip -t "$destination/wvp.sql.gz"

set -a
source "$root/shared/config/redis-backup.env"
set +a
redis-cli -h 127.0.0.1 -p 18977 --rdb "$destination/redis.rdb"
redis-check-rdb "$destination/redis.rdb" >/dev/null

# tar exits nonzero if an upload changes while it is being read.
tar -czf "$destination/uploads.tar.gz" -C "$root/shared/uploads" .
tar -tzf "$destination/uploads.tar.gz" >/dev/null
(cd "$destination" && sha256sum wvp.sql.gz redis.rdb uploads.tar.gz > SHA256SUMS)
