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
`V2+` migration and refresh only the Rails schema fixture. Splitting the check
into a frozen V1 comparison and a current Rails fixture comparison is follow-up work
once V1 ships.

## Gateway

Start the Rails app and gateway with `docker compose -f spring/docker-compose.yml up`.
The gateway listens on port 8000 and sends all traffic to Rails by default. To migrate
a resource, enable the named blocks with `gateway/routing.sh enable upstream accounts`;
only GET and HEAD account requests then go to Spring. Roll back with
`gateway/routing.sh disable accounts upstream` and reload nginx. Blocks are disabled
by default, and the script accepts `--file <path>` to prepare a candidate config.

## Accounts read API (AB-270)

Authenticated Accounts reads are available at:

| Endpoint | Behavior |
| --- | --- |
| `GET /api/v1/accounts` | Rails-compatible rows in a `ListResult` envelope with paging and category facets. |
| `GET /api/v1/accounts/{numericId}` | Read one accessible account and record a Rails-compatible `view` row in `versions`. |
| `GET /api/v1/accounts/autocomplete` | Return `{results: [{id, text}]}` with a ten-row limit. |

List parameters include `page`, `per_page`, `query`, `sort_by`, Rails-style `q[...]`, and
`category` (comma-separated category values; `other` selects a NULL category). `category`
is ignored when an advanced `q[...]` query is present. The preferences
`accounts_per_page` and `accounts_sort_by` are decoded from the user's Rails Base64-JSON
preferences and provide defaults only when their corresponding request parameters are absent.
Explicit request values take precedence.

| Rails preference | Default for | Behavior |
| --- | --- | --- |
| `accounts_per_page` | `per_page` | Positive JSON integer or numeric string; invalid values are ignored. |
| `accounts_sort_by` | `sort_by` | Rails sort selector used only when `sort_by` is absent. |

Autocomplete accepts `term` and either `excludeRelated` or Rails' `related`; when both are
provided, `excludeRelated` wins. Related values support a bare account ID and `users/<id>`
(exclude accounts owned by that user). Other plural types have no account-collection exclusion.
Blank terms do not add a text predicate. Results are scoped to accounts the caller can read,
ordered by ID, and limited to ten.

Showing an account writes a `versions` row with `item_type: Account`, `event: view`, and the
viewer's ID in `whodunnit`; other version-change fields remain null.

The serializer derives field order and PostgreSQL types from database metadata, appends
`tag_list` last in tagging-ID order, parses the Rails `subscribed_users` YAML array, and
serializes runtime checkbox custom fields. PostgreSQL numeric values use Rails decimal
strings; timestamps are UTC ISO-8601 strings truncated to milliseconds. Sensitive columns
whose names contain `password`, `token`, or `salt` are never emitted.

## Adding a read family

Phase B should reuse the Accounts read foundation rather than add family-specific serialization
or list plumbing:

1. Register a `RailsResource` descriptor and `SearchableEntity` metadata. Define the table/model,
   dynamic text column, sort whitelist, association search whitelist, tag/facet behavior, and any
   state filter. Keep `SearchableEntities` limited to families in the active phase.
   - CRM entity resources must pass `RailsResources.CRM_EXCLUDED_COLUMNS` as `excludedColumns`;
     it excludes AB-271's Rails-ignored `custom_fields` JSONB column.
2. Add a controller that extracts the authenticated user and query parameters, applies user
   preference defaults, and delegates list/show/autocomplete to `CrmReadService`. Protect show
   with `hasPermission(#id, '<RailsModel>', 'read')`; declare only the family's supported related
   autocomplete exclusions.
3. Add Rails-generated search-matrix cases and replay them in Spring parity tests. Use a safe
   throwaway PostgreSQL corpus, and clean up any preference rows written by each case.
4. Add enforced contract cases based on captured Rails responses. Use `bodyPointer` when the
   Spring list envelope must be compared with Rails' bare array, plus Spring-vs-Spring
   `expect` cases for envelope metadata Rails does not return. Allow-list only demonstrated,
   narrowly scoped differences.
5. Add a disabled-by-default gateway block and validate the default and enabled nginx configs.
   Use `gateway/routing.sh enable|disable <block>...` for cutover and rollback.
6. Run the family's integration tests, `./gradlew build`, its live contract cases, the relevant
   Rails RuboCop/RSpec checks, the nginx checks, and `spring/scripts/generate-baseline.sh --check`.

## Operations and CI

Console logs use ECS structured JSON. The `spring-api.yml` workflow runs the Spring
build, Rails baseline drift check, and nginx configuration validation when the Spring
project, frozen contract, Rails schema/migrations, or workflow changes.
## Contract diff

See [contract-diff.md](contract-diff.md) for the Rails/Spring request harness, fixture database,
case schema, allow-list, and report instructions.

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

### Deviation from Jira: soft delete

AB-265 requested `@SQLRestriction`-based soft delete; it is not implemented
because Rails 8 has no paranoia gem (removed long ago), no Rails runtime code
in `app/` or `lib/` filters or sets `deleted_at` (the fixture task writes
representative values only inside its rolled-back transaction), and Rails shows
those rows and hard-deletes on destroy. The epic coexistence rule is that Rails
semantics win: a restriction would break the contract-diff parity AB-270
depends on. Real soft delete needs a Rails change plus a product decision and
is out of scope. `deleted_at` is mapped as a plain column.

Rails polymorphic references remain a raw `String` type and integer ID pair
rather than an inheritance hierarchy or Hibernate `@Any`. Typed
`xModelType()` accessors recognize Rails class names (including `List` for
`SavedList`); unknown type strings are preserved and resolve to an empty
`Optional`. The read-only `PolymorphicReferenceService` resolves known pairs
with `EntityManager.find`.

CRM `access` values are also stored as raw strings. `accessLevel()` recognizes
`Public`, `Private`, and `Shared`, while retaining Rails-accepted unknown values.

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

Known gaps: counter caches are not maintained by Spring; `subscribed_users`
rows still in the pre-2012 `!ruby/object:Set` YAML form fail to load in Spring,
and also in Rails 8 (`Psych::DisallowedClass`: Set is not in
`yaml_column_permitted_classes`). Repair them to YAML arrays at migration time.
Settings/field YAML is exposed read-only because arbitrary Ruby-object YAML
cannot be emitted faithfully by the Java model.

## Authorization (row-level access)

`com.fatfreecrm.security.authz.AccessPolicy#accessibleBy(user, type)` returns a JPA
`Specification` that reproduces Rails `Klass.my(user)` / `accessible_by(user.ability)`
(`app/models/users/ability.rb`). List queries compose it with search as
`accessibleBy(user, type).and(search)`. `CrmAccessPolicy` builds the predicate in SQL:

| Type | Rule (non-admin) |
| --- | --- |
| Account, Campaign, Contact, Lead, Opportunity | `access = 'Public'` OR `user_id = me` OR `assigned_to = me` OR `EXISTS (permissions WHERE asset_type = '<Class>' AND asset_id = id AND (user_id = me OR group_id IN (my groups)))` |
| Task | `user_id = me` OR `assigned_to = me` OR `completed_by = me` |
| Comment, Email | `user_id = me` (the parent's visibility is not checked) |
| User | `id = me` |

Admins get a conjunction (no filter). Any other entity type throws `IllegalArgumentException`.
The admin flag and group membership are re-read from the database on every call, never taken
from JWT claims; an unknown user id sees nothing. Permission and group checks are `EXISTS` / `IN`
subqueries, so `count` and `Page.getTotalElements()` never fan out and no `DISTINCT` is needed.

Single-record checks use `@PreAuthorize("hasPermission(#id, 'Account', 'read')")`
(`CrmPermissionEvaluator`, wired by `MethodSecurityConfig`). It evaluates
`repository.exists(byId.and(accessibleBy(...)))` with the same Specification, so list and fetch
cannot disagree. `read`, `update`, `destroy` and `manage` are equivalent, because every Rails rule
grants `:manage`. Responses:

| Case | Rails (JSON) | Spring |
| --- | --- | --- |
| No credentials | 401 | 401 problem+json |
| Record exists but is outside the user's scope | 401 `CanCan::AccessDenied` (`load_and_authorize_resource` loads by id, then authorizes) | **403** problem+json (settled, allow-listed delta) |
| Record id does not exist | 404 | 404 problem+json (the evaluator throws `EntityNotFoundException`) |

Rails also exposes whether a record exists (401 vs 404), and Spring matches this.
`/api/v1/admin/**` requires `ROLE_ADMIN` in `SecurityConfig`. `@AdminOnly` is the same guard as a
method annotation. `ROLE_ADMIN` comes from `users.admin`, re-read on every request.

`PermissionService` mirrors `FatFreeCRM::Permissions` for Shared-access writes. AB-272 wires it to
endpoints. `setAccess` with any value other than `Shared` deletes all of the asset's permission
rows. `setUserIds` / `setGroupIds` delete every row when access is not `Shared`. Otherwise they
flatten the ids, drop blanks, convert them like Ruby `to_i`, delete rows of that kind that are no
longer listed, and insert the missing ones. Rows of the other kind are left alone.
`updateSharing(entity, access, userIds, groupIds)` applies the three in Rails attribute order.
Rows carry the Rails `asset_type` (e.g. `Account`) and identical `created_at`/`updated_at`.

### Rails semantics reproduced (verified by the matrix)

- A `Private` record with a permission row **is** visible to the permitted user or group. CanCan
  ORs every rule and never checks `access` on the permission rule.
- `Shared` with no permission rows is visible only to the owner, the assignee and admins.
- A NULL `assigned_to` or `user_id` never matches (SQL `=` on NULL).
- Permissions left behind by a **destroyed group** grant nothing, even if a stale `groups_users` row
  remains. Rails reads `user.group_ids` through the `groups` table, so the group subquery selects
  from `groups`.
- A permission row only grants access for its own `asset_type`. For example, a `Contact` row with
  the same id as an Account does not expose that Account.
- Inherited raw `access` values (`Lead`, `Campaign`) are not `Public`, so they are visible only via
  owner, assignee or permission.
- Soft-deleted rows (`deleted_at` set) stay visible. Rails has no paranoia filter.
- Tasks are authorized by owner, assignee or `completed_by`. `Task.my` (the dashboard filter:
  `(user_id = me AND assigned_to IS NULL) OR assigned_to = me`) is a view scope, not the
  authorization rule. For example, a task the user created and assigned to someone else is not in
  `Task.my` but is still fetchable. Task permission rows are ignored, as in `Ability`.
- Deliberate deviation: Ruby runs `uniq` before `to_i`, so `["3", 3]` builds two identical
  permission rows. Java de-duplicates after conversion. Visibility is the same.

### Rails parity matrix

`rake ffcrm:migration:authz_matrix` seeds 8 actors (owner, assignee, shared_user, group_member,
completer, stale_member, unrelated, admin), 12 records per CRM entity, and tasks, comments and
emails, using real Rails models inside a rollback-only transaction. It records
`Klass.my(user).pluck(:id)` and `count` for every actor and type, plus the rows Rails writes for a
sequence of `access=` / `user_ids=` / `group_ids=` assignments. Output goes to
`spring/src/test/resources/authz/authz_matrix.json` and `authz_fixture.sql`.
`AuthorizationMatrixTest` loads the same seed and asserts identical ids, counts and
`PageRequest` totals for all 72 cells, evaluator/Specification agreement for every record × actor,
the HTTP 401/403/404 and admin-namespace behavior, and Java-written permission rows identical to
the Rails-written ones. Regenerate against an empty Rails-migrated PostgreSQL database:

```sh
FFCRM_ENTITY_FIXTURE=1 SECRET_KEY_BASE=entity-fixture-only-secret-key-base-0123456789abcdef \
  DATABASE_URL=postgres://postgres:postgres@localhost:5432/ffcrm_authz RAILS_ENV=development \
  bundle exec rake ffcrm:migration:authz_matrix
```

The same task also records `Klass.my(user)` / `accessible_by` for admin, alice, bob, sam and
carol on the AB-266 contract-diff corpus (`db/contract_fixtures.rb`, also rolled back), writing
`contract_corpus_matrix.json` and `contract_corpus.sql`. `ContractCorpusAuthorizationTest` asserts
identical list ids, page totals and evaluator decisions on that corpus, so the Specifications are
checked on the data the harness runs against. The matrix matches the visibility table in
[contract-diff.md](contract-diff.md). The enforced account-show case for Bob exercises the
documented Rails 401 versus Spring 403 authorization difference.

The `authz-matrix` job in `spring-api.yml` regenerates both matrices on a PostgreSQL service,
fails if the committed files drift, and runs `spec/lib/tasks/authz_matrix_spec.rb`.

## List queries (AB-269)

`CrmQueryService.list(AuthenticatedUser, Class<T>, ListQuery)` is the only list path. It composes
`accessPolicy.accessibleBy(user, type)` — the `CrmAccessPolicy` from AB-268 — with the parsed search
specification (`accessibleBy(...).and(searchSpec)`). `CrmAccessPolicy.supports(Class)` must cover the
listed type (Account, Contact, Lead, Opportunity, Campaign are registered; other families are
added only in their own phase);
unsupported types propagate the policy's `IllegalArgumentException`.

Parameters mirror Rails `EntitiesController#get_list_of_records`: `page` (Ruby `to_i`; `<1` -> 404
problem+json, beyond-last -> empty page), `per_page` (Ruby `to_i` clamped to 1..200, default 20 or the
record's `preferredPerPage`), `query` (text + `#tag` syntax per `parse_query_and_tags`), `sort_by`
(Spring-only replacement for the Rails session sort preference, additive) and `q[...]` Ransack trees
(predicates `eq not_eq cont not_cont i_cont start not_start end not_end matches does_not_match lt lteq
gt gteq in not_in null not_null present blank true false`, `_any`/`_all` compounds, `_or_`/`_and_`
attribute splits, `m`/`g`/`c`/`s` combinator form). `ListQuery.withPreferences(perPage, sortBy)` applies
decoded preferences as list defaults; preferences are not part of the OpenAPI contract.

The response envelope is `ListResult` (`{items, page, perPage, totalCount, totalPages, facets}`), which
intentionally differs from Rails' bare JSON array. Enforced contract cases use `bodyPointer: /items`
to compare row lists, while Spring-vs-Spring envelope expectations pin paging and facet metadata.

Unknown attributes/predicates are dropped like Rails (`ignore_unknown_conditions`); set
`ffcrm.search.ignore-unknown-conditions=false` to get a 400 with an `invalidParameters` problem property
instead. Structurally malformed `q` trees always 400.

Intentional deviations: no Chronic natural-language dates (ISO only; ISO dates become noon UTC, matching
Rails' cast); association traversal stops at the CRM whitelists — Task/Address/Email/Comment/Tag columns
are searchable but expose no further hops (Rails could reach `users` through `comments`, a security
deviation); association sorts inside `q[s]` are ignored as Rails ignores invalid sorts; `id ASC` is
appended as a deterministic tiebreaker. Accounts category filters are explicit `category` parameters;
preferences supply defaults only when explicit list parameters are absent.

`DynamicAttributePredicates` is an extension point for non-static attributes (Rails custom fields,
`cf_*`): the Ransack parser consults registered beans — in order — for root-level attributes after the
static-column and association-traversal lookups, passing the predicate suffix string and the stripped
raw values. AB-271 registers the production custom-field predicate bean.

The Rails parity matrix `rake ffcrm:migration:search_matrix
OUTPUT=spring/src/test/resources/search/accounts_search_matrix.json` (PostgreSQL only, seeds a fixed
corpus in a rolled-back transaction, runs every case through `AccountsController#index`) feeds
`RailsSearchParityTest`, which replays the same corpus and cases against `/api/v1/accounts`.

## Custom-field JSONB backfill

If V3 fails, run `flyway repair` and rerun. The Java migration keeps valid expected GIN indexes and rebuilds invalid or wrong-definition indexes concurrently.

Writes normalize only supplied keys whose raw input differs from the current READ value. Omitted and unchanged values retain READ-mode semantics while still participating in required and date-pair validation.

Normal Spring startup applies the custom-field JSONB synchronization trigger and GIN indexes. Once
the trigger is live, run the restartable backfill with:

```bash
java -jar spring-api.jar --spring.profiles.active=backfill-custom-fields
```

The job processes ID ranges in independent transactions. Set
`ffcrm.custom-fields.backfill.batch-size` to tune the default 10,000-row batch. Re-running is safe:
already synchronized rows are skipped, and unresolved YAML markers are retried. The stdout and log
report lists, per table, `rows`, `rowsBackfilled`, `drift`, `markersRemaining`, and each physical
custom-field column's non-null count beside its JSONB-key non-null count. A zero `drift`,
`markersRemaining`, and count difference indicates a successful verification. A non-zero exit
indicates a failed report; investigate it with `CustomFieldConsistencyCheck` before cutover. The
read/write precedence and cutover sequence are documented in
`../docs/migration/spikes/custom-fields-dual-read-design.md` §7.

The registry caches field definitions and physical `cf_*` columns, and re-hashes their contents after
the configured TTL to detect metadata or schema changes.
