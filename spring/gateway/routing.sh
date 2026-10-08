#!/usr/bin/env bash
set -euo pipefail

usage() {
    echo "Usage: routing.sh enable|disable <upstream|accounts|opportunities>... [--file <path>]" >&2
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
        upstream|accounts|opportunities)
            blocks+=("$1")
            shift
            ;;
        *)
            usage
            ;;
    esac
done
[[ ${#blocks[@]} -gt 0 && -f "$config_file" ]] || usage

validate_markers() {
    local block=$1
    local begin=$2
    local end=$3
    awk -v block="$block" -v begin="$begin" -v end="$end" '
        function trim(line) {
            sub(/^[[:space:]]*/, "", line)
            sub(/[[:space:]]*$/, "", line)
            return line
        }
        {
            marker = trim($0)
            if (marker == begin) {
                if (inside) {
                    printf "routing.sh: nested BEGIN marker for block %s\n", block > "/dev/stderr"
                    errors++
                }
                begin_count++
                inside = 1
                next
            }
            if (marker == end) {
                if (!inside) {
                    printf "routing.sh: END marker without BEGIN for block %s\n", block > "/dev/stderr"
                    errors++
                } else {
                    end_count++
                    inside = 0
                }
            }
        }
        END {
            if (begin_count == 0) {
                printf "routing.sh: BEGIN marker not found for block %s\n", block > "/dev/stderr"
                errors++
            }
            if (inside) {
                printf "routing.sh: END marker not found for block %s\n", block > "/dev/stderr"
                errors++
            }
            if (begin_count != end_count) {
                printf "routing.sh: unbalanced markers for block %s\n", block > "/dev/stderr"
                errors++
            }
            exit (errors > 0)
        }
    ' "$config_file"
}

validate_block() {
    local action=$1
    local block=$2
    local begin=$3
    local end=$4
    awk -v action="$action" -v block="$block" -v begin="$begin" -v end="$end" '
        function trim(line) {
            sub(/^[[:space:]]*/, "", line)
            sub(/[[:space:]]*$/, "", line)
            return line
        }
        {
            marker = trim($0)
            if (marker == begin) {
                begin_count++
                inside = 1
                section_active = 0
                next
            }
            if (marker == end) {
                if (inside) {
                    end_count++
                    if (action == "enable" && section_active == 0) {
                        printf "routing.sh: enabled block %s has no active lines\n", block > "/dev/stderr"
                        errors++
                    }
                    inside = 0
                }
                next
            }
            if (inside && $0 !~ /^[[:space:]]*$/) {
                if (action == "enable") {
                    if ($0 ~ /^[[:space:]]*#/) {
                        printf "routing.sh: enabled block %s has a commented line at %d\n", block, NR > "/dev/stderr"
                        errors++
                    } else {
                        section_active++
                    }
                } else if ($0 !~ /^[[:space:]]*#/) {
                    printf "routing.sh: disabled block %s has an uncommented line at %d\n", block, NR > "/dev/stderr"
                    errors++
                }
            }
        }
        END {
            if (begin_count == 0) {
                printf "routing.sh: BEGIN marker not found for block %s\n", block > "/dev/stderr"
                errors++
            }
            if (inside || begin_count != end_count) {
                printf "routing.sh: unbalanced markers for block %s\n", block > "/dev/stderr"
                errors++
            }
            exit (errors > 0)
        }
    ' "$config_file"
}

for block in "${blocks[@]}"; do
    begin="# ---- BEGIN Spring routing: $block ----"
    end="# ---- END Spring routing: $block ----"
    begin_address="^[[:space:]]*$begin[[:space:]]*$"
    end_address="^[[:space:]]*$end[[:space:]]*$"
    validate_markers "$block" "$begin" "$end"
    if [[ "$action" == "enable" ]]; then
        expression="/$begin_address/,/$end_address/ { /$begin_address/! { /$end_address/! s/^([[:space:]]*)# /\\1/; } }"
    else
        expression="
            /$begin_address/,/$end_address/ {
                /$begin_address/! {
                    /$end_address/! {
                        /^([[:space:]]*)([^#[:space:]].*)$/ {
                            G
                            s/^(.*)\\n(.*)$/\\2\\n\\1/
                            s/^([[:space:]]*)\\n\\1/\\1# /
                        }
                    }
                }
                /$begin_address/ {
                    h
                    s/^([[:space:]]*).*/\\1/
                    x
                }
            }
        "
    fi
    sed -E -i "$expression" "$config_file"
    validate_block "$action" "$block" "$begin" "$end"
done
