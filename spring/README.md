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

### Baseline drift after V1 ships

Before V1 is released, rerun the generator to regenerate both artifacts. After V1 has
been applied to any shared database, never regenerate V1; add an additive
`V2+__*.sql` migration and refresh only the Rails schema fixture. Splitting the check
into a frozen V1 comparison and a current Rails fixture comparison is follow-up work
once V1 ships.

## Domain model

`com.fatfreecrm.domain` maps every application table in `docs/migration/data-model.md`
one-to-one, with no JPA inheritance: `CrmEntity` is a `@MappedSuperclass` for the five
core entities (owner, assignee, `access`, `deleted_at`, `subscribed_users`), Rails
polymorphic associations are `PolymorphicRef` embeddables over the existing
`*_type`/`*_id` column pairs, and soft delete is `@SQLRestriction("deleted_at IS NULL")`
on every table Rails soft-deletes. Repositories extend `SoftDeletableRepository`
(`softDelete(id)` stamps `deleted_at` inside its own transaction and never issues a
physical `DELETE`). Rails-serialised columns (`subscribed_users` YAML, `settings.value`,
`preferences.value`, `fields.collection`/`settings`, PaperTrail `versions`) are stored
byte-for-byte as Rails writes them; `versions` is read-only. Custom `cf_*` columns are
out of scope here (see `docs/migration/custom-fields-migration.md`).

### Data-fidelity fixture

`spring/src/test/resources/db/rails/rails_seed_data.sql` is a `pg_dump` of a database
seeded by Rails itself (`rake ffcrm:migration:seed_fixture`, one or more rows per table
including soft-deleted rows). `RailsSeededDataFidelityTest` loads it on top of the Rails
schema fixture and asserts that every table is mapped by exactly one entity, every column
is mapped, every row reads back attribute-for-attribute, and re-inserting the entities
through Hibernate reproduces the Rails rows byte-for-byte. Regenerate it with
`spring/scripts/generate-seed-fixture.sh` whenever the seed task or the Rails schema
changes.

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

## Authentication

The API provides `POST /api/v1/auth/login` with `username` and `password`,
`POST /api/v1/auth/refresh` with `refreshToken`, authenticated `POST
/api/v1/auth/logout`, and authenticated `GET /api/v1/users/me`. Login and refresh
return a bearer access token and refresh token. JWTs are signed with HS256 and
contain `sub` (the user id), `username`, `admin`, `typ` (`access` or `refresh`),
`iat`, `exp`, and `jti`. The default access-token TTL is 15 minutes and the
default refresh-token TTL is 14 days.

Configure `FFCRM_JWT_SECRET` with at least 32 UTF-8 bytes. Optional
`FFCRM_JWT_ACCESS_TTL` and `FFCRM_JWT_REFRESH_TTL` values use Spring duration
syntax (for example, `15m` and `14d`). The API verifies existing Rails
`authlogic_sha512` password hashes using `password_salt`; successful logins do
not rehash or otherwise change password columns. Password upgrade-on-login is a
cutover item for AB-274. Logout is currently stateless and does not revoke
issued tokens. Every authenticated request reloads the user and rejects
unconfirmed or suspended accounts.

Regenerate the committed Rails compatibility fixture in the development
environment with:

```sh
bundle exec rake ffcrm:migration:legacy_auth_fixture \
  OUTPUT=spring/src/test/resources/auth/rails-legacy-users.json
```
