#!/usr/bin/env bash
set -euo pipefail
[[ $EUID == 0 && $# == 1 && ( $1 == internal || $1 == public ) ]]
root=/srv/apps/test-wvp
candidate=$(mktemp "$root/deploy/.entry.XXXXXXXX")
trap 'rm -f -- "$candidate"' EXIT
cat "$root/deploy/internal.caddy" > "$candidate"
if [[ $1 == public ]]; then
    for ns in gift.dnspod.net pot.dnspod.net; do
        address=$(dig +time=4 +tries=1 +short @"$ns" wvp.shizibandu.icu A)
        [[ $address == 175.178.236.56 ]] || { echo "DNSPod has not published the expected A record." >&2; exit 1; }
        [[ -z $(dig +time=4 +tries=1 +short @"$ns" wvp.shizibandu.icu AAAA) ]]
    done
    cat "$root/deploy/public.caddy" >> "$candidate"
fi
envfiles=()
while IFS= read -r path; do
    [[ -z $path ]] || envfiles+=(-p "EnvironmentFile=$path")
done < <(systemctl show caddy -p EnvironmentFiles | awk '{sub(/^EnvironmentFiles=/, ""); print $1}')
systemd-run --wait --pipe --collect "${envfiles[@]}" /usr/local/sbin/caddy-site-install test-wvp "$candidate"
