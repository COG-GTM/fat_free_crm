# AB-270. Rails-compatible read API foundation and Accounts read cutover path

- **Status:** Proposed
- **Date:** 2026-10-07
- **ARB ticket:** TO BE CREATED
- **Authors:** Devin (AB-270 phase A)
- **Owning team:** TBD — owner to confirm before ARB (Fat Free CRM migration team, epic AB-261)
- **Related ADRs:** `0001-spring-boot-api-service-skeleton.md`, `ab-264-jwt-authentication.md`, `ab-268-authorization-specifications.md`; `docs/migration/target-architecture.md` §2.5/§2.6 and "AJAX→JSON mappings"; Jira AB-261 / AB-270

## Context

AB-270 starts traffic shifting in the strangler migration. Every CRM family needs Spring
`index` / `show` / `autocomplete` reads that return the same rows and values as Rails
`respond_with(...).to_json`, against the shared PostgreSQL database, before the gateway can route
the family's GETs to Spring. Phase A builds the shared foundation and the Accounts family; phase B
families (campaigns, contacts+leads, opportunities, tasks/comments/emails/lists,
users/me+home+admin+metadata) reuse it.

Constraints: the Rails schema is shared and frozen (Flyway V1, `ddl-auto: validate`); runtime
`cf_*` custom-field columns exist outside the JPA mapping until AB-271; Rails reads have a write
side effect (a PaperTrail `view` row that the Rails UI's recently-viewed sidebar reads); Rails list
state (category filter, sort/per-page preferences, page/query) lives in the cookie session.

ARB triggers (manual review of the diff): **T4** (new sync API contract: `/api/v1/accounts` list
envelope, show and autocomplete become the supported read contract for API clients) and **T6**
(network boundary: new named gateway routing blocks that move Accounts GETs from Rails to Spring when
enabled; they ship disabled). The heuristic detector reported only T7 on
`cases/ab-270-accounts-read.yml` (`rails: {path: /accounts/auto_complete.json}`); that is a false
positive — a contract-test case naming the existing Rails endpoint, no framework introduced.
Not triggered: T1, T2, T3, T5, T7, T8, T9.

## Decision

We will serialize Spring read responses from the database row rather than from hand-written DTOs:
`RailsRowRepository` reads `SELECT *` by id for a descriptor-whitelisted table and `RailsJsonWriter`
maps each column by its PostgreSQL type to the Rails `as_json` form (numeric → string, timestamps →
UTC ISO-8601 milliseconds, YAML arrays → JSON arrays, `tag_list` appended, password/token/salt
columns never emitted). Lists keep going through `CrmQueryService` (AB-268 `accessibleBy` + AB-269
search); `CrmReadService` adds show (authorized by `CrmPermissionEvaluator`) with the Rails `view`
version written in its own `REQUIRES_NEW` transaction whose failure never fails the read, and
autocomplete. Rails session state becomes explicit, stateless query parameters (`category`,
`page`, `query`, `sort_by`, `per_page`) with the Rails user preferences as defaults. Each family gets
a disabled-by-default nginx routing block toggled by `spring/gateway/routing.sh`; a block is enabled
only after its enforced contract cases are diff-clean.

## Alternatives considered

| Alternative | Pros | Cons | Why rejected |
| --- | --- | --- | --- |
| Do nothing (keep AB-269's throwaway list endpoint) | No work | No show/autocomplete; no cutover path | Ticket scope |
| Hand-written DTO per entity (AB-269 `AccountResponse` style) | Compile-time types | Field lists drift from the schema; misses runtime `cf_*` columns; repeated for ~10 entities | Ticket asks for metadata-driven serialization |
| Serialize JPA entities via Hibernate metamodel | Single read path | `cf_*` columns are not mapped; association/FK and YAML handling still need per-type rules | Same completeness gap as DTOs |
| Skip the `view` version write in Spring | Pure reads | Rails recently-viewed sidebar silently stops updating after cutover | Settled decision 4 requires parity |
| Route Rails `/accounts.json` itself to Spring | Clients unchanged | Spring envelope/error contract differs from Rails' bare arrays and text errors | Gateway routes only `/api/v1/*` paths |

## Architecture

```mermaid
C4Container
    title Accounts read API and gateway cutover (AB-270)
    Person(user, "API client")
    System_Boundary(b, "Fat Free CRM") {
        Container(gw, "nginx gateway", "nginx 1.27", "Path routing; per-family Spring blocks disabled by default (routing.sh)")
        Container(api, "Spring API", "Spring Boot 3.5 / Java 21", "AccountsController -> CrmReadService -> CrmQueryService / RailsJsonWriter / RecentlyViewedService")
        Container(rails, "Rails app", "Rails 8", "UI, /accounts.json, recently-viewed sidebar")
        ContainerDb(db, "PostgreSQL", "PostgreSQL 16", "accounts, taggings/tags, fields/field_groups, preferences, versions")
    }
    Rel(user, gw, "HTTPS / Bearer JWT (API) or cookie session (Rails)")
    Rel(gw, api, "HTTP GET /api/v1/accounts[/{id}|/autocomplete] when the accounts block is enabled")
    Rel(gw, rails, "HTTP (all other paths and methods)")
    Rel(api, db, "JDBC; SELECT (rows, tags, field metadata, preferences); INSERT versions (event=view)")
    Rel(rails, db, "ActiveRecord; same tables; reads versions for recently-viewed")
```

## Non-functional requirements

| NFR | Target | How met |
| --- | --- | --- |
| Availability SLO | Inherits the Spring API SLO (TBD — owner to confirm before ARB) | No new component |
| p95 latency | TBD — owner to confirm before ARB | Per list page: the AB-269 page + count queries, one `SELECT * ... WHERE id IN (...)`, one batched taggings query, facet counts; show adds one INSERT |
| RPO / RTO | N/A — no new data store | `versions` rows are the same rows Rails writes |
| Peak load | TBD — owner to confirm before ARB | Same order of queries as the Rails controller |
| Scaling model | Stateless; scales with the API pods | Session state replaced by query parameters |
| Data retention | Unchanged | `versions` growth from views equals Rails today |

## Security & compliance

- **Data classification:** CRM business data (accounts). No new data is stored; the `view` version
  row is the one Rails already writes.
- **Encryption at rest / in transit:** Unchanged.
- **AuthN / AuthZ:** JWT (AB-264). Lists filtered SQL-side by `accessibleBy` (AB-268); show via
  `hasPermission(#id, 'Account', 'read')` (401/404/403); autocomplete filtered by `accessibleBy`.
  The 401→403 delta for out-of-scope records is the existing allow-listed difference.
- **Injection:** the serializer's table name comes only from a code descriptor validated against
  `[a-z][a-z0-9_]*`; ids are bound parameters.
- **Sensitive columns:** a global name filter drops any `*password*`, `*token*`, `*salt*` column in
  addition to per-resource exclusions (prepares the User family).
- **Secrets:** None added.
- **Audit logging:** `view` events are recorded exactly as in Rails (`whodunnit` = user id).
- **Policy sections satisfied:** No infrastructure resources; `policy/approved-infra.yaml` not affected. No new dependencies.

## Cost

| Item | Assumption | Monthly estimate |
| --- | --- | --- |
| Compute / storage | In-process; no new resources | $0 |
| **Total** | | $0 |

## Operations

- **On-call rotation:** TBD — owner to confirm before ARB.
- **Runbook:** `spring/README.md` § Accounts read API and § Adding a read family; `spring/contract-diff.md`.
- **Cutover:** `spring/gateway/routing.sh enable upstream accounts` then `nginx -t && nginx -s reload`, only after the Accounts enforced contract cases are clean.
- **Rollback plan:** `routing.sh disable accounts` (re-comments the block) and reload nginx. No data migration.
- **Dashboards / alarms:** Existing API 4xx/5xx metrics; WARN log `Unable to record recently viewed` signals view-recording failures.

## Policy exceptions requested

| Rule | Resource | Justification | Compensating control | Expiry |
| --- | --- | --- | --- | --- |
| none | | | | |

## Consequences

- Positive: one serializer and read service for every family; new columns (including `cf_*`) appear
  automatically; per-family cutover and rollback are a config toggle.
- Negative / risks: list reads load each page twice (JPA ids, then JDBC rows); the JSON shape follows
  the schema, so a schema change is an API change (same as Rails today).
- Follow-ups: phase B families; AB-271 replaces physical `cf_*` columns with JSONB (the writer's
  `json/jsonb` mapping and check-box metadata lookup are the seams); User `to_json` override in the
  users family.

## Open questions

- Ownership, SLO and latency targets (TBD above).
- Whether API clients need persisted list preferences writes (Rails `redraw` writes them; Spring only reads them).

## Family: campaigns

- **Routed surface:** `GET /api/v1/campaigns` (list + `status` facet envelope),
  `GET /api/v1/campaigns/{id}` (show + `view` version row), and
  `GET /api/v1/campaigns/autocomplete` (`related` supports bare ids and `users/<id>`).
- **Gateway block:** `Spring routing: campaigns` markers in `spring/gateway/nginx.conf`;
  `routing.sh enable upstream campaigns` activates it independently of accounts.
- **Parity evidence:** `spring/src/test/resources/search/campaigns_search_matrix.json`
  (45 recorded cases replayed by `RailsCampaignSearchParityTest`),
  `CampaignsControllerIntegrationTest`, and `ab-270-campaigns-read.yml` (21 enforced
  contract cases, no allow-list entries).
- **Deviations:** none vs the Accounts foundation. Recorded Rails quirk documented in the
  matrix: `POST /campaigns/filter` returns 500 when the filtered result contains a
  NULL-status campaign (`campaigns/_status.html.haml` calls `status.to_sym`), so the
  `other` session filter never persists in real Rails; those cases carry `post_status`
  and are replayed unfiltered. Facet key order is `all, other, <statuses>` (sidebar
  build order), unlike accounts' `<categories>, all, other`.

## Family: contacts-leads

The contacts/leads family reuses the Phase A read foundations and adds
`ContactsController` and `LeadsController`, family descriptors and list metadata.
The surface is `GET /api/v1/{contacts,leads}`, numeric show, `/autocomplete`, and
numeric `.vcf` show paths. Contacts have no sidebar facets or state filter.
Leads expose the ordered `lead_status` facet counts plus `all` and `other`;
the `status` filter includes NULL for `other` and is skipped when advanced
`q[...]` parameters are present. Rails omits `per_page` when it calls
`get_leads`, therefore Spring strips explicit `per_page` and still applies
`leads_per_page` / `leads_sort_by` preferences.

Autocomplete remains scoped to `accessibleBy`, returns at most ten full names,
and supports the Rails related exclusions: contacts (`accounts`,
`opportunities`, `users`) and leads (`campaigns`, `users`), with existence
checks before looking up related rows. Each family records show and `.vcf`
reads as Rails-style `view` versions.

The `.vcf` endpoints use a handwritten `com.fatfreecrm.service.vcard.VCardWriter`
instead of the target architecture's ez-vcard suggestion. No dependency is
added: byte parity requires preserving vcardigan 0.0.9's insertion order, raw
values, Ruby-style `TYPE=` rendering, LF/trailing-LF behavior, and its exact
75/74-codepoint folding quirk.

Ground truth is generated by PostgreSQL-only Rails tasks in rollback-only
transactions: `ffcrm:migration:search_matrix` appends contacts and leads
matrices to the existing Accounts output, and `ffcrm:migration:vcard_matrix`
captures actual Rails `.vcf` status, headers, and body bytes. Spring test
replayers assert list IDs/counts/lead facets and exact vCard responses.
Disabled nginx blocks route only GET/HEAD requests matching
`^/api/v1/contacts(/[0-9]+(\.vcf)?|/autocomplete)?$` and
`^/api/v1/leads(/[0-9]+(\.vcf)?|/autocomplete)?$` when enabled.

## Family: tasks

- **Surface**: `GET /api/v1/tasks` (`{buckets}`, `view`, `timeZone`), `GET /api/v1/tasks/{id}`,
  `GET /api/v1/tasks/autocomplete`, `GET /api/v1/comments?<parent>_id=`; disabled gateway blocks `tasks` and
  `comments`. No email or saved-list reads exist in Rails, so none are added. Details: `spring/README.md`
  §"Tasks and comments read API".
- **ARB triage**: ARB_REQUIRED — T4 (new synchronous API contracts) and T6 (gateway path routing, disabled by
  default), same component, data store and auth boundary as the accounts family; no new dependency, data store,
  vendor or infrastructure (detector's T7 hit on `allowlist.yml` is a false positive). Covered by this ADR's ARB
  ticket (TO BE CREATED).
- **Decisions**:
  1. Bucket queries reproduce Rails `Task.my` / `Task.assigned_by` scopes and do not compose the broader AB-268 Task
     policy (owner, assignee, completed_by): composing it would be a no-op for `assigned_by` and would not widen
     `my`, and Rails does not widen buckets for admins.
  2. Bucket boundaries use an explicit `timeZone` parameter (default `ffcrm.time-zone` = UTC) because the Rails zone
     is session state (`/home/timezone`). Clients that want Rails behaviour send the browser zone.
  3. Show keeps the AB-268 403 for rows outside the policy (allow-list entry `tasks-show-tracked-by-404-vs-403`) and
     returns 404 for rows inside the policy but outside `tracked_by` (completed_by only), matching Rails.
- **Observed Rails behaviour**: `Time.zone` is thread-local and `set_context` only assigns it when the session has
  an offset, so a Puma thread keeps the previous request's zone for a session without one. The matrix resets
  `Time.zone` per case; Spring is stateless and unaffected.

## Family: opportunities

AB-270 Phase B adds `/api/v1/opportunities` list, numeric show, and autocomplete reads through
the existing `CrmReadService`, `RailsResources`, and `SearchableEntities` registrations. The
disabled-by-default nginx opportunities blocks route only GET/HEAD list, numeric show, and
autocomplete paths; `gateway/routing.sh enable upstream opportunities` enables the upstream
and family blocks.

Opportunity stage facets remain access-scoped and independent of list filtering, ordered as
`all`, `other`, then configured stages. Facet `other` includes NULL and unconfigured stage
values; the explicit `stage=other` filter matches only NULL, matching Rails' `Opportunity.state`
behavior. Explicit stage filtering is bypassed for advanced `q[...]` searches. User preference
defaults are `opportunities_per_page` and `opportunities_sort_by`, including Rails' weighted
`amount*probability DESC` selector.

Autocomplete related exclusions support bare opportunity IDs and existing users, accounts,
contacts, and campaigns; Leads intentionally do not exclude opportunities. The live contract
run observed Rails' PostgreSQL-generated `?column?` alias on list rows; the contract allow-list
ignores only `/*/?column?` on GET `/opportunities`. Show and autocomplete responses are unaffected.

Deviation: Rails 8 500s on the weighted-sort preference; Spring sorts by the weighted product.
The Rails 500 is `ActiveRecord::UnknownAttributeReference` for
`opportunities.amount*probability DESC`; Spring safely resolves it through the sort whitelist.
`Opportunity.weighted_sort` now projects the table's qualified non-ignored columns instead of
`*`; that preserves AB-271's guarantee that Rails JSON does not expose `custom_fields`, and it
fixed the tagged-list defect where `SELECT DISTINCT *` let the tagging `id` overwrite the
opportunity `id`, so tag search cases assert exact parity. The parity replay names the
remaining weighted-sort Rails defect explicitly and asserts Spring's correct behavior, while
keeping strict parity for all other cases.
