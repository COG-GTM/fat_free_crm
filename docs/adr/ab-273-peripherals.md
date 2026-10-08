# AB-273: Peripherals (exports, settings/i18n, jobs/mail)

- **Status**: Proposed
- **Ticket**: [AB-273](https://cog-gtm.atlassian.net/browse/AB-273) (epic AB-261)
- **ARB ticket**: TO BE CREATED

Each AB-273 track appends one `## Track:` section below.

## Track: exports

### Context

Rails exports every entity list as CSV and XLS through `respond_to` branches on the index actions
(`app/controllers/entities/accounts_controller.rb:17-18` and the campaigns/contacts/leads/opportunities
equivalents), tasks (`app/controllers/tasks_controller.rb:19-20`) and the activity dashboard
(`app/controllers/home_controller.rb:19`, routed as `/activities`). Spring must serve the same documents from
the AB-270 list endpoints so the gateway can move export traffic without changing what users download.

### Decision

1. **Negotiation without touching AB-270 controllers.** `com.fatfreecrm.api.ExportsController` maps the same
   list paths (`/api/v1/{accounts,campaigns,contacts,leads,opportunities,tasks,activities}`) with
   `produces = {text/csv, application/vnd.ms-excel, application/vnd.msexcel}`; Spring picks it only when the
   request asks for an export, otherwise the JSON read controller answers. `ExportContentNegotiationConfig`
   registers a `ParameterContentNegotiationStrategy` (`format=csv|xls`, registered extensions only, unknown
   values ignored) ahead of the `Accept` header strategy. `.csv` / `.xls` suffix paths mirror the Rails URLs.
2. **Same rows as Rails: full list.** `EntitiesController#get_list_of_records`
   (`app/controllers/entities_controller.rb:138-178`) applies `params[:query]` (139), text + tag parsing (141,
   154-155), Ransack `distinct: true` (146), the session state filter unless `q` is present (148-152), the
   user's `<controller>_sort_by` preference or model default unless `q` is present (157-161), and paginates
   only when the format is not XLS/CSV (165-173). `ExportService` reuses `CrmQueryService` (AB-268 access
   policy + AB-269 search/sort; the AB-270 list filter parameters `category` (accounts), `status` (campaigns, leads) and `stage` (opportunities) stand in for the Rails session filter) and pages
   through every result (page size 200) so the export is the whole accessible, filtered, sorted list.
   Tasks reuse `TaskReadService` buckets (view `pending|assigned|completed`), activities reuse
   `ActivitiesReadService` (same user/duration filters and authorization as the JSON activity list).
3. **CSV = `FatFreeCRM::ExportCSV`.** Columns are the physical table columns in ActiveRecord order, minus
   `/password|token/` (`lib/fat_free_crm/export_csv.rb:20`) and the AB-271 `custom_fields` column (Rails
   ignores it via `ignored_columns`), plus `tags` space-joined for taggable models (21, 26-27). Physical `cf_*`
   columns are included raw. Values use Ruby `to_s`: nil → empty, `true/false`, `YYYY-MM-DD HH:MM:SS UTC`
   timestamps, `BigDecimal#to_s` (`1000.5`, `0.0`), YAML arrays as Ruby `inspect`. Ruby CSV dialect: comma,
   quote only when needed, `""` escaping, `\n` line endings, no BOM, zero-byte body for no rows.
   `Content-Disposition: attachment; filename=<controller>.csv` (`lib/fat_free_crm/renderers.rb:8-12`).
4. **XLS = SpreadsheetML XML**, not binary (`app/views/layouts/header.xls.builder`,
   `app/views/*/index.xls.builder`), so no Apache POI. `SpreadsheetMlWriter` reproduces the builder output
   byte-for-byte: namespaces, `Worksheet`/`Table` wrappers even for empty lists, per-entity en-US headers
   (`ExportHeaders`), custom-field labels/values appended, `ss:Type="Number"` only for numeric values,
   worksheet `Dashboard` for activities. Content type `application/vnd.msexcel; charset=utf-8`
   (`config/initializers/mime_types.rb:6`), no `Content-Disposition` (Rails sends none).
5. **Never exported:** `custom_fields`, `password|token|salt` columns, and activity user secrets (the Rails
   `/activities.json` leak is not reproduced; activities export only the builder's columns).
6. **Atom/RSS/XML stay on Rails** (settled decision: deprecated pending production log confirmation). No Spring
   route produces them; `RailsExportGoldenTest` asserts `Accept: application/atom+xml|application/rss+xml|
   application/xml` never reaches `ExportsController`.

### Tests and gates

- `rake ffcrm:migration:export_goldens` (`lib/tasks/ffcrm/export_goldens.rake`) seeds a fixed corpus inside a
  rolled-back transaction on a frozen clock, drives the real Rails controllers (39 cases: 7 families × CSV/XLS,
  admin/alice/bob, query, tag, Ransack, session filter, pagination-ignored, empty, private records, custom
  fields) and writes `spring/src/test/resources/exports/`. `spec/lib/tasks/export_goldens_spec.rb` checks it is
  deterministic, leaves the DB unchanged and matches the committed files.
- `RailsExportGoldenTest` replays each case through Spring three ways (`Accept`, `format=`, suffix) comparing
  status, content type, `Content-Disposition` and body byte-for-byte; the only normalization is the activity
  timestamps (seeded relative to now).
- CI job `export-goldens` (appended to `.github/workflows/spring-api.yml`) regenerates the goldens and fails on
  drift, mirroring `search-matrix`.
- Contract cases `spring/src/contractTest/resources/contract/cases/ab-273-exports.yml` (14, `compare: text`).
- Gateway: disabled `Spring routing: exports` block matching only
  `^/api/v1/(accounts|campaigns|contacts|leads|opportunities|tasks|activities)\.(csv|xls)$`; `Accept`/`format=`
  exports on the bare list paths follow the per-family blocks.

### Foundation extensions

- Contract harness (authorized by the ticket): per-side `accept` (`ContractCase.SideRequest.accept`, sent by
  `ContractClient`, default still `application/json`) and case-level `compare: text` (`ContractCase.bodyCompare`,
  `ContractDiffer` exact body compare reporting `TEXT_BODY` at `/line/<n>`), unit-tested in
  `ContractTextBodyTest`. Existing cases are unaffected.
- New `ExportLookupRepository` (read-only JDBC lookups for tag names, user/campaign/lead/account names and
  addresses); no foundation class was modified. No new dependency.

### Deviations

- Atom/RSS/XML are not ported (see decision 6), contrary to the ticket text.
- Spring accepts the IANA-style `application/vnd.ms-excel` as well as Rails' registered
  `application/vnd.msexcel`, and always answers with the Rails content type.
- Unauthenticated/forbidden exports return RFC 9457 problem+json (existing `error-body-problem-json`
  allow-list entry), not Rails' `401 text/csv`.

### ARB triage

ARB triage: ARB_REQUIRED (manual review; the detector's T3 hits are `http://acme.test` fixture data and its T7
hits are contract-case paths, both false positives).
Triggers:
- T4 New sync contract: CSV/XLS representations on the `/api/v1` list endpoints (`ExportsController`).
- T6 Network boundary: disabled-by-default gateway block `exports` in `spring/gateway/nginx.conf`.
Not triggered: T1, T2, T3, T5, T7, T8, T9 (same service, data store and JWT boundary; no new dependency).
Required next steps: covered by this ADR; ARB ticket TO BE CREATED.
