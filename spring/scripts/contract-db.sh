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
    if docker container inspect ffcrm-contract-db >/dev/null 2>&1; then
        CONTAINER_ID="ffcrm-contract-db"
        EXISTING_DB_PORT="$(docker inspect \
            --format '{{with (index .NetworkSettings.Ports "5432/tcp")}}{{(index . 0).HostPort}}{{end}}' \
            "$CONTAINER_ID")"
        if [[ "$EXISTING_DB_PORT" != "$DB_PORT" ]]; then
            printf 'Existing ffcrm-contract-db uses host port %s, not requested port %s.\n' \
                "$EXISTING_DB_PORT" "$DB_PORT" >&2
            exit 1
        fi
        docker start "$CONTAINER_ID" >/dev/null
    else
        CONTAINER_ID="$(docker run -d \
            --name ffcrm-contract-db \
            -e POSTGRES_PASSWORD=postgres \
            -e POSTGRES_DB="$DB_NAME" \
            -p "127.0.0.1:${DB_PORT}:5432" \
            "${PG_IMAGE:-postgres:16}")"
    fi

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
    DATABASE_URL="$DATABASE_URL" RAILS_ENV=development bundle exec rails db:create
    DATABASE_HAS_USERS="$(DATABASE_URL="$DATABASE_URL" RAILS_ENV=development bundle exec rails runner \
        'print ActiveRecord::Base.connection.data_source_exists?("users")')"
    if [[ "$DATABASE_HAS_USERS" == "true" ]]; then
        if [[ "${CONTRACT_FIXTURES_RESET:-0}" != "1" ]]; then
            printf 'Contract database %s is not empty (users table exists).\n' "$DATABASE_URL" >&2
            printf 'Set CONTRACT_FIXTURES_RESET=1 to wipe and reload it.\n' >&2
            exit 1
        fi
        printf 'Resetting contract database %s as requested.\n' "$DATABASE_URL"
        DATABASE_URL="$DATABASE_URL" RAILS_ENV=development bundle exec rails runner \
            'ActiveRecord::Base.connection.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = current_database() AND pid <> pg_backend_pid()")'
        DATABASE_URL="$DATABASE_URL" RAILS_ENV=development bundle exec rails db:drop db:create
    fi
    DATABASE_URL="$DATABASE_URL" RAILS_ENV=development bundle exec rails db:schema:load
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
