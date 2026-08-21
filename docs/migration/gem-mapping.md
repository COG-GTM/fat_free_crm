# Rails Gem/DSL → Spring Boot Mapping

Scope: every runtime gem from `Gemfile` / `fat_free_crm.gemspec` and every model-level DSL used in `app/models/`, mapped to its Spring Boot / Java equivalent, with reimplementation effort and risk ratings.

**Effort scale**: Low (< 1 session, mostly configuration), Medium (1 session, straightforward custom code), High (1–2 sessions, substantial custom design), Very High (2+ sessions, requires new architecture — coordinate with Session 4).
**Risk scale**: Low (well-trodden equivalent), Medium (behavioral differences to reconcile), High (no clean equivalent; custom design needed).

---

## 1. Architecture Overview

```
  Rails (fat_free_crm)                        Spring Boot target
  ─────────────────────                       ──────────────────
  ┌─────────────────────────┐                 ┌──────────────────────────────┐
  │ Devise + encryptable    │  ──────────▶    │ Spring Security +            │
  │ confirmable/recoverable │                 │ PasswordEncoder chain        │
  ├─────────────────────────┤                 ├──────────────────────────────┤
  │ CanCanCan Ability +     │  ──────────▶    │ Method security (@PreAuth)   │
  │ permissions table       │                 │ + custom PermissionEvaluator │
  ├─────────────────────────┤                 ├──────────────────────────────┤
  │ PaperTrail (versions)   │  ──────────▶    │ Hibernate Envers (_AUD)      │
  ├─────────────────────────┤                 ├──────────────────────────────┤
  │ Ransack / ransack_ui    │  ──────────▶    │ JPA Specifications /         │
  │                         │                 │ QueryDSL predicate builder   │
  ├─────────────────────────┤                 ├──────────────────────────────┤
  │ ActiveJob + Solid Queue │  ──────────▶    │ @Scheduled / @Async +        │
  │ IMAP MailProcessor      │                 │ jakarta.mail IMAP poller     │
  ├─────────────────────────┤                 ├──────────────────────────────┤
  │ acts_as_* mixins        │  ──────────▶    │ Custom JPA entities +        │
  │ (tags, comments, list)  │                 │ @MappedSuperclass helpers    │
  ├─────────────────────────┤                 ├──────────────────────────────┤
  │ Dynamic custom fields   │  ──╳ NO CLEAN   │ Custom design required       │
  │ (DDL at runtime +       │     EQUIVALENT  │ (JSONB / EAV / metamodel)    │
  │  method_missing)        │                 │ → coordinate w/ Session 4    │
  └─────────────────────────┘                 └──────────────────────────────┘
```

---

## 2. Model DSL Mapping (app/models/)

All five entities (`Account`, `Campaign`, `Contact`, `Lead`, `Opportunity`) declare the same DSL block; `Task`, `Comment`, `Email`, `Address` use subsets.

| Rails DSL | What it does | Spring/Java equivalent | Effort | Risk |
|---|---|---|---|---|
| `uses_user_permissions` (`lib/fat_free_crm/permissions.rb`) | Adds polymorphic `permissions` association + `scope :my` filtered by CanCanCan `accessible_by`; writes `Permission` rows for Shared access; `access = Public/Private/Shared` | `Permission` JPA entity (`assetType`, `assetId`, `userId`, `groupId`); a shared `@MappedSuperclass` or interface `Permissionable` with an `access` enum; visibility filtering via JPA `Specification` composed into every list query (see §4 CanCanCan) | High | Medium — row-level filtering must be pushed into queries, not post-filtered |
| `acts_as_commentable` (forked gem) + `uses_comment_extensions` | Polymorphic `comments` association + `add_comment` helpers | `Comment` entity with `commentableType`/`commentableId` discriminator columns (or JPA `@Any` via Hibernate); service-layer helpers | Medium | Low |
| `acts_as_taggable_on :tags` | Polymorphic tagging (`tags`, `taggings` tables), tag lists, tagged-with queries | Custom `Tag` + `Tagging` entities (same two-table schema); `tagList` DTO mapping; `taggedWith` Specification | Medium | Low — schema ports directly |
| `has_paper_trail versions: {class_name: 'Version'}, ignore:/meta:` | Full audit trail per record; `meta: {related:}` links versions to a parent asset; powers the activity log UI | Hibernate Envers (`@Audited`, `@NotAudited` for ignored cols like `subscribed_users`). **Gap**: Envers has no `meta`/`related` concept and its `_AUD` schema differs from the `versions` table — the activity-log feature needs a custom `RevisionListener` + custom revision entity to store `related_type/related_id` and `whodunnit` | High | Medium — activity log reads the versions table directly; data migration of existing `versions` rows is lossy-prone |
| `has_fields` (`lib/fat_free_crm/fields.rb`) | Custom-field framework: per-class `FieldGroup`s, dynamic validation, serialization of checkbox fields, `method_missing` fallback for `cf_*` attributes | **No clean equivalent** — see §5 | Very High | High |
| `exportable` (`lib/fat_free_crm/exportable.rb`) | Adds `user_id_full_name` / `assigned_to_full_name` / `completed_by_full_name` accessors for CSV export | Trivial: DTO/projection fields in an export mapper; CSV via Apache Commons CSV or OpenCSV | Low | Low |
| `sortable by: [...], default:` (`lib/fat_free_crm/sortable.rb`) | Declares whitelisted sort clauses + default sort per model | Spring Data `Sort` + a per-entity whitelist map (constant or annotation); `PageRequest.of(page, size, sort)` | Low | Low |
| `has_ransackable_associations %w[...]` + `ransackable_attributes` | Whitelists associations/attributes exposed to the Ransack search builder | Whitelist map consumed by the QueryDSL/Specification search builder (see §4 Ransack) | Low (given search builder exists) | Low |
| `acts_as_list` (on `Field`, `FieldGroup`) | `position` column ordering with reorder helpers | `position` integer column + service methods for insert/move/reorder (or a small helper superclass) | Low | Low |
| `serialize :value` (Setting), `serialize(field, Array)` | YAML-serialized columns | JPA `AttributeConverter` (JSON via Jackson). **Data migration**: existing values are Ruby YAML, must be converted | Low code / Medium migration | Medium |
| `method_missing` on `Setting` and `cf_*` attributes | Dynamic attribute resolution | **No equivalent** — see §5 | — | High |

---

## 3. Auth Stack: Devise → Spring Security

`Gemfile`: `devise ~> 5.0` (gemspec pins `~> 4.6`), `devise-encryptable`, `devise-security`, `devise-i18n`. `User` model modules: `database_authenticatable`, `registerable`, `recoverable`, `rememberable`, `trackable`, `validatable`, `encryptable`, `confirmable` (per `app/models/users/user.rb`).

| Devise module | Spring Security equivalent | Effort | Risk |
|---|---|---|---|
| `database_authenticatable` | `DaoAuthenticationProvider` + `UserDetailsService` + `BCryptPasswordEncoder` | Low | Low |
| `encryptable` (legacy SHA-512 salted hashes) | `DelegatingPasswordEncoder` with a custom legacy encoder for existing hashes + rehash-on-login | Medium | Medium — must byte-match Devise's `stretches`/salt/pepper scheme or force resets |
| `recoverable` | Custom flow: reset-token entity + mail via `JavaMailSender` (no built-in) | Medium | Low |
| `confirmable` | Custom flow: confirmation token + account-enabled check in `UserDetails.isEnabled()` | Medium | Low |
| `rememberable` | Built-in `rememberMe()` (persistent token repository) | Low | Low |
| `trackable` (`sign_in_count`, `last_sign_in_at`, IPs) | `AuthenticationSuccessEvent` listener updating the user row | Low | Low |
| `registerable` + `User.can_signup?` gating | Registration controller + conditional endpoint | Low | Low |
| `validatable` | Bean Validation (`@Email`, `@Size`) on registration DTOs | Low | Low |
| `devise-security` (password expiry/complexity) | Custom filters/validators (or Passay for complexity) | Medium | Low |
| `rack-attack` (throttling) | Bucket4j / Resilience4j rate limiter filter, or gateway-level | Low | Low |

Overall: **Effort High (aggregate), Risk Medium** — the flows are standard but numerous, and password-hash compatibility is the main migration hazard.

---

## 4. Core Capability Gems

| Gem | Purpose in FFCRM | Spring/Java equivalent | Effort | Risk |
|---|---|---|---|---|
| `cancancan >= 3.3` | `Ability` class (`app/models/users/ability.rb`): admin-can-all; ownership (`user_id`), assignment (`assigned_to`), `access: 'Public'`, plus per-row grants from the `permissions` table (user & group) | Spring Security method security (`@PreAuthorize`) for action checks **plus** a custom `PermissionEvaluator` for per-object checks **plus** query-level filtering: a reusable `Specification<T> accessibleBy(User)` reproducing `scope :my` (public OR owner OR assignee OR permission-row match incl. groups). Spring ACL is an option but its schema is heavier than needed; a bespoke evaluator matching the existing `permissions` table is simpler | High | Medium — the dual nature (decision on single objects + filtering of lists) must stay consistent |
| `ransack ~> 4` + `ransack_ui ~> 3.0` (FFCRM fork) | Advanced search UI; predicate grammar (`name_cont`, `*_gteq`), searches across whitelisted associations incl. custom fields | JPA Criteria `Specification` builder or QueryDSL `Predicate` builder that parses a predicate DSL (field + operator + value) from the frontend; whitelists from §2. `ransack_ui`'s JS builder must be rebuilt against the new API | High | Medium — custom-field searchability compounds with §5; frontend rebuild included |
| `paper_trail ~> 16` | Versioning + activity log (see §2) | Hibernate Envers + custom revision entity/listener | High | Medium |
| `solid_queue` + `activejob` | DB-backed background jobs | Spring `@Async` + `@Scheduled`; if durable/queued jobs are needed, JobRunr or Quartz (JDBC job store) — both DB-backed, matching Solid Queue's no-extra-infra property | Medium | Low |
| IMAP `MailProcessor` (`lib/fat_free_crm/mail_processor/{base,dropbox,comment_replies}.rb`) | Polls IMAP; Dropbox archives emails onto entities by address matching; comment replies append comments | `@Scheduled` poller using `jakarta.mail` IMAP (or Spring Integration Mail `ImapMailReceiver`); port keyword/address-matching + entity-linking logic | Medium–High | Medium — parsing/matching logic is bespoke; `email_reply_parser_ffcrm` must be ported or replaced (no maintained Java equivalent; small algorithm, portable) |
| `Setting` + `Setting.unroll` (`app/models/setting.rb`) | 3-tier settings: defaults YAML < local YAML < `settings` table; `unroll` maps symbol lists to translated select options | `@ConfigurationProperties` for YAML tiers + a `settings` JPA entity overlay resolved through a `SettingsService` with cache (`@Cacheable`, evict per request or TTL); `unroll` → service method joining values with `MessageSource` translations. **Note**: DB values are YAML-serialized Ruby objects — migrate to JSON | Medium | Medium — `method_missing`-style arbitrary keys become explicit service lookups; callers must be enumerated |
| `devise` family | see §3 | Spring Security | High | Medium |
| `pg` / `mysql2` / `sqlite3` | DB drivers | JDBC drivers + Spring Data JPA | Low | Low |
| `will_paginate` | Pagination | Spring Data `Pageable`/`Page` | Low | Low |
| `simple_form` + `dynamic_form` + `country_select` | Form builders | Frontend concern (Thymeleaf fragments or SPA components); country list via `java.util.Locale` ISO codes | Medium (part of view rebuild) | Low |
| `haml`, `sassc-rails`, `coffee-rails`, `sprockets-rails`, `uglifier`, `execjs`, `mini_racer`, `bootstrap ~> 5.2`, `jquery-*`, `select2-rails`, `font-awesome-rails`, `responds_to_parent2`, `rails3-jquery-autocomplete` | Asset pipeline + view layer (Haml templates, CoffeeScript, SJR responses) | Full view-layer rewrite: Thymeleaf + webpack/Vite, or an SPA. `responds_to_parent2`/RJS-style responses have no equivalent — replace with fetch/JSON patterns | Very High (biggest single line item; it is a rewrite, not a mapping) | High |
| `rails-i18n` + `devise-i18n` + FFCRM locale files | i18n (30+ locales) | Spring `MessageSource` + converted `messages_*.properties` (script the YAML→properties conversion) | Medium | Low |
| `premailer` | Inlines CSS in outgoing HTML mail | `cssinliner` (jsoup-based, e.g. `com.vladsch.flexmark` alt) or Jsoup + custom inliner; simplest: keep templates pre-inlined | Low | Low |
| `nokogiri` | HTML/XML parsing (mail processing, autolink) | Jsoup | Low | Low |
| `rails_autolink` | Auto-link URLs in text | Jsoup/regex utility or `org.nibor.autolink` | Low | Low |
| `activemodel-serializers-xml` + `responders` | XML/JSON API rendering | Jackson (+ `jackson-dataformat-xml`) with `@RestController` content negotiation | Low | Low |
| `vcardigan` | vCard import/export for contacts | `ez-vcard` (mature Java library) | Low | Low |
| `mini_magick` + `image_processing` | Avatar image resizing | `imgscalr` or Thumbnailator (pure Java; avoids ImageMagick dependency) | Low | Low |
| `ffaker` | Demo/seed data | JavaFaker / DataFaker in seed profile | Low | Low |
| `thor` | CLI tasks (`ffcrm:setup` etc.) | Spring Boot `ApplicationRunner` commands or picocli | Low | Low |
| `validates_lengths_from_database` | Auto length validation from column limits | Unneeded — JPA `@Column(length=)` + Bean Validation `@Size` are explicit | Low | Low |
| `sparql-client` | SPARQL queries (RDF lookup) | Apache Jena ARQ | Low | Low — verify actual usage; appears peripheral |
| `addressable` | URI parsing | `java.net.URI` / Guava | Low | Low |
| `rails-observers` | Observer callbacks (`lib/fat_free_crm` hook system, subscribed-user notifications) | Spring `ApplicationEvent` + `@EventListener`, or JPA `@EntityListeners` | Medium | Low |
| `acts_as_list` | see §2 | position column + helpers | Low | Low |
| `email_reply_parser_ffcrm` | Strip quoted text from email replies | Port the algorithm (~200 LOC) to Java | Low | Medium — fidelity matters for comment-reply UX |
| `bootsnap`, `tzinfo-data`, `logger`, `bigdecimal`, `csv`, `ostruct`, etc. | Ruby runtime shims | N/A on JVM (built-in) | — | — |
| Dev/test-only: `rspec-rails`, `factory_bot`, `capybara`, `selenium-webdriver`, `rubocop*`, `guard*`, `capistrano*`, `brakeman`, `byebug`, `pry`, `timecop`, `database_cleaner`, `zeus`, `headless`, `webrick`, `puma`, `rails_12factor` | Test/deploy tooling | JUnit 5 + Testcontainers + Selenium/Playwright; Checkstyle/SpotBugs; CI deploy pipeline | Medium (test suite rewrite tracked separately) | Low |

---

## 5. Features with NO Clean Equivalent (custom design — coordinate with Session 4)

### 5.1 Dynamic custom-field columns (`has_fields` / `CustomField`)

`app/models/fields/custom_field.rb` issues **live DDL**: creating a `CustomField` row runs `ALTER TABLE ... ADD COLUMN cf_*` at runtime, changes column types when "safe", and never drops columns. Ransack searchability and per-tag `FieldGroup` visibility hang off this.

```
  Rails runtime                          Why the JVM can't copy this
  ─────────────                          ────────────────────────────
  admin adds "Region" field              JPA entities are compiled classes:
        │                                the set of columns is fixed at
        ▼                                build time. Runtime ALTER TABLE
  ALTER TABLE contacts                   would leave Hibernate's metamodel,
    ADD COLUMN cf_region varchar  ──╳──  second-level cache, and generated
        │                                accessors unaware of the column.
        ▼
  method_missing catches cf_region,
  reloads column info, retries
```

Candidate redesigns (decision for Session 4):
1. **JSONB column** (`custom_fields jsonb` per entity) — recommended on PostgreSQL: indexable (GIN), searchable via `jsonb_extract_path`, no DDL, maps to a `Map<String,Object>` with an `AttributeConverter`. Loses per-column type enforcement → enforce types in the service layer from `fields` metadata.
2. **EAV table** — explicitly rejected by the original authors for search performance; only viable if search is offloaded (e.g. to Elasticsearch/OpenSearch).
3. **Keep wide-column + JDBC DDL service** — closest behavioral port; requires bypassing JPA for `cf_*` access (dynamic `Map` via native queries) and careful cache handling. Highest fidelity, highest complexity.

**Effort: Very High. Risk: High.** Includes data migration of existing `cf_*` columns and rebuilding Ransack-over-custom-fields search.

### 5.2 `method_missing` dynamic attribute resolution

Used in two places: `cf_*` attribute fallback (`lib/fat_free_crm/fields.rb`) and `Setting.method_missing` (arbitrary `Setting.foo` reads). Java has no message-passing fallback; every dynamic access site must become an explicit map/service lookup (`entity.getCustomField("cf_region")`, `settings.get("foo")`). Effort is not the lookup itself but **enumerating every call site** across models, views, and mail processing. **Effort: Medium (audit-heavy). Risk: High** (silent behavior changes if a site is missed).

### 5.3 Other no-clean-equivalent items

| Feature | Issue | Direction |
|---|---|---|
| PaperTrail `meta: {related:}` + `Version`-table-driven activity log | Envers schema/semantics differ | Custom revision entity + listener (§2) |
| Callback/hook plugin system (`lib/fat_free_crm/callback.rb`, `view_factory.rb`, `tabs.rb`) | Ruby open-class plugin injection | Spring `ApplicationEvent` + pluggable bean interfaces; view hooks only meaningful after view-layer decision |
| `responds_to_parent2` / SJR (server-generated JS responses) | No JVM analogue | Replace with JSON APIs + client-side rendering during view rewrite |
| Legacy Devise-encryptable password hashes | Hash-format compatibility | Custom `PasswordEncoder` + rehash-on-login, or forced reset campaign |
| YAML-serialized DB values (`settings.value`, checkbox custom fields) | Ruby YAML unreadable from Java | One-time Ruby-side migration script YAML→JSON before cutover |

---

## 6. Effort Summary

| Cluster | Effort | Risk |
|---|---|---|
| Auth (Devise family → Spring Security) | High | Medium |
| Authorization (CanCanCan + permissions → evaluator + Specifications) | High | Medium |
| Audit/activity log (PaperTrail → Envers + custom) | High | Medium |
| Search (Ransack/ransack_ui → QueryDSL/Specifications + UI) | High | Medium |
| Jobs + mail ingestion (Solid Queue/ActiveJob + IMAP) | Medium–High | Medium |
| Tags/comments/list-position (acts_as_*) | Medium | Low |
| Settings system | Medium | Medium |
| Custom fields (dynamic DDL + method_missing) | **Very High** | **High** |
| View layer / asset pipeline | **Very High** | **High** |
| Long tail of utility gems (vCard, images, i18n, CSV, autolink, …) | Low–Medium each | Low |
