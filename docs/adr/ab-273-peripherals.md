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
## Track: jobs-mail


### Context

The Rails application owns asynchronous mail delivery, periodic background
jobs, account enrichment, and IMAP polling. Moving the API runtime to Spring
without an explicit owner boundary could send duplicate mail, ingest the same
message twice, or run two enrichment pipelines. Rails behavior was inventoried
in `app/models/observers/entity_observer.rb:524-549`,
`app/models/polymorphic/comment.rb:24-71`,
`app/models/entities/account.rb:86-87`,
`app/mailers/{user_mailer,subscription_mailer,dropbox_mailer,devise_mailer}.rb`,
`app/views/{user,subscription,dropbox}_mailer/`,
`lib/fat_free_crm/mail_processor/{base,dropbox,comment_replies}.rb`,
`app/jobs/account_website_job.rb`,
`app/services/wikidata_service.rb`, and `db/schema.rb` (Solid Queue tables).

### Decision

Add the Spring jobs/mail/IMAP track behind `FFCRM_JOBS_OWNER`, defaulting to
`rails`; only `spring` ownership starts Quartz, connects to IMAP, schedules
one-off work, or delivers mail. Quartz recurring jobs use RAMJobStore and
PostgreSQL session advisory locks (`classid=1179009869`, `objid=1` Dropbox,
`2` comment replies, `3` Solid Queue drain). The Solid Queue bridge claims
supported work from the existing Rails tables in bounded batches; unknown job
classes are left ready. One-off jobs contain primitive IDs or serialized mail
fields only. The website and Wikidata clients use JDK `HttpClient`, Jsoup, and
Jackson with no automatic redirects, bounded timeouts and response size, and
optional private-address blocking disabled by default.

Mail template rendering is isolated from the Spring MVC Thymeleaf view
configuration. Mail settings resolve database-backed Rails values before the
Spring YAML defaults. IMAP drops attachments, follows Rails message validity,
sender and archive/discard rules, and records comment create versions. The
reply parser ports `email_reply_parser_ffcrm` 0.5.0; its upstream MIT license
and fixture emails are shipped with the JUnit goldens. Rails generates the
sorted JSON parity fixtures through `rake ffcrm:migration:mail_golden`.

### Alternatives

- Keep Rails as the sole owner permanently: safest initial deployment, but
  leaves background work outside the Spring migration.
- Use a persistent Quartz store: increases schema and recovery coupling; the
  agreed design uses RAMJobStore and documents one-off restart loss.
- Share IMAP folders or rely only on scheduler timing: cannot guarantee
  single-owner processing; PostgreSQL session locks are used for recurring
  polls.

### Dependencies

Spring Boot Mail, Thymeleaf, Quartz, Jsoup 1.21.x, and test-only GreenMail
2.1.x; PostgreSQL is the shared lock and Solid Queue store. Java `HttpClient`
and Jackson are already available. Resolved dependency versions are recorded
with the implementation verification report.

### NFR and security

No new endpoint or database is introduced. Jobs use bounded HTTP request sizes
and timeouts, no redirects, and an opt-in private-address block. SMTP and IMAP
credentials remain in the existing Rails settings/configuration sources and
must be supplied through deployment secrets; never place credentials in
goldens or logs. The owner switch and advisory locks prevent concurrent
side-effects. One-off RAMJobStore work is intentionally at-most-process-life.

### Operations and rollback

1. Deploy with `FFCRM_JOBS_OWNER=rails`; confirm Spring has no Quartz polling
   and Rails remains the only producer/consumer.
2. Configure Spring SMTP/IMAP settings, schedules, and external service access.
3. Stop Rails job/IMAP workers, change the Spring deployment to
   `FFCRM_JOBS_OWNER=spring`, and verify scheduler logs and mail inbox health.
4. Roll back by setting `FFCRM_JOBS_OWNER=rails` and restarting Rails workers.
   The Spring owner gate then prevents scheduled side effects.
5. Check failed Solid Queue rows and rerun any one-off work lost across a
   Spring restart; recurring schedules are registered on startup.

The Rails IMAP constructor at
`lib/fat_free_crm/mail_processor/base.rb:71` uses the deprecated positional
`Net::IMAP.new(host, port, ssl)` form; installed `net-imap` 0.6.3 still accepts
it. Its shared permission check uses `Permission.exists`; the Spring
implementation uses the existing permission repository. The en-US Rails mail locale is
`config/locales/fat_free_crm.en-US.yml`; Devise strings come from the Devise
gem because no `config/locales/devise.en-US.yml` exists.

### Operations addendum

#### Context and decision

Rails ownership remains the default. Spring can take ownership through
`FFCRM_JOBS_OWNER=spring` only after the Rails cron and Solid Queue workers are
stopped. The inventory is `UserMailer`
(`app/mailers/user_mailer.rb:9-23`, trigger `app/models/observers/entity_observer.rb:9-28`),
subscription mail (`app/mailers/subscription_mailer.rb:9-35`,
`app/models/polymorphic/comment.rb:34-71`), Dropbox rendering
(`app/mailers/dropbox_mailer.rb:9-19`), the website/Wikidata jobs and the three
IMAP processor files under `lib/fat_free_crm/mail_processor/`.

Quartz is used for scheduling rather than JobRunr to keep the already selected
Spring scheduler integration small. Quartz RAMJobStore is selected over JDBC
JobStore because this bridge's scheduled work is restart-loss tolerant and
should not introduce another durable scheduler schema. A single owner gate is
preferred to dual-running Rails and Spring, which could duplicate mail, IMAP
processing and enrichment. Spring drains supported Solid Queue rows rather
than leaving the Rails worker active in parallel; unsupported classes remain
ready for Rails.

Quartz, Spring Mail, Thymeleaf, Angus Jakarta Mail, Jsoup, GreenMail and the
vendored reply parser are the selected components. Gradle resolves Spring Boot
3.5.16, Thymeleaf 3.1.5.RELEASE, Angus Jakarta Mail 2.0.5, Jsoup 1.21.2 and
GreenMail 2.1.3; `email_reply_parser_ffcrm` is vendored at 0.5.0 (MIT).

Cutover: stop Rails Dropbox/comment-reply cron; stop Solid Queue workers and
dispatcher and verify no claimed executions remain; set `FFCRM_JOBS_OWNER=spring`;
restart Spring; verify scheduler startup, advisory-lock acquisition and
queue-drain logs. Rollback sets the owner to `rails`, restarts Spring with
scheduling disabled, drains pending RAMJobStore one-offs, restarts Rails queue
workers and re-enables cron.

Deviations: MailText is en-US only pending settings-i18n; Devise token
generation remains Rails-owned; update/touch versions await the audit track;
attachments are ignored; and SSRF private-address blocking is disabled by
default for parity. Dropbox notification rendering remains available, but its
Rails caller is dead and Spring does not send it. SMTP/IMAP credentials remain
in existing Rails settings and are never emitted in logs or goldens; response
bodies are bounded and redirects are not followed.

### Architecture review (jobs-mail)

ARB triage: ARB_REQUIRED
Detector (heuristic, `detect_arb_triggers.py --base origin/devin/ab-273-peripherals`): ARB_LIGHT — T3 new outbound HTTP hosts (query.wikidata.org; social-URL prefixes; test/fixture URLs are false positives).
Manual review against the trigger table raises it to ARB_REQUIRED:
- New external integrations: outbound SMTP (JavaMailSender), IMAP polling (Jakarta Mail) of the Dropbox and comment-reply mailboxes, outbound HTTP to arbitrary account websites and to query.wikidata.org.
- New runtime dependencies/frameworks: spring-boot-starter-mail (Jakarta Mail/Angus), spring-boot-starter-thymeleaf, spring-boot-starter-quartz (Quartz 2.5.2, RAMJobStore), org.jsoup:jsoup 1.21.2; test-only com.icegreen:greenmail-junit5 2.1.3.
- Cross-system data integration: Spring claims and completes rows in Rails' Solid Queue tables (solid_queue_jobs / ready_executions / failed_executions) and takes PostgreSQL advisory locks (classid 1179009869) on the shared database.
ARB ticket: TO BE CREATED
