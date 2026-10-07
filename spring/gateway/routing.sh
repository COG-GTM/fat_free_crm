#!/usr/bin/env bash
set -euo pipefail

usage() {
    echo "Usage: routing.sh enable|disable <upstream|accounts>... [--file <path>]" >&2
    exit 2
}

[[ $# -ge 2 ]] || usage
action=$1
shift
[[ "$action" == "enable" || "$action" == "disable" ]] || usage

blocks=()
config_file="$(dirname "$0")/nginx.conf"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --file)
            [[ $# -ge 2 ]] || usage
            config_file=$2
            shift 2
            ;;
        upstream|accounts)
            blocks+=("$1")
            shift
            ;;
        *)
            usage
            ;;
    esac
done
[[ ${#blocks[@]} -gt 0 && -f "$config_file" ]] || usage

for block in "${blocks[@]}"; do
    begin="# ---- BEGIN Spring routing: $block ----"
    end="# ---- END Spring routing: $block ----"
    if [[ "$action" == "enable" ]]; then
        expression="/^$begin$/,/^$end$/ { /^$begin$/! { /^$end$/! s/^([[:space:]]*)# /\\1/; } }"
    else
        expression="/^$begin$/,/^$end$/ { /^$begin$/! { /^$end$/! s/^([[:space:]]*)([^#[:space:]].*)/\\1# \\2/; } }"
    fi
    sed -E -i "$expression" "$config_file"
done
