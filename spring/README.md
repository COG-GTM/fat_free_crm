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

## Domain model

The JPA domain maps the V1 Rails tables as follows:

| Entity | Table |
| --- | --- |
| `Account` | `accounts` |
| `AccountContact` | `account_contacts` |
| `AccountOpportunity` | `account_opportunities` |
| `Activity` | `activities` |
| `Address` | `addresses` |
| `Avatar` | `avatars` |
| `Campaign` | `campaigns` |
| `Comment` | `comments` |
| `Contact` | `contacts` |
| `ContactOpportunity` | `contact_opportunities` |
| `Email` | `emails` |
| `FieldGroup`, `Field` | `field_groups`, `fields` |
| `Lead` | `leads` |
| `Opportunity` | `opportunities` |
| `Preference` | `preferences` |
| `ResearchTool` | `research_tools` |
| `SavedList` | `lists` |
| `Setting` | `settings` |
| `Tag`, `Tagging` | `tags`, `taggings` |
| `Task` | `tasks` |
| `Version` | `versions` |
| Existing `User`, `Group`, `Permission` | `users`, `groups`, `permissions` |

`BaseEntity` supplies identity IDs and proxy-safe equality. `TimestampedEntity`
adds UTC `Instant` timestamps with microsecond precision; creation preserves
explicit timestamps, while updates replace `updated_at`. `CrmEntity` adds the
owner/assignee, access, soft-delete timestamp, and ordered subscribed-user IDs
for accounts, campaigns, contacts, leads, and opportunities. Tasks have their
own user and assignee fields and are not CRM entities. The existing
`User.groups` association maps `groups_users`; the other join tables are
entities with their own identity and timestamps.

Spring applies `@SQLRestriction("deleted_at IS NULL")` to accounts, contacts,
leads, opportunities, campaigns, tasks, emails, addresses, account_contacts,
account_opportunities, and contact_opportunities. **This intentionally differs
from Rails 8:** Rails hard-deletes these records and does not filter
`deleted_at`, whereas Spring hides them in normal ORM queries. The corresponding
repositories expose explicit including-deleted finders. Initializing a lazy
association to a soft-deleted row can throw `EntityNotFoundException`; no
`@NotFound` fallback is installed.

Rails polymorphic references remain a type and integer ID pair rather than an
inheritance hierarchy or Hibernate `@Any`. `RailsModelType` converts Rails class
names (including `List` for `SavedList`) and unknown names fail fast. The
read-only `PolymorphicReferenceService` resolves a pair with `EntityManager.find`;
soft-delete restrictions apply to the result.

| Column(s) | Representation |
| --- | --- |
| `subscribed_users` on CRM entities and tasks | Ordered `List<Long>` using Psych-compatible YAML; empty/null writes NULL; the legacy `"--- []\n"` form remains readable. |
| `preferences.value` | Raw Base64 Rails string plus typed JSON accessors using Ruby-compatible `Base64.encode64` wrapping. |
| `settings.value` | Raw YAML string plus a safe read-only parsed value. |
| `fields.collection`, `fields.settings` | Raw YAML strings plus read-only list/map accessors; unknown Rails YAML tags are treated as their underlying safe values. |
| `versions.object`, `versions.object_changes` | Raw PaperTrail YAML strings with no typed accessors. |

The deliberately unmapped tables are `sessions`, `schema_migrations`,
`ar_internal_metadata`, the `active_storage_*` tables,
`action_text_rich_texts`, all named `solid_queue_*` tables, and
`flyway_schema_history`. They are Rails/session, migration bookkeeping,
attachment, rich-text, background-queue, or Spring infrastructure tables. No
`cf_*` custom columns are mapped; V1 does not contain production custom-field
columns.

Regenerate the Rails-created fixture and serializer golden file in a throwaway
PostgreSQL 16 database:

```sh
docker run --rm -d --name ffcrm-entity-fixture \
  -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=ffcrm_entity_fixture -p 5433:5432 postgres:16
FFCRM_ENTITY_FIXTURE=1 SECRET_KEY_BASE=entity-fixture-only-secret-key-base-0123456789abcdef \
DATABASE_URL=postgres://postgres:postgres@127.0.0.1:5433/ffcrm_entity_fixture \
  bundle exec rails db:create db:schema:load
FFCRM_ENTITY_FIXTURE=1 SECRET_KEY_BASE=entity-fixture-only-secret-key-base-0123456789abcdef \
DATABASE_URL=postgres://postgres:postgres@127.0.0.1:5433/ffcrm_entity_fixture \
  bundle exec rake ffcrm:migration:entity_fixture
FFCRM_ENTITY_FIXTURE=1 SECRET_KEY_BASE=entity-fixture-only-secret-key-base-0123456789abcdef \
  bundle exec rspec spec/lib/tasks/entity_fixture_spec.rb
docker rm -f ffcrm-entity-fixture
```

The task aborts if any mapped table is non-empty or any `cf_*` column exists,
creates actual Rails model rows inside a rollback-only transaction, and writes
`spring/src/test/resources/db/rails/entity_fixture.sql` and
`serialized_formats.json`. The PostgreSQL fixture uses text-preserving literals
and sequence resets. `FFCRM_ENTITY_FIXTURE=1` prevents application boot from
seeding the `settings.secret_token` row, preserving the task's empty-table
precondition; the example secret key is local-only. The RSpec coverage runs in
the existing Rails test environment with the same opt-out flag. The
`spring-api.yml` workflow currently runs the Spring build, baseline check, and
gateway validation, but does **not** run RSpec.

Known gaps: counter caches are not maintained by Spring; unknown polymorphic
type strings throw rather than resolving dynamically; and lazy references to
soft-deleted rows can fail as described above. Settings/field YAML is exposed
read-only because arbitrary Ruby-object YAML cannot be emitted faithfully by
the Java model.
