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
  status: pending
  method: GET
  path: /accounts
  params: {page: 1}
  auth: admin
  description: Compare the Rails and Spring account indexes.
```

`path` is the logical path used by the allow-list. If only `path` is specified, the request paths
are Rails `<path>.json` and Spring `/api/v1<path>`. Explicit `rails` or `spring` entries may be a
path string or `{path: /path, target: rails|spring}`; target defaults to the corresponding side.
`params` is a query map, `body` is the JSON object sent for a non-GET request, and `auth` is
`anonymous` or a key from `contract/users.yml`.

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

Ignore pointers are evaluated against the response structure after renames and before unordered
arrays are sorted.

Arrays remain ordered unless listed under `unorderedArrays`. A string pointer sorts by canonical
JSON; an object with `key` pairs entries by that key pointer. Timestamp pointers compare as
ISO-8601 instants. Renames happen only for the declared side and pointer.

Allow-list entries in `contract/allowlist.yml` match HTTP method, a path glob (`*` matches one
segment and `**` matches any path), and optionally a case ID:

* `status` allows only the declared Rails and Spring status pair.
* `pointer` supports `ignore` and `equalsAfter` with `instant`, `trim`, `lowercase`, `number`, or
  `string` transforms.
* `errorBody` handles a Spring RFC 9457 `application/problem+json` response against a Rails error
  format. When both statuses are errors and the Spring body parses, it allows content-type and body
  differences, including Rails-side `INVALID_JSON`. It never allows a malformed Spring body, and
  still requires a non-empty `title` and matching `status`.

Each applied allow-list entry is counted. Zero-hit entries are marked **stale** in the report but
do not fail the run. The `authz-denied-401-vs-403` status entry is currently stale because the
authenticated account-show request reaches an unimplemented Spring route and gets 404, not the
expected 403.

## Authentication and case lifecycle

`RailsSessionAuth` performs the Devise CSRF form sign-in using an isolated cookie jar per fixture
user. `SpringJwtAuth` posts fixture credentials to `/api/v1/auth/login` and uses the returned
bearer token. In the current live run, login succeeded for admin, Alice, and Bob, so no
`spring auth unavailable` notes were recorded. The account and contact resource endpoints are not
implemented yet; authenticated requests to them return Spring 404 `application/problem+json`,
while Rails returns 200 for the pending account/contact reads and 401 for Bob's private-account
denial. If Spring login returns 401 or 404, the case still sends its request without credentials
and records `spring auth unavailable`; this is report-only for pending cases but an enforced case
fails. An unreachable Rails sign-in page fails the run clearly.

Cases begin as `pending` while their Spring endpoint or behavior is still in progress. Pending
cases always pass the Gradle task but retain their live diff in the report. Later phases flip cases
to `enforced` when the endpoint lands and its intended behavior is stable.

`accounts-show-private-denied-bob` currently reports **DIFF**: Bob's JWT login succeeds, but the
missing Spring account-show endpoint returns 404 while Rails denies the private account with 401.
The `errorBody` rule allows the content-type and body differences; the 401-vs-404 status difference
remains open because the authorization rule expects Spring 403. Keep this case pending until the
Spring endpoint can exercise the intended authorization behavior.

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
