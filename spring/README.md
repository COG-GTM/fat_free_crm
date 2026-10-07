# Fat Free CRM API

## Build and test

Use JDK 21 and Docker, then run `./gradlew build`. Integration tests use PostgreSQL 16
Testcontainers and checkstyle, SpotBugs, and the frozen OpenAPI copy are part of `check`.

## Run

Point the API at a PostgreSQL database already adopted from Rails, then run
`./gradlew bootRun`:

```sh
export FFCRM_DB_URL=jdbc:postgresql://localhost:5432/fat_free_crm_development
export FFCRM_DB_USER=postgres
export FFCRM_DB_PASSWORD=postgres
```

The API listens on `${FFCRM_API_PORT:-8080}`. Useful endpoints are `/actuator/health`,
`/v3/api-docs`, `/swagger-ui.html`, and `/openapi.yaml`.

## Flyway and Rails coexistence

Flyway baselines a Rails-migrated database at version 1; an empty database executes V1.
Hibernate is validation-only. `schema_migrations` and `ar_internal_metadata` are kept so
Rails and Spring can share the database during the strangler window. V1 is immutable
after release: all schema changes are additive V2+ migrations. Production `cf_*`
columns are not in the Rails schema snapshot and must be accounted for before production
adoption.

Regenerate or check the baseline from the current `db/schema.rb` with
`spring/scripts/generate-baseline.sh [--check]`. Set `PG_IMAGE` to override the
PostgreSQL container image.

## Gateway

Start the Rails app and gateway with `docker compose -f spring/docker-compose.yml up`.
The gateway listens on port 8000 and sends all traffic to Rails by default. To migrate
a resource, enable the commented Spring upstream, request-method map, and accounts
location in `gateway/nginx.conf`; only GET requests then go to Spring. Roll back by
commenting those directives again and reloading nginx.

## Operations and CI

Console logs use ECS structured JSON. The `spring-api.yml` workflow runs the Spring
build, Rails baseline drift check, and nginx configuration validation when the Spring
project, frozen contract, Rails schema/migrations, or workflow changes.
