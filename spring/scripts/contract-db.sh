#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
CREATED_DATABASE_YML=false
CONTAINER_ID=""

cleanup() {
    if [[ "$CREATED_DATABASE_YML" == true ]]; then
        rm -f "$REPO_ROOT/config/database.yml"
    fi
}
trap cleanup EXIT

if [[ -z "${CONTRACT_DATABASE_URL:-}" ]]; then
    DB_PORT="${CONTRACT_DB_PORT:-5433}"
    DB_NAME="${CONTRACT_DB_NAME:-ffcrm_contract}"
    docker rm -f ffcrm-contract-db >/dev/null 2>&1 || true
    CONTAINER_ID="$(docker run -d \
        --name ffcrm-contract-db \
        -e POSTGRES_PASSWORD=postgres \
        -e POSTGRES_DB="$DB_NAME" \
        -p "127.0.0.1:${DB_PORT}:5432" \
        "${PG_IMAGE:-postgres:16}")"

    for attempt in $(seq 1 60); do
        if docker exec "$CONTAINER_ID" pg_isready -U postgres -d "$DB_NAME" >/dev/null 2>&1; then
            break
        fi
        if [[ "$attempt" -eq 60 ]]; then
            printf 'PostgreSQL contract container did not become ready.\n' >&2
            exit 1
        fi
        sleep 1
    done
    DATABASE_URL="postgres://postgres:postgres@127.0.0.1:${DB_PORT}/${DB_NAME}"
else
    DATABASE_URL="$CONTRACT_DATABASE_URL"
fi
export DATABASE_URL

if [[ ! -f "$REPO_ROOT/config/database.yml" && -f "$REPO_ROOT/config/database.postgres.yml" ]]; then
    cp "$REPO_ROOT/config/database.postgres.yml" "$REPO_ROOT/config/database.yml"
    CREATED_DATABASE_YML=true
fi

(
    cd "$REPO_ROOT"
    DATABASE_URL="$DATABASE_URL" RAILS_ENV=development bundle exec rails db:create db:schema:load
    DATABASE_URL="$DATABASE_URL" RAILS_ENV=development bundle exec rails runner db/contract_fixtures.rb
)

IFS=$'\t' read -r FFCRM_DB_URL FFCRM_DB_USER FFCRM_DB_PASSWORD < <(
    python3 -c 'from urllib.parse import unquote, urlsplit; import os; u = urlsplit(os.environ["DATABASE_URL"]); host = u.hostname or "localhost"; port = f":{u.port}" if u.port else ""; print("jdbc:postgresql://{}{}\t{}\t{}".format(host, port + u.path, unquote(u.username or ""), unquote(u.password or "")))'
)
printf '\n%s\n' \
    "export DATABASE_URL='${DATABASE_URL}'" \
    "export FFCRM_DB_URL='${FFCRM_DB_URL}'" \
    "export FFCRM_DB_USER='${FFCRM_DB_USER}'" \
    "export FFCRM_DB_PASSWORD='${FFCRM_DB_PASSWORD}'"
