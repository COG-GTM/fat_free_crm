# Contract-diff harness

The harness sends each case to Rails and Spring and compares response status, media type, and
JSON body. Pending cases report differences without failing; enforced cases must be clean.
Cases and fixture credentials live under `src/contractTest/resources/contract/`.

## Run locally

The default database command creates or reuses a PostgreSQL 16 container named
`ffcrm-contract-db` on `127.0.0.1:5433`; it intentionally leaves the container running:

```sh
PG_IMAGE=mirror.gcr.io/library/postgres:16 spring/scripts/contract-db.sh
```

For re-running against an existing container (optional):

```sh
CONTRACT_FIXTURES_RESET=1 spring/scripts/contract-db.sh
```

The script reuses the container; after the first fixture load, the database is populated and the
guard refuses to overwrite it. Use the reset command only when a deliberate wipe and reload is
intended.

The script prints the connection exports. Export those values in the shell where Spring will run,
then start Rails and Spring in separate terminals:

```sh
export DATABASE_URL=postgres://postgres:postgres@127.0.0.1:5433/ffcrm_contract
bin/rails server -p 3000 -b 127.0.0.1
```

```sh
export FFCRM_DB_URL=jdbc:postgresql://127.0.0.1:5433/ffcrm_contract
export FFCRM_DB_USER=postgres
export FFCRM_DB_PASSWORD=postgres
export FFCRM_JWT_SECRET=ab266-contract-ci-jwt-secret-0123456789abcdef
cd spring
./gradlew bootRun
```

Run the cases in another terminal:

```sh
cd spring
./gradlew contractTest
```

Reports are written to `spring/build/reports/contract-diff/report.json` and
`spring/build/reports/contract-diff/report.md`; the Markdown summary is also printed to stdout.
Override endpoints with `CONTRACT_RAILS_URL` and `CONTRACT_SPRING_URL`, select case IDs with
`CONTRACT_CASE_FILTER` (a Java regular expression), and choose another report directory with
`CONTRACT_REPORT_DIR`.

To use an existing PostgreSQL database instead of Docker, set `CONTRACT_DATABASE_URL` to its
PostgreSQL URL. For both Docker and URL targets, the script checks for an existing `users` table
after `db:create` and refuses to load the schema if one exists. Set `CONTRACT_FIXTURES_RESET=1`
only when it is safe to wipe the target; the script disconnects its active sessions and drops and
recreates the database before loading `db/schema.rb` and the fixed fixture corpus. CI uses a fresh
PostgreSQL service database and does not need the reset variable.

## Case YAML

Each YAML file in `contract/cases/` contains a list of cases:

```yaml
- id: accounts-index-admin
  ticket: AB-270
  status: enforced
  method: GET
  path: /accounts
  spring: {bodyPointer: /items}
  auth: admin
  description: Compare the Rails and Spring account indexes.
```

`path` is the logical path used by the allow-list. If only `path` is specified, the request paths
are Rails `<path>.json` and Spring `/api/v1<path>`. Explicit `rails` or `spring` entries may be a
path string or `{path: /path, target: rails|spring}`; target defaults to the corresponding side.
`params` is a query map, `body` is the JSON object sent for a non-GET request, and `auth` is
`anonymous` or a key from `contract/users.yml`. Optional `expect` checks `status` and exact JSON
pointer values independently on each side after the normal diff; expectation differences are
always open and cannot be allow-listed.

An object-form side request may set `bodyPointer`:

```yaml
spring:
  bodyPointer: /items
```

For a 2xx JSON response, the pointer-selected value is compared instead of the full response
tree. This supports comparing Rails' bare index array with Spring's list envelope. A missing
target is an open, non-allow-listable `INVALID_JSON` difference at the requested pointer.
Non-2xx responses compare their full bodies even when a pointer is configured. Expectations
always evaluate against each complete response body. Pointer application or bypass is noted in
the case report.

Rails keeps the list `page` and `query` in the cookie session (`current_page`/`current_query`),
and each fixture user shares one Rails session for the whole run. Rails index cases therefore pin
`page` and `query` explicitly (`query: ""` clears a query left by an earlier case) so a case's
result does not depend on run order. Spring has no list session state: absent parameters always
mean page 1 and no query.

```yaml
expect:
  status: 200
  json: {/id: 1, /username: admin, /admin: true}
```

The current initial case set is:

| Case | Mode | Request | Purpose |
|---|---|---|---|
| `accounts-index-self-check-admin` | enforced | Rails `GET /accounts.json` on both sides | Harness and Rails-session self-check; expected 200 |
| `auth-login-spring-self-check` | enforced | Spring `GET /api/v1/users/me` on both sides | AB-264 fixture JWT login and current-user identity self-check; expected admin id 1, username `admin`, and `admin: true` |
| `accounts-index-admin` | enforced | Rails `GET /accounts.json`; Spring `GET /api/v1/accounts` (`bodyPointer: /items`) | AB-270 account index |
| `accounts-show-public` | enforced | Rails `GET /accounts/101.json`; Spring `GET /api/v1/accounts/101` | AB-270 public account |
| `contacts-index-alice` | pending | Rails `GET /contacts.json`; Spring `GET /api/v1/contacts` | AB-270 contacts index |
| `accounts-index-anonymous` | enforced | Rails `GET /accounts.json`; Spring `GET /api/v1/accounts` (`bodyPointer: /items` on 2xx only) | Anonymous authorization and error-body self-check |
| `accounts-show-private-denied-bob` | enforced | Rails `GET /accounts/102.json`; Spring `GET /api/v1/accounts/102` | Rails 401 versus Spring 403 authorization behavior |

`ab-269-accounts-search.yml` contains enforced Accounts index comparisons; each Spring request
selects `/items` to compare account rows. `ab-270-accounts-read.yml` adds Accounts list, show,
autocomplete, and Spring-vs-Spring envelope assertions; all 18 cases are enforced. Its envelope
cases pin totals and configured category facets to the captured Rails corpus because Rails returns
a bare array. The expected authorization and error-body distinctions remain covered only by the
existing narrowly scoped allow-list entries.

## Normalization and allow-list

`contract/config.yml` supplies global `normalize` defaults; per-case options are merged with them.
Keep global defaults narrow. JSON pointers support `*` for one array index or object key:

```yaml
normalize:
  ignore: ["/*/updated_at"]
  unorderedArrays: ["", {pointer: "/*/tags", key: "/id"}]
  timestamps: ["/*/created_at"]
  rename:
    - {side: spring, from: "/*/createdAt", to: "/*/created_at"}
```

Ignore pointers remove subtrees from the received response structure after renames and before
unordered arrays are sorted. Missing-key diagnostics are collected after sorting and use sorted
array pointers; ignore matches for an element's key pointer are captured before sorting.

Arrays remain ordered unless listed under `unorderedArrays`. A string pointer sorts by canonical
JSON; an object with `key` pairs entries by that key pointer. Timestamp pointers compare as
ISO-8601 instants. Renames happen only for the declared side and pointer.

Allow-list entries in `contract/allowlist.yml` match HTTP method, a path glob (`*` matches one
segment and `**` matches any path), and optionally a case ID or `match.authenticated` value. The
authenticated matcher is true only for fixture-user cases whose Spring-side authentication
succeeded; unavailable Spring auth counts as unauthenticated. If omitted, the entry matches both
authenticated and unauthenticated requests:

* `status` allows only the declared Rails and Spring status pair.
* `pointer` supports `ignore` and `equalsAfter` with `instant`, `trim`, `lowercase`, `number`, or
  `string` transforms.
* `errorBody` handles a Spring RFC 9457 `application/problem+json` response against a Rails error
  format. When both statuses are errors and the Spring body parses, it allows content-type and body
  differences, including Rails-side `INVALID_JSON`. It never allows a malformed Spring body, and
  still requires a non-empty `title` and matching `status`.

Each applied allow-list entry is counted. Zero-hit entries are marked **stale** in the report but
do not fail the run. The enforced `accounts-show-private-denied-bob` case exercises the
`authz-denied-401-vs-403` status entry. The `error-body-problem-json` entry handles Rails error
formats against Spring RFC 9457 responses.

## Authentication and case lifecycle

`RailsSessionAuth` performs the Devise CSRF form sign-in using an isolated cookie jar per fixture
user. `SpringJwtAuth` posts fixture credentials to `/api/v1/auth/login` and uses the returned
bearer token. If Spring login returns 401 or 404, the case still sends its request without
credentials and records `spring auth unavailable`; an enforced case then fails. An unreachable
Rails sign-in page fails the run clearly.

`auth-login-spring-self-check` sends both compared requests to Spring with the same cached admin
JWT and reads `/api/v1/users/me`; its response contains only stable identity and admin fields, so
no per-case normalization ignores are needed.

Cases begin as `pending` while their Spring endpoint or behavior is still in progress. Pending
cases always pass the Gradle task but retain their live diff in the report. Later phases flip cases
to `enforced` when the endpoint lands and its intended behavior is stable.

## Fixture corpus and visibility matrix

The user fixture corpus is defined once in `contract/users.yml`; the Rails loader reads that same
file.

| ID | Username | Role | Groups | Admin | Suspended |
|---:|---|---|---|---|---|
| 1 | admin | Administrator | — | Yes | No |
| 2 | alice | Regular user | Sales | No | No |
| 3 | bob | Regular user | Support | No | No |
| 4 | sam | Regular user | Sales | No | Yes |
| 5 | carol | Regular user; no explicit permissions | — | No | No |

User IDs 6–9 are reserved for later tickets. Other IDs use stable ranges: groups 1–2, accounts
101–107, contacts 201–207, leads 301–307, opportunities 401–407, campaigns 501–507, and tasks
601–605. For each entity type, IDs 1–7 in its range share the access patterns below:

| Suffix | Owner | Assignee | Access | Share |
|---:|---|---|---|---|
| 1 | Alice | — | Public | — |
| 2 | Alice | — | Private | — |
| 3 | Alice | — | Shared | Bob user permission |
| 4 | Alice | — | Shared | Support group permission |
| 5 | Alice | Bob | Private | — |
| 6 | Bob | — | Private | — |
| 7 | Admin | — | Private | — |

Alice and Sam are in Sales (group 1); Bob is in Support (group 2). Sam is suspended. Carol has no
group memberships or explicit permissions. The corpus also links account 101 to contact 201 and
opportunity 401, links contact 201 to opportunity 401, links lead 301 to campaign 501 and contact
201, adds comments to account 101 and shared contact 203, tags account 101 and contact 201, and
gives account 101 a billing address. Tasks 601 and 604 are Alice-owned; 602 is Alice-owned and
assigned to Bob; 603 is owned by Carol and completed by Bob; 604 is attached to account 101; 605 is
private to the admin.

| Resource | Alice | Bob | Admin | Carol |
|---|---|---|---|---|
| Accounts | 101–105 | 101, 103–106 | 101–107 | 101 |
| Contacts | 201–205 | 201, 203–206 | 201–207 | 201 |
| Leads | 301–305 | 301, 303–306 | 301–307 | 301 |
| Opportunities | 401–405 | 401, 403–406 | 401–407 | 401 |
| Campaigns | 501–505 | 501, 503–506 | 501–507 | 501 |
| Tasks | 601, 602, 604 | 602, 603 | 601–605 | 603 |

The visibility matrix is asserted by `spec/db/contract_fixtures_spec.rb`; it is the fixture contract
that AB-268 relies on. Entity index fixtures stay below Rails' default 20-record page size.
