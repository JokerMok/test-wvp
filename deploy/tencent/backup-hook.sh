#!/usr/bin/env bash
set -euo pipefail
exec 9>/run/lock/test-wvp-deploy.lock
flock -w 60 -x 9
exec /srv/apps/test-wvp/deploy/backup.sh "$1/test-wvp"
