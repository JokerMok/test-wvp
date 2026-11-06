#!/usr/bin/env bash
set -euo pipefail
umask 077

root=/srv/apps/test-wvp
deploy=$root/deploy
shared=$root/shared
config=$shared/config
[[ $EUID == 0 && $# == 1 && -f $1 ]]
schema=$1

exec 9>/run/lock/test-wvp-deploy.lock
flock -x 9
if [[ -f $config/.initialized ]]; then
    echo "Test WVP configuration already exists."
    exit 0
fi
if [[ -e $shared/data/mysql/mysql ]]; then
    echo "A MySQL data directory already exists. Inspect the bootstrap status before resuming." >&2
    exit 1
fi
for program in /usr/bin/java /usr/sbin/mysqld /usr/bin/redis-check-rdb; do
    [[ -x $program ]]
done

for account in test-wvp test-wvp-db test-wvp-cache test-wvp-media; do
    if ! id "$account" >/dev/null 2>&1; then
        useradd --system --user-group --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin "$account"
    fi
done

install -d -m 0755 "$root/runtime" "$root/releases"
install -d -m 0711 "$shared" "$config" "$shared/data" "$shared/uploads"
install -d -m 0700 "$shared/backups"
install -d -m 0700 -o test-wvp-db -g test-wvp-db "$shared/data/mysql"
install -d -m 0700 -o test-wvp-cache -g test-wvp-cache "$shared/data/redis"
install -d -m 0700 -o test-wvp -g test-wvp "$shared/data/wvp" "$config/wvp" "$shared/uploads/snap"
install -d -m 0700 -o test-wvp-media -g test-wvp-media "$shared/data/media" "$config/media" "$shared/uploads/mp4_record"
install -d -m 0750 -o test-wvp-media -g test-wvp-media "$shared/data/media/www"
ln -sfn "$shared/uploads/mp4_record" "$shared/data/media/www/mp4_record"
ln -sfn "$shared/uploads/snap" "$shared/data/wvp/snap"
# The Ubuntu redis-tools package ships the Redis multicall binary.
ln -sfn /usr/bin/redis-check-rdb "$root/runtime/redis-server"

root_password=$(openssl rand -hex 32)
db_password=$(openssl rand -hex 32)
redis_password=$(openssl rand -hex 32)
media_secret=$(openssl rand -hex 24)
sip_password=$(openssl rand -hex 24)
app_password=$(openssl rand -hex 24)
edge_password=$(openssl rand -hex 24)
app_hash=$(printf '%s' "$app_password" | md5sum | cut -d' ' -f1)
push_key=$(openssl rand -hex 16)

cat > "$config/access.env" <<EOF
WVP_USERNAME=admin
WVP_PASSWORD=$app_password
EDGE_USERNAME=wvp-test
EDGE_PASSWORD=$edge_password
EOF
printf 'wvp-test %s\n' "$(printf '%s\n' "$edge_password" | caddy hash-password)" > "$config/caddy-auth.caddy"
chgrp caddy "$config/caddy-auth.caddy"
chmod 0640 "$config/caddy-auth.caddy"

cat > "$config/mysql-admin.cnf" <<EOF
[client]
protocol=socket
socket=/run/test-wvp-db/mysql.sock
user=root
password=$root_password
EOF

cat > "$config/mysql.cnf" <<EOF
[mysqld]
datadir=$shared/data/mysql
socket=/run/test-wvp-db/mysql.sock
pid-file=/run/test-wvp-db/mysql.pid
bind-address=127.0.0.1
port=18979
mysqlx=OFF
skip-log-bin
skip-name-resolve
local_infile=OFF
innodb_buffer_pool_size=64M
innodb_redo_log_capacity=16M
innodb_temp_data_file_path=ibtmp1:12M:autoextend:max:64M
max_connections=32
performance_schema=OFF
character-set-server=utf8mb4
collation-server=utf8mb4_0900_ai_ci
EOF
chgrp test-wvp-db "$config/mysql.cnf"
chmod 0640 "$config/mysql.cnf"

cat > "$config/redis.conf" <<EOF
bind 127.0.0.1
port 18977
protected-mode yes
requirepass $redis_password
dir $shared/data/redis
dbfilename dump.rdb
save 300 1
maxmemory 64mb
maxmemory-policy noeviction
EOF
chgrp test-wvp-cache "$config/redis.conf"
chmod 0640 "$config/redis.conf"
printf 'REDISCLI_AUTH=%s\n' "$redis_password" > "$config/redis-backup.env"

cat > "$config/application.yml" <<EOF
server:
  address: 127.0.0.1
  port: 18978
  forward-headers-strategy: framework
  servlet:
    session:
      cookie:
        secure: true
        http-only: true
        same-site: lax
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:18979/wvp?allowPublicKeyRetrieval=true&sslMode=DISABLED&serverTimezone=Asia/Shanghai
    driver-class-name: com.mysql.cj.jdbc.Driver
    username: test_wvp
    password: "$db_password"
    hikari:
      maximum-pool-size: 8
  data:
    redis:
      host: 127.0.0.1
      port: 18977
      password: "$redis_password"
      database: 0
      timeout: 5s
  sql:
    init:
      mode: never
springdoc:
  api-docs:
    enabled: false
  swagger-ui:
    enabled: false
logging:
  config: file:$deploy/logback.xml
sip:
  ip: 127.0.0.1
  show-ip: 127.0.0.1
  port: 18982
  domain: "4401000000"
  id: "44010000002000000001"
  password: "$sip_password"
jt1078:
  enable: false
ftp:
  enable: false
media:
  id: test-wvp-zlm
  ip: 127.0.0.1
  hook-ip: 127.0.0.1
  stream-ip: wvp.shizibandu.icu
  sdp-ip: 127.0.0.1
  http-port: 18980
  proxy-https-port: 443
  secret: "$media_secret"
  auto-config: true
  record-path: $shared/uploads/mp4_record
  rtp:
    enable: true
    port-range: 30000,30500
    send-port-range: 30502,30550
user-settings:
  server-id: test-wvp
  interface-authentication: true
  auto-apply-play: false
  record-push-live: false
  record-sip: false
  allowed-origins:
    - https://wvp.shizibandu.icu
  jwk-file: $config/wvp/jwk.json
EOF
chgrp test-wvp "$config/application.yml"
chmod 0640 "$config/application.yml"

# MediaServer fills omitted options with this pinned runtime's defaults.
cat > "$config/media/config.ini" <<EOF
[api]
secret=$media_secret

[general]
mediaServerId=test-wvp-zlm
listen_ip=127.0.0.1

[http]
allow_ip_range=127.0.0.1
port=18980
sslport=0
dirMenu=0
rootPath=$shared/data/media/www

[rtmp]
port=18983
sslport=0

[rtsp]
port=18984
sslport=0

[rtp_proxy]
port=18985
port_range=30000-30500

[rtc]
port=0
tcpPort=0
signalingPort=0
signalingSslPort=0
enableTurn=0

[srt]
port=0

[shell]
port=0

[record]
appName=mp4_record

[protocol]
mp4_save_path=$shared/uploads
downloadRoot=$shared/uploads
hls_save_path=$shared/data/media/www
enable_mp4=0

[hook]
enable=0
EOF
chown test-wvp-media:test-wvp-media "$config/media/config.ini"
chmod 0600 "$config/media/config.ini"

# Start the bootstrap server on its private Unix socket only.
install -d -m 0700 -o test-wvp-db -g test-wvp-db /run/test-wvp-db
/usr/sbin/mysqld --defaults-file="$config/mysql.cnf" --initialize-insecure --user=test-wvp-db
bootstrap_unit=test-wvp-db-bootstrap.service
systemd-run --unit="$bootstrap_unit" --collect -p User=test-wvp-db -p Group=test-wvp-db \
    -p RuntimeDirectory=test-wvp-db -p RuntimeDirectoryMode=0700 \
    /usr/sbin/mysqld --defaults-file="$config/mysql.cnf" --skip-networking
trap 'systemctl stop "$bootstrap_unit"' EXIT
mysql_socket=(mysql --no-defaults --protocol=socket --socket=/run/test-wvp-db/mysql.sock -u root)
for attempt in {1..30}; do
    if "${mysql_socket[@]}" --batch --skip-column-names -e 'SELECT 1' >/dev/null 2>&1; then
        break
    fi
    sleep 1
done
"${mysql_socket[@]}" <<EOF
ALTER USER 'root'@'localhost' IDENTIFIED BY '$root_password';
CREATE DATABASE wvp CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'test_wvp'@'127.0.0.1' IDENTIFIED BY '$db_password';
GRANT SELECT, INSERT, UPDATE, DELETE ON wvp.* TO 'test_wvp'@'127.0.0.1';
EOF
mysql --defaults-file="$config/mysql-admin.cnf" wvp < "$schema"
mysql --defaults-file="$config/mysql-admin.cnf" wvp <<EOF
UPDATE wvp_user SET password='$app_hash', push_key='$push_key', default_password=FALSE WHERE id=1;
EOF
systemctl stop "$bootstrap_unit"
trap - EXIT
touch "$config/.initialized"

install -m 0644 "$deploy"/systemd/*.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now test-wvp-db.service test-wvp-cache.service
