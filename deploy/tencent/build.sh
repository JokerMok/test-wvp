#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/../.." && pwd)
git_bin=${WVP_GIT:-git}
mvn_bin=${WVP_MVN:-mvn}
[[ $# == 2 ]] || { echo "Usage: build.sh <commit> <new-release.tar.gz>" >&2; exit 2; }
output=$(python3 -c 'import pathlib,sys; print(pathlib.Path(sys.argv[1]).resolve())' "$2")
[[ ! -e $output ]] || { echo "Release archive already exists." >&2; exit 2; }

cd "$root"
"$git_bin" fetch origin main
version=$("$git_bin" rev-parse "$1^{commit}")
"$git_bin" merge-base --is-ancestor "$version" refs/remotes/origin/main
scratch=$(mktemp -d)
cleanup() {
    if [[ -e $scratch/source/.git ]]; then
        "$git_bin" worktree remove --force "$scratch/source"
    fi
    rm -rf "$scratch"
}
trap cleanup EXIT
"$git_bin" worktree add --detach "$scratch/source" "$version"
cd "$scratch/source"
"$mvn_bin" -B -DskipTests package

# Caddy serves the compiled site; build Java first to keep static assets out of the JAR.
(
    cd web
    npm ci --legacy-peer-deps --no-audit --no-fund
    NODE_OPTIONS=--openssl-legacy-provider npm run build:prod
)
release=$scratch/release
mkdir "$release"
cp target/wvp-pro-*.jar "$release/wvp.jar"
cp '数据库/2.7.4/初始化-mysql-2.7.4.sql' "$release/schema.sql"
mv src/main/resources/static "$release/site"
python3 - "$release" "$version" <<'PY'
import datetime
import hashlib
import json
from pathlib import Path
import sys

release = Path(sys.argv[1])
metadata = {
    "git_sha": sys.argv[2],
    "application_version": "2.7.4",
    "built_at_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
}
(release / 'RELEASE.json').write_text(json.dumps(metadata, indent=2) + '\n')
files = sorted(item for item in release.rglob('*') if item.is_file())
with (release / 'SHA256SUMS').open('w') as sums:
    for item in files:
        with item.open('rb') as f:
            sha = hashlib.file_digest(f, 'sha256').hexdigest()
        sums.write(f'{sha}  {item.relative_to(release)}\n')
PY
COPYFILE_DISABLE=1 tar --no-xattrs --no-acls -czf "$output" -C "$release" .
shasum -a 256 "$output"
printf 'Git commit: %s\nArchive: %s\n' "$version" "$output"
