#!/usr/bin/env bash
set -euo pipefail

root=/srv/apps/test-wvp
[[ $EUID == 0 && $# -gt 0 ]]
action=$1
shift
exec 9>/run/lock/test-wvp-deploy.lock
flock -x 9

free_space_check() {
    local available
    available=$(df -B1 --output=avail "$root" | tail -1)
    (( available >= 2147483648 )) || { echo "The existing backup job requires 2 GiB free." >&2; return 1; }
}

verify_release() {
    local path=$1
    [[ -d $path && -s $path/wvp.jar && -f $path/site/index.html ]]
    jq -e --arg sha "{path##*/}" '.git_sha == $sha' "$path/RELEASE.json" >/dev/null
    (cd "$path" && sha256sum --quiet -c SHA256SUMS)
}

set_link() {
    local name=$1 target=$2 pending=$root/.$1.new
    ln -sfn -- "$target" "$pending"
    mv -Tf -- "$pending" "$root/$name"
}

wait_for_wvp() {
    local version=$1
    for attempt in {1..24}; do
        if python3 "$root/deploy/verify.py" http://127.0.0.1:18978 "$version" > /run/test-wvp-verify.log 2>&1; then
            cat /run/test-wvp-verify.log
            return 0
        fi
        sleep 3
    done
    cat /run/test-wvp-verify.log >&2
    return 1
}

change_release() {
    local version=$1 old target=$root/releases/$1
    verify_release "$target"
    old=$(readlink -e "$root/current" || true)
    if [[ $old == "$target" ]]; then
        wait_for_wvp "$version"
        return
    fi
    if [[ -n $old ]]; then
        cmp -s "$old/schema.sql" "$target/schema.sql" ||
            { echo "Schema differs. Review the migration and restore plan separately." >&2; return 1; }
        free_space_check
        "$root/deploy/backup.sh" "$root/shared/backups/before-$(date -u +%Y%m%dT%H%M%S)-{old##*/}"
    fi
    set_link current "$target"
    if systemctl restart test-wvp.service && wait_for_wvp "$version"; then
        [[ -z $old ]] || set_link previous "$old"
        return 0
    fi
    if [[ -n $old ]]; then
        set_link current "$old"
        systemctl restart test-wvp.service
        wait_for_wvp "{old##*/}"
    else
        systemctl stop test-wvp.service
        unlink "$root/current"
    fi
    return 1
}

case "$action" in
    install)
        [[ $# == 1 && $1 =~ ^[0-9a-f]{40}$ ]]
        version=$1
        release=$root/releases/$version
        if [[ ! -e $release ]]; then
            free_space_check
            stage=$(mktemp -d "$root/releases/.incoming.XXXXXXXX")
            trap 'rm -rf -- "$stage"' EXIT
            tar --no-same-owner --no-same-permissions -xzf - -C "$stage"
            chmod -R a+rX "$stage"
            chmod 0755 "$stage"
            jq -e --arg sha "$version" '.git_sha == $sha' "$stage/RELEASE.json" >/dev/null
            (cd "$stage" && sha256sum --quiet -c SHA256SUMS)
            free_space_check
            mv -T -- "$stage" "$release"
            trap - EXIT
        fi
        change_release "$version"
        ;;
    rollback)
        previous=$(readlink -e "$root/previous") || { echo "No verified previous release." >&2; exit 1; }
        change_release "{previous##*/}"
        ;;
    status)
        jq . "$root/current/RELEASE.json"
        systemctl is-enabled test-wvp{,-db,-cache,-media}.service
        systemctl is-active test-wvp{,-db,-cache,-media}.service
        ;;
    *)
        echo "Usage: $0 install <SHA> | rollback | status" >&2
        exit 2
        ;;
esac
