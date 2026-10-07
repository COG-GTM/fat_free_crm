#!/usr/bin/env bash
set -euo pipefail

container=ffcrm-prodshape-pg
pg_port="${PGPORT:-55432}"
password="${PRODUCTION_SHAPED_PASSWORD:-ffcrm_prodshape_local}"

if [[ "${1:-}" == "--down" ]]; then
  docker rm -f "$container" >/dev/null 2>&1 || true
  echo "Removed $container."
  exit 0
fi

if ! docker inspect "$container" >/dev/null 2>&1; then
  if ! docker pull postgres:16; then
    docker pull mirror.gcr.io/library/postgres:16
    docker tag mirror.gcr.io/library/postgres:16 postgres:16
  fi
  docker run --detach \
    --name "$container" \
    --publish "127.0.0.1:${pg_port}:5432" \
    --env POSTGRES_PASSWORD="$password" \
    --env POSTGRES_DB=ffcrm_production_shaped \
    postgres:16
else
  running="$(docker inspect --format '{{.State.Running}}' "$container")"
  if [[ "$running" != true ]]; then
    docker start "$container" >/dev/null
  fi
  published_mapping="$(docker port "$container" 5432/tcp | head -n 1)"
  published_port="${published_mapping##*:}"
  if [[ -z "$published_port" ]]; then
    echo "Could not determine the published PostgreSQL port for $container." >&2
    exit 1
  fi
  if [[ -n "${PGPORT:-}" && "$published_port" != "$pg_port" ]]; then
    echo "$container is bound to port $published_port, not requested PGPORT=$pg_port." >&2
    exit 1
  fi
  pg_port="$published_port"
fi

ready=false
for attempt in {1..60}; do
  if docker exec "$container" pg_isready --username postgres --dbname ffcrm_production_shaped >/dev/null; then
    ready=true
    break
  fi
  sleep 1
done
if [[ "$ready" != true ]]; then
  echo "PostgreSQL did not become ready in $container." >&2
  exit 1
fi

database_url="postgres://postgres:${password}@127.0.0.1:${pg_port}/ffcrm_production_shaped"
export DATABASE_URL="$database_url"
export RAILS_ENV=development

bin/rails runner 'abort "DATABASE_URL did not select PostgreSQL" unless ActiveRecord::Base.connection_db_config.adapter == "postgresql"'
DISABLE_DATABASE_ENVIRONMENT_CHECK=1 bin/rails db:drop db:create db:migrate
bin/rails runner script/migration/seed_production_shaped.rb

if [[ -z "${PG_DUMP:-}" ]]; then
  if command -v pg_dump >/dev/null && pg_dump --version | grep -Eq 'PostgreSQL\) 16(\.|$)'; then
    export PG_DUMP=pg_dump
  else
    export PG_DUMP='docker run --rm --network host -e PGPASSWORD postgres:16 pg_dump'
  fi
fi

mkdir -p docs/migration/baseline
OUTPUT=docs/migration/baseline/production-shaped-schema.sql bin/rails ffcrm:migration:baseline_dump
FORMAT=markdown COUNT_ROWS=true OUTPUT=docs/migration/baseline/column-census.md bin/rails ffcrm:migration:column_census
FORMAT=json COUNT_ROWS=true OUTPUT=docs/migration/baseline/column-census.json bin/rails ffcrm:migration:column_census

echo "Production-shaped baseline artifacts generated."
echo "PRODUCTION_SHAPED_DATABASE_URL=$database_url"
