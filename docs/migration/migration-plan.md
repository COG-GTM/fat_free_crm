# Fat Free CRM — Rails → Spring Boot Migration Plan

Phased, parallelizable execution plan derived from the specs already in this directory:

| Input doc | What this plan takes from it |
|---|---|
| [`target-architecture.md`](target-architecture.md) | Route/controller inventory, target package layout, auth design, `respond_to` → JSON mapping, error contract |
| [`openapi.yaml`](openapi.yaml) | Frozen compatibility baseline for the current JSON/XML API (1971 lines) |
| [`data-model.md`](data-model.md) | ERD, data dictionary, polymorphic + join table JPA mapping, serialized columns, `versions` table |
| [`gem-mapping.md`](gem-mapping.md) | Per-gem/DSL Spring equivalent with effort & risk ratings |
| [`custom-fields-migration.md`](custom-fields-migration.md) | `cf_*` dynamic DDL analysis, JSONB target, data-migration plan |

Codebase being replaced: 35 models (2.8k LOC), 27 controllers (3.2k LOC), 28 helpers,
354 view files, 34 lib files (1.8k LOC), 198 spec files, 653-line schema.

**Estimates are in Devin sessions** (one session ≈ a focused unit of work by one agent, not a
human sprint). Where a phase says "3 sessions × 2 parallel" the two tracks can run
simultaneously in separate sessions on separate branches.

---

## 1. Strategy: strangler over a shared database

The single most important decision: **Spring Boot and Rails run side by side against the same
PostgreSQL database** until the last cutover. Rails keeps serving HTML/JS; Spring Boot serves
`/api/v1` and grows resource by resource behind a gateway (nginx/Envoy) that routes by path
prefix.

```
                     ┌──────────────────────────┐
   client ──────────▶│  gateway (path routing)  │
                     └───────┬──────────┬───────┘
                             │          │
              /api/v1/tasks  │          │  everything else
              /api/v1/...    ▼          ▼
                   ┌──────────────┐  ┌──────────────┐
                   │ Spring Boot  │  │ Rails 8 app  │
                   └──────┬───────┘  └──────┬───────┘
                          └────── same ──────┘
                              PostgreSQL
```

Consequences that shape every phase below:

1. **No schema rewrite.** Flyway starts with a baseline of the existing schema
   (`data-model.md`); Hibernate runs `ddl-auto: validate`, never `update`. New tables/columns
   are additive only, and must remain readable by Rails until Rails is retired.
2. **Two writers.** Any column both apps write needs identical semantics (soft delete
   `deleted_at`, `updated_at`, counter columns, YAML-serialized values). Anything Rails
   serializes as Ruby YAML must be dual-read on the Java side until converted (see Phase 6).
3. **Contract equivalence is testable.** `openapi.yaml` describes the *current* behavior, so
   every ported endpoint can be diffed against live Rails (Phase 2 builds that harness). This
   is what makes aggressive parallelism safe.
4. **The view layer is a separate product decision**, not a port. `gem-mapping.md` rates it
   Very High / High — 354 view files, Haml + CoffeeScript + server-generated JS
   (`responds_to_parent2`). It is deliberately sequenced last and can be descoped
   (keep Rails as the UI shell indefinitely) without blocking the backend migration.

### Deliberately deferred / out of scope decisions

- **XML responses**: recommendation is JSON-only for `/api/v1`, XML declared deprecated. Needs a
  consumer audit in Phase 0 — if external consumers exist, add `jackson-dataformat-xml`
  (Low effort) rather than dropping it.
- **JWT vs. session cookies**: Phase 7 assumes JWT for the API. If the Rails UI is kept as the
  front end past cutover, session cookies shared via a common store are the cheaper choice.
  Flagged as a Phase 0 decision because it changes Phase 7, not earlier phases.

---

## 2. Workstreams

Eight workstreams, chosen so that ownership boundaries match the dependency structure:

| ID | Workstream | Scope |
|---|---|---|
| **A** | Platform & foundation | Gradle/Maven project, Flyway baseline, config, CI, gateway, observability |
| **B** | Domain & persistence | JPA entities, repositories, polymorphic + join mappings, soft delete |
| **C** | Security | Authn (Devise→Spring Security), authz (CanCanCan→Specifications + evaluator) |
| **D** | Query & search | Pagination/sort whitelists, text+tag search, Ransack predicate DSL → Specifications |
| **E** | Read API | `index` / `show` / `autocomplete` / metadata endpoints for every resource |
| **F** | Write API & workflows | create/update/delete, lead promotion, attach/discard, subscribe, tasks, comments, admin |
| **G** | Custom fields | `cf_*` → JSONB, metadata registry, search integration, data migration |
| **H** | Peripheral systems | Audit/activity log, exports (CSV/XLS/vCard/Atom), i18n, mailers, IMAP ingestion, jobs, settings |
| **I** | View layer | Thymeleaf or SPA rebuild (optional / last) |

### Dependency graph

```
 Phase 0   Phase 1        Phase 2         Phase 3           Phase 4        Phase 5      Phase 6/7/8
 ───────   ───────        ───────         ───────           ───────        ───────      ───────────
   A0 ──▶ A1 platform ─┬─▶ B2 domain ─┬─▶ D3 search ──┬──▶ E4 read API ─┬─▶ F5 writes ─┬─▶ H6 periph.
          (skeleton)   │              │               │                 │              │
                       ├─▶ C1 authn ──┴─▶ C3 authz ───┤                 │              ├─▶ C7 auth
                       │                              │                 │              │   cutover
                       └─▶ G2 custom-field design ────┴──▶ G4 JSONB ─────┘              └─▶ I8 views
                                                              impl
        A2 contract-diff harness  ──── used by every phase from E4 onward ────▶
```

Read left to right; boxes in the same column run in parallel.

---

## 3. Phases

### Phase 0 — Decisions & inventory (1 session, blocking)

Everything else forks from here, so it is intentionally small and cheap.

| Task | Output |
|---|---|
| Audit external API consumers (XML? Atom/RSS? CSV/XLS exports? `.vcf`?) | keep/drop list per format |
| Decide auth model (JWT vs. shared session) and whether the Rails UI survives cutover | decision record |
| Decide custom-field target (JSONB — recommended in `custom-fields-migration.md` §Option B) | decision record |
| Decide build tool, Java 21, Postgres version floor (JSONB + GIN needs ≥ 9.4; assume 14+) | decision record |
| Freeze `openapi.yaml` as the compatibility baseline; tag the Rails commit it describes | git tag |
| Confirm the production schema delta vs. `db/schema.rb` (live `cf_*` columns) | column census script |

**Exit criteria**: decision records committed under `docs/migration/decisions/`; a real
production-shaped schema dump (with `cf_*` columns) available for Flyway baselining.

---

### Phase 1 — Foundation (2 sessions, 2 parallel tracks)

| Track | Work | Sessions |
|---|---|---|
| **A1** | Spring Boot 3.x skeleton per `target-architecture.md` §2.2 (`api/`, `service/`, `repository/`, `domain/`, `security/`, `config/`); Flyway baseline from the Phase 0 dump; `ddl-auto: validate`; springdoc serving `openapi.yaml`; Testcontainers-Postgres harness; CI (build + Checkstyle/SpotBugs + tests); gateway config routing nothing yet; structured logging + actuator | 1 |
| **C1** | `User`/`Group`/`Permission` entities; `UserDetailsService`; `DelegatingPasswordEncoder` with a **legacy Devise-encryptable SHA-512 encoder** (`gem-mapping.md` §3) verified byte-for-byte against real hashes; login endpoint issuing tokens; `trackable` fields updated via `AuthenticationSuccessEvent` | 1 |

**Why C1 this early**: legacy password-hash compatibility is the highest-risk *unknown* in the
auth stack. If the SHA-512 scheme can't be reproduced, the answer is a forced-reset campaign —
a product decision with lead time. Prove it in Phase 1, not Phase 7.

**Exit criteria**: `./gradlew build` green in CI; a Java test authenticates a user whose hash was
created by the Rails app; Flyway `validate` passes against a Rails-migrated database.

---

### Phase 2 — Domain model & test harness (4 sessions, 3 parallel tracks)

| Track | Work | Sessions |
|---|---|---|
| **B2** | JPA entities for all tables in `data-model.md`: 5 entities + `Task`, `Comment`, `Email`, `Address`, `Activity`, `Preference`, `Setting`, `Tag`/`Tagging`, `Field`/`FieldGroup`, `List`, `ResearchTool`, `Avatar`, join tables. `@MappedSuperclass CrmEntity` (user, assignedTo, access, deletedAt, subscribedUsers). Polymorphic pairs as discriminator columns (`data-model.md` §Mapping to JPA) — **not** JPA inheritance. `@SQLRestriction` soft delete. Repositories + `@Transactional` boundaries. Data-fidelity tests: load a Rails-seeded DB and assert every entity round-trips. | 2 |
| **A2** | **Contract-diff harness**: a test rig that fires the same request at Rails and Spring Boot and diffs status + JSON body (with a declared allow-list of intentional deltas, e.g. 403-vs-401 from `target-architecture.md` §2.6). Seeded fixture database shared by both. This is the single highest-leverage investment in the plan — it is what lets Phases 4–6 run wide in parallel without regression fear. | 1 |
| **G2** | Custom-field **design spike**: prototype `custom_fields jsonb` + `AttributeConverter` + GIN index; benchmark a JSONB-path filter against the equivalent `cf_*` column query on a realistic row count; write the dual-read shim design (Rails writes `cf_*`, Java reads both) and the type-enforcement rules from `fields` metadata | 1 |

**Exit criteria**: every table mapped and covered by a round-trip test; harness can diff a
Rails endpoint against a stub and report a structured diff; JSONB benchmark documented with a
go/no-go on Option B.

---

### Phase 3 — Cross-cutting query concerns (3 sessions, 2 parallel tracks)

These two are the shared substrate of every list endpoint; building them once, well, is what
keeps Phase 4 parallel.

| Track | Work | Sessions |
|---|---|---|
| **C3** | Authorization: `Specification<T> accessibleBy(User)` reproducing `scope :my` — `access='Public' OR user_id=:me OR assigned_to=:me OR EXISTS(permission for user/group)`; custom `PermissionEvaluator` for single-record `@PreAuthorize`; `ROLE_ADMIN` guard for the admin namespace; permission writes for Shared access. Test matrix: owner / assignee / shared-via-user / shared-via-group / public / unrelated × list & fetch, asserted against CanCanCan behavior via the harness | 1.5 |
| **D3** | Search: `Pageable` + per-entity sort whitelists (`sortable` DSL); `per_page` 1–200 clamp; text search + `#tag` search; Ransack predicate grammar (`*_cont`, `*_gteq`, association traversal) parsed into Specifications with whitelists from `has_ransackable_associations`; facet/sidebar totals in the list envelope | 1.5 |

**Coupling risk**: authz filtering and search predicates compose into one query. Agree the
`Specification` composition contract (a single `Specifications.and(accessibleBy(user), search(q))`
entry point) at the start of Phase 3 so the tracks don't collide.

**Exit criteria**: both produce reusable components with unit tests; a throwaway
`GET /api/v1/accounts` proves authz + search + pagination compose correctly and matches Rails
for a seeded corpus.

---

### Phase 4 — Read API, fanned out (5 sessions, up to 5 parallel tracks)

Now genuinely parallelizable: one resource family per session, each using B2/C3/D3.

| Track | Resources | Endpoints |
|---|---|---|
| E4a | accounts, campaigns | index, show, autocomplete, member lists (`contacts`, `opportunities`, `leads`), sidebar totals |
| E4b | contacts, leads | index, show, autocomplete, `autocomplete_account_name`, vCard read |
| E4c | opportunities | index (`weighted_sort`), show, autocomplete, member `contacts` |
| E4d | tasks, comments, emails, lists | tasks index **grouped by bucket** (`{overdue:[…], due_today:[…]}`), comments index, saved lists |
| E4e | users/me, home activity stream, admin read endpoints, `metadata/{entity}` (field groups + custom fields + select options, replacing `new`/`edit`/`options` JS actions) | |
| G4 | Custom fields JSONB implementation + dual-read shim + read-path search over custom fields (runs alongside; blocks nothing but E4e metadata) | 2 |

Traffic-shifting starts here: once a resource's read endpoints pass the harness, the gateway can
route that resource's `GET /api/v1/...` to Spring Boot. Reads are idempotent, so rollback is a
gateway config change.

**Exit criteria per track**: zero unexplained diffs vs. Rails on the seeded corpus for every
endpoint in the track; explained diffs recorded in the allow-list.

---

### Phase 5 — Write API & workflows (6 sessions, up to 4 parallel tracks)

Order within the phase follows `target-architecture.md` §2.7: smallest blast radius first.

| Track | Work | Sessions |
|---|---|---|
| F5a | comments, emails, tasks (create/update/delete, `complete`/`uncomplete`, filters→query params), lists upsert | 1 |
| F5b | accounts, campaigns writes + `attach`/`discard`, `subscribe`/`unsubscribe` (shared `AttachmentService`/`SubscriptionService`) | 1.5 |
| F5c | contacts, opportunities writes incl. nested `account` creation (`save_with_account_and_permissions`) | 1.5 |
| F5d | **Lead promotion** (`POST /api/v1/leads/{id}/promotion`): Lead → Contact + optional Account + Opportunity in one transaction, combined 422 error shape; plus `reject` | 1 |
| F5e | Admin writes: users (incl. suspend/reactivate/destroy guards), groups, tags, research tools, field groups & fields, settings update, CSV lead import (Spring Batch or a streaming service) | 1 |

Validation: Bean Validation on request DTOs must reproduce ActiveModel messages, including the
caret (`^`) prefix convention that suppresses attribute names, since the harness diffs 422 bodies.

**Exit criteria**: every write endpoint diff-clean; per-resource dual-write soak (Rails and Spring
both writing the same tables under load) with no constraint violations or lost updates.

---

### Phase 6 — Peripheral systems (4 sessions, 4 parallel tracks)

| Track | Work | Sessions |
|---|---|---|
| H6a | **Audit/activity log**: Envers + custom revision entity/listener carrying `whodunnit` and PaperTrail's `meta: {related_type, related_id}`; keep writing the existing `versions` table (or dual-write) so the Rails activity stream keeps working; `versions` endpoints | 1.5 |
| H6b | **Exports & feeds**: CSV (OpenCSV), XLS (Apache POI), vCard (ez-vcard), Atom/RSS (Rome) via content negotiation on list endpoints; `exportable` full-name projections | 1 |
| H6c | **Settings + i18n**: `@ConfigurationProperties` YAML tiers + `settings` table overlay + cache; `unroll` → `MessageSource` join; scripted YAML→properties conversion for 30+ locales; **YAML→JSON conversion of `settings.value` and serialized checkbox custom fields** (one-time Ruby-side script, run before cutover) | 1 |
| H6d | **Jobs & mail**: mailers → `JavaMailSender` + templates (pre-inlined CSS, dropping `premailer`); IMAP `MailProcessor` (Dropbox archiving + comment replies) → `@Scheduled` + `jakarta.mail`, including a Java port of `email_reply_parser_ffcrm` (~200 LOC); Solid Queue → JobRunr/Quartz. **Only one of Rails or Spring may poll the IMAP mailbox** — cut over atomically, don't dual-run | 1 |

`method_missing` audit (`gem-mapping.md` §5.2) is folded into H6c: every dynamic `Setting.foo` and
`cf_*` call site must be enumerated and made explicit. Audit-heavy, silent-failure-prone; budget
a full pass with grep-based coverage evidence, not spot checks.

---

### Phase 7 — Auth cutover & Rails retirement (2 sessions, sequential)

1. Move registration / confirmation / password-reset / remember-me to Spring endpoints; switch
   clients to the new token flow; keep Devise endpoints alive read-only until traffic is zero.
2. Flip the gateway default route to Spring Boot; Rails becomes reachable only for whatever the
   view-layer decision left behind. Freeze Rails writes, run the YAML→JSON and `cf_*`→JSONB
   final conversions with the app in maintenance mode, then drop the dual-read shim.

**Exit criteria**: no Rails process serving write traffic; Flyway owns the schema; `ddl-auto`
still `validate`; conversion scripts have verified row counts before/after.

---

### Phase 8 — View layer (optional, 8+ sessions, highly parallel)

354 view files of Haml + CoffeeScript + server-generated JS. Rated Very High / High in
`gem-mapping.md` and treated here as a **separate project** gated on a product decision (SPA vs.
Thymeleaf). It parallelizes well by screen once the API is stable, and the backend migration is
complete and shippable without it. Do not put it on the critical path.

---

## 4. Schedule shape

Critical path: `0 → A1/C1 → B2 → C3/D3 → E4 → F5 → C7`. Peripheral (H6) and custom fields (G)
hang off the side; views (I8) are detached.

| Phase | Sessions (sum) | Max useful parallelism | Wall-clock (sessions) |
|---|---|---|---|
| 0 Decisions | 1 | 1 | 1 |
| 1 Foundation | 2 | 2 | 1 |
| 2 Domain + harness | 4 | 3 | 2 |
| 3 Query concerns | 3 | 2 | 1.5 |
| 4 Read API | 7 | 5 | 2 |
| 5 Writes | 6 | 4 | 2 |
| 6 Peripherals | 4.5 | 4 | 1.5 |
| 7 Cutover | 2 | 1 | 2 |
| **Backend total** | **~30** | | **~13** |
| 8 Views (optional) | 8+ | many | 3+ |

Serialized, the backend is ~30 sessions of work; with the parallelism above it compresses to
~13 sessions of wall clock. The compression is bought entirely by Phase 2's contract-diff
harness and Phase 3's shared query substrate — cutting either one collapses Phases 4–6 back into
a serial queue.

External waits, not counted above: the auth-model and view-layer product decisions (Phase 0),
production schema/`cf_*` census access, and a maintenance window for Phase 7.

---

## 5. Parallelization rules

1. **One resource family per session/branch** in Phases 4–5. Shared code (`CrmEntity`,
   `accessibleBy`, search builder, `ProblemDetail` handler) is frozen at the end of Phase 3;
   changes to it after that go through a single owner to avoid cross-branch churn.
2. **Contract-first**: a track's first commit is the OpenAPI slice + failing harness tests, then
   the implementation. Keeps parallel tracks from drifting on error shapes and envelopes.
3. **Additive migrations only** while Rails is live. Every Flyway script must be reviewed against
   "can Rails still read and write this?".
4. **No shared mutable session state.** Rails stores filters, sort prefs, and view toggles in the
   session; the ported API takes them as explicit query params persisted in `UserPreference`
   (`target-architecture.md` §2.5). Tracks must not invent per-track session mechanisms.
5. **Serialize the singletons**: IMAP polling, scheduled jobs, and the CSV importer must run in
   exactly one app at a time. These are the only places where dual-running is unsafe by
   construction.

## 6. Risk register

| Risk | Impact | Mitigation | Phase |
|---|---|---|---|
| Devise-encryptable hashes not reproducible in Java | All users locked out at cutover | Prove in Phase 1; fallback = forced reset campaign (needs product lead time) | 1 |
| Custom fields: JSONB loses per-column typing & Ransack searchability | Admin-defined fields silently unusable | Type enforcement in service layer from `fields` metadata; benchmark + search tests in the G tracks; dual-read shim | 2, 4 |
| Envers can't express PaperTrail `meta: {related:}` | Activity stream degrades | Custom revision entity + listener; keep writing `versions` until Rails retires | 6 |
| Row-level authz post-filtered instead of pushed into SQL | Wrong pagination totals + data leaks | `accessibleBy` Specification composed into every list query; harness diffs totals, not just page contents | 3 |
| Dual-write divergence on shared columns | Data corruption | Additive-only migrations; per-resource dual-write soak before shifting write traffic | 5 |
| YAML-serialized DB values unreadable from Java | Settings/checkbox fields break | One-time Ruby-side YAML→JSON script + dual-read converter until cutover | 6, 7 |
| View layer creeps onto the critical path | Backend migration stalls behind a UI rewrite | Explicitly gated as Phase 8 and descopable; Rails UI may live on indefinitely | 8 |
| `method_missing` call site missed in the audit | Silent behavior change, no exception | grep-based coverage evidence over models/views/lib + harness diffs | 6 |

## 7. Definition of done (backend)

- Every endpoint in `openapi.yaml` served by Spring Boot, diff-clean against the frozen Rails
  baseline except for the documented allow-list of intentional deltas.
- Flyway is the sole schema owner; no runtime DDL anywhere in the Java code.
- Authorization test matrix passes for all six access shapes across list and single-record paths.
- Custom fields creatable, editable, searchable, and exportable without a deploy or restart.
- Activity stream, exports, mail ingestion, and scheduled jobs running in Spring Boot only.
- No Rails process handling write traffic.
