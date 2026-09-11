# Fat Free CRM — Spring Boot read-only API (strangler phase 1)

Spring Boot 3.5 / Java 21 / Maven service that serves **read-only** REST endpoints against the
**same PostgreSQL database the Rails app uses**. It is the first strangler slice described in
[`docs/migration/target-architecture.md` §2.7](../docs/migration/target-architecture.md) (steps 1–2:
contract frozen in `openapi.yaml`, read-only index/show/autocomplete behind a gateway). Rails
remains the owner of the schema and of every write path.

Nothing outside `java/` is touched by this service; it never runs DDL
(`spring.jpa.hibernate.ddl-auto=none`, Flyway disabled).

## Build / run

Requirements: JDK 21, Docker (for the Testcontainers-based integration tests).

```bash
cd java
./mvnw -B verify                 # compile + integration tests (starts postgres:16-alpine via Docker)

# run against a local PostgreSQL that already has the Rails schema
DB_HOST=localhost DB_PORT=5432 DB_NAME=fat_free_crm_development \
DB_USER=postgres DB_PASSWORD=secret ./mvnw spring-boot:run
```

| Env var       | Default                    |
|---------------|----------------------------|
| `DB_HOST`     | `localhost`                |
| `DB_PORT`     | `5432`                     |
| `DB_NAME`     | `fat_free_crm_development` |
| `DB_USER`     | `postgres`                 |
| `DB_PASSWORD` | *(empty)*                  |

Server port is `8080`. Useful URLs:

- `GET /api/v1/ping` — echoes the authenticated user (`{userId, admin, groupIds}`); smoke test for the auth shim
- `GET /swagger-ui.html` — Swagger UI rendering the **frozen** contract
- `GET /openapi.yaml` — verbatim copy of `docs/migration/openapi.yaml`
- `GET /v3/api-docs` — springdoc's generated document (development aid, not the contract of record)
- `GET /actuator/health`

### Path prefix

`openapi.yaml` documents the Rails paths (`/accounts`, `/accounts/{id}`, `/accounts/auto_complete`, …).
The Java service mounts the same resources under **`/api/v1`**, e.g. `/api/v1/accounts`. The gateway
maps one onto the other while traffic is shifted per resource.

## Authentication: trusted-header shim (TEMPORARY)

Real authentication (stateless JWT, target-architecture.md §2.4) is deferred. Until then
`TrustedHeaderAuthenticationFilter` authenticates every `/api/**` request from the header

```
X-User-Id: <numeric users.id>
```

The user row is loaded (`users.id`, `users.admin`) together with its group ids
(`groups_users`), and a `CurrentUser(id, admin, groupIds)` record becomes the Spring Security
principal. Services obtain it through `CurrentUserProvider`.

Rejected with **401 `application/problem+json`**: missing header, non-numeric header, unknown id,
soft-deleted (`deleted_at`) or suspended (`suspended_at`) user.

> **Security caveat.** The header is trusted blindly. This service must be reachable **only**
> through the gateway that validates the Devise session and sets/overwrites `X-User-Id`. Never
> expose port 8080 directly. The shim will be replaced by JWT in a later phase.

Public (no header needed): `/swagger-ui/**`, `/swagger-ui.html`, `/openapi.yaml`, `/v3/api-docs/**`,
`/actuator/health`. Everything else outside `/api/**` is denied.

## Record visibility

`AccessControlSpecifications.visibleTo(currentUser, assetType)` is a generic JPA `Specification`
reproducing Rails `Model.my(user)` (`app/models/users/ability.rb`):

```sql
access = 'Public' OR user_id = :me OR assigned_to = :me
  OR EXISTS (SELECT 1 FROM permissions p
              WHERE p.asset_type = :assetType AND p.asset_id = <entity>.id
                AND (p.user_id = :me OR p.group_id IN (:myGroupIds)))
```

Admins see everything. Soft-deleted rows are excluded globally by `@SQLRestriction("deleted_at IS NULL")`
on every entity. `assetType` is the Rails class name stored in `permissions.asset_type`
(`"Account"`, `"Contact"`, …).

## Error contract

RFC 9457 `application/problem+json` (`type`, `title`, `status`, `detail`, `instance`) for
401, 403, 404 and 400 — see `ApiExceptionHandler`. Note the deliberate delta from Rails: CanCan
denials are HTTP **401** in Rails today and **403** here (target-architecture.md §2.6).

## Endpoints in phase 1

| Endpoint | Status |
|---|---|
| `GET /api/v1/ping` | this PR (smoke test; may be removed later) |
| `GET /api/v1/accounts` | `page`, `perPage`/`per_page` (clamped to 200), `query` (name/email substring), `sortBy`/`sort_by` (`name ASC`, `rating DESC`, `created_at DESC` default, `updated_at DESC`) |
| `GET /api/v1/accounts/{id}` | 404 problem+json when missing, soft-deleted or not visible to the caller |
| `GET /api/v1/accounts/autocomplete?term=` | `term` (name/email substring), max 10 results by `name ASC`; `related` exclusion deferred |
| `GET /api/v1/contacts` | added by follow-up PR |
| `GET /api/v1/contacts/{id}` | added by follow-up PR |
| `GET /api/v1/contacts/autocomplete?term=` | added by follow-up PR |

### List envelope

Per target-architecture.md §2.5 list endpoints return

```json
{ "items": [ ... ], "page": 1, "perPage": 20, "totalCount": 123 }
```

whereas the Rails JSON today is a bare array. `page` defaults to 1; `perPage` defaults to 20 and is
clamped to `1..200` (`PaginationProperties`, mirroring the Rails `per_page` clamp).
Autocomplete keeps the existing `{ "results": [ { "id", "text" } ] }` shape.

## Layout

```
com.fatfreecrm
├── api/          controllers, ApiExceptionHandler, ProblemDetails   (api/dto: records + MapStruct mappers)
├── service/      business rules, ResourceNotFoundException
├── repository/   Spring Data JPA repositories (+ JpaSpecificationExecutor)
├── domain/       JPA entities mirroring db/schema.rb (CrmEntity, Account, Contact, Permission, User, GroupUser)
├── security/     CurrentUser, CurrentUserProvider, AccessControlSpecifications, header auth shim, SecurityConfig
└── config/       CorsConfig, OpenApiConfig, PaginationProperties
```

`subscribed_users` is Rails-serialized YAML and is mapped as opaque text (see
`docs/migration/data-model.md`, "Serialized columns"). Dynamic `cf_*` custom-field columns are not
mapped.

## Tests

`src/test/java/com/fatfreecrm/AbstractIntegrationTest` boots the app on a random port against a
shared `postgres:16-alpine` Testcontainer. The Rails schema is stood in for by the **test-only**
`src/test/resources/schema-test.sql` (tables `users`, `groups`, `groups_users`, `permissions`,
`accounts`, `contacts`, faithful to `db/schema.rb`). `TestDataSeeder` (JdbcTemplate) provides
`insertUser`, `insertGroup`, `addUserToGroup`, `insertPermission`, `insertAccount`, `insertContact`
and `truncateAll()` (run before each test).

## Deferred to later phases

- All write paths (POST/PUT/PATCH/DELETE) — Rails stays authoritative
- JWT authentication (replaces the `X-User-Id` shim), registration / confirmation / password reset
- Custom fields (`cf_*` columns → JSONB, see `docs/migration/custom-fields-migration.md`)
- Remaining entities: Campaign, Lead, Opportunity, Task, Comment, Email, Address, Tag, List, Setting, Preference, audit (`versions`)
- Ransack `q[...]` advanced search and `query` text/tag search
- `related` exclusion in autocomplete
- Non-JSON formats: XML, CSV, XLS, vCard, Atom/RSS
- Flyway migrations (dependency present, `spring.flyway.enabled=false`)
- Schema validation by Hibernate (`ddl-auto=validate`) — not used because Hibernate's validator is
  strict about Rails' `int4`/`timestamp(6)` column types and would break on Rails upgrades
