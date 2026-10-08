# AB-273: Peripherals (exports, settings/i18n, jobs/mail)

- **Status**: Proposed
- **Ticket**: [AB-273](https://cog-gtm.atlassian.net/browse/AB-273) (epic AB-261)
- **ARB ticket**: TO BE CREATED

Each AB-273 track appends one `## Track:` section below.

## Track: settings-i18n

### Context

Spring needs Rails' `Setting[...]` values (locale selection now; mail, facets and validations in
later tracks) and the app's translation catalog, with identical results while both apps share the
PostgreSQL `settings` table. Rails ground truth:

- `app/models/setting.rb:55-65` `Setting[name]`: per-request cache → first `settings` row whose
  deserialized value is `present?` → `yaml_settings[name]` → nil. A blank DB value (nil, false, "",
  whitespace, [], {}) falls through to YAML; any other DB value replaces the YAML value wholesale.
- `app/models/setting.rb:114-117` + `lib/fat_free_crm/load_settings.rb:14-19`: `yaml_settings` is
  `config/settings.default.yml` deep-merged (`deep_merge!`: Hash+Hash recurses, anything else —
  including nil/false and arrays — replaces) with optional `config/settings.yml`, as a
  `HashWithIndifferentAccess`.
- `app/controllers/application_controller.rb:109-111` clears the cache every request;
  `:116-119` picks the user's `locale` preference, else `Setting.locale`.
- `settings.value` is Psych YAML (`serialize :value`), loaded with the permitted classes in
  `config/application.rb:87-99`; other `!ruby/object` classes raise `Psych::DisallowedClass`.
- `config/initializers/locale.rb:15-19`: i18n fallbacks on; `I18n.fallbacks[:en] = [:"en-US"]`.

Locale inventory (the Jira text says "30+"): **18 app-owned locales** in
`config/locales/fat_free_crm.*.yml` (the locale is each file's top-level key, e.g.
`fat_free_crm.de-DE.yml` → `de`). Rails' `I18n.available_locales` reports 134 because gems add
their own files: rails-i18n 8.1.0 (287 files, 123 locales), devise-i18n 1.15.0 (72 files,
72 locales) and other gems (57 files, 33 locales) — counts captured in `rails_i18n_matrix.json`
`load_path_census`.

### Decision

1. **Settings tiers.** `com.fatfreecrm.service.settings.SettingsService` (config
   `ffcrm.settings.*`, `SettingsProperties`) resolves exactly like `Setting[]`: DB row present →
   wins wholesale; otherwise the YAML tier = classpath `settings/settings.default.yml` (byte copy of
   the Rails file, enforced by Gradle `verifySettingsDefaults` in `check`) deep-merged with the
   optional `${FFCRM_SETTINGS_FILE}` overrides (missing file ignored like `File.exist?`; ERB fails
   startup because Spring cannot evaluate it). Map keys are normalized to indifferent access; Ruby
   symbols stay `":name"` strings. Duplicate rows resolve to the lowest id. Read-only: Rails remains
   the writer of `settings`.
2. **Bounded staleness.** The DB tier is a snapshot of the whole `settings` table refreshed lazily
   when older than `ffcrm.settings.cache-ttl` = **30s** (same value as the AB-271 custom-field
   registry). A Rails admin change becomes visible to Spring within 30s. Resolved values are
   memoized per snapshot, so the bound holds for every key.
3. **YAML and JSON values.** `SettingValueCodec` reads both: `---` documents and non-JSON text via
   `RailsYaml`, strict JSON via Jackson; `!ruby/<kind>:<Class>` tags outside the Rails permitted list
   are rejected (row treated as absent, logged by name/id only).
4. **YAML→JSON conversion for AB-274.** Not run while Rails runs. Profile `convert-settings-json`
   (`SettingsJsonConversionRunner` → `SettingsJsonConversion`) converts each non-JSON row, verifies
   the JSON decodes to the same value, and writes with
   `UPDATE settings SET value = ? WHERE id = ? AND value = ?` (concurrent-change guard). Dry run is
   the default; a real run also needs `confirm-rails-stopped=true`; re-runs are no-ops; any failed
   row → non-zero exit. Cutover runbook:
   `java -jar spring/build/libs/fat-free-crm-api-0.1.0-SNAPSHOT.jar --spring.profiles.active=convert-settings-json` (dry run, review
   the report), then add `--ffcrm.settings.conversion.dry-run=false
   --ffcrm.settings.conversion.confirm-rails-stopped=true` once Rails is stopped.
5. **i18n catalog.** `rake ffcrm:migration:i18n_properties` generates
   `spring/src/main/resources/i18n/messages_<tag>.properties` (UTF-8, flattened dotted keys,
   deterministic) from the 18 app files. Plain strings keep Rails `%{name}` text (Rails interpolates
   only when arguments are passed); plural hashes become ICU
   `{count, plural, =0{…} one{…} few{…} many{…} other{…}}`. Non-string YAML keys (`true`/`false`)
   and nil leaves are skipped because Rails' dotted lookup can't reach them.
6. **`I18nService.t(key, Locale, Map<String,Object>)`** walks the Rails fallback chain and formats
   with **ICU4J** `MessageFormat` using the locale where the key was found (CLDR plural rules).
   Chains are taken from the Rails fixture: `<locale>` → parents → `en`, except `en → [en-US]`
   (configured by `ffcrm.i18n.fallback-defaults` / `fallback-overrides`). A missing argument throws
   `MissingInterpolationArgumentException` (Rails `I18n::MissingInterpolationArgument`); a key
   missing everywhere returns `Translation missing: <tag>.<key>`.
7. **Spring wiring.** `FfcrmMessageSource` (bean `messageSource`) delegates to `I18nService` but
   keeps the `MessageSource` contract (default message / `NoSuchMessageException`, never the Rails
   "Translation missing" text), so problem-detail and Bean Validation output is unchanged. It
   replaces Boot's auto-configured `messageSource`; no `spring.messages` bundle is used in the
   service, and AB-272's ActiveModel messages load their own `validation/` JSON. `FfcrmLocaleResolver`
   (bean `localeResolver`) orders: authenticated user's `locale` preference → `Accept-Language`
   matched to the 18 bundles → `Setting[:locale]` → `en-US`.
8. **Gem locales are not converted.** Spring needs no gem keys today. ActiveModel/validation
   messages belong to AB-272. Date/number/helpers/simple_form/ransack/will_paginate strings are
   rendered only by Rails views (AB-275). Devise strings belong to the Rails login UI.
9. **New dependency:** `com.ibm.icu:icu4j:77.1`. It is the stable ICU release from the Unicode
   Consortium (Unicode-3.0 license), a pure library with no network access and no transitive
   dependencies.
10. **Coverage evidence.** `rake ffcrm:migration:settings_cf_coverage` scans `app/`, `lib/` and
    `config/` for every `Setting.<x>` / `Setting[...]` and `cf_` site and generates
    `docs/migration/settings-cf-coverage.md`, classified by the ordered rules in
    `docs/migration/settings-cf-coverage.rules.yml`. That gives 229 sites: 6 ported, 19 partial,
    184 not ported (each with an owner/reason), and 20 n/a. `CHECK=1` fails on any unclassified
    site or table drift; line-number-only drift only warns.

### Parity and CI

- `rake ffcrm:migration:settings_matrix` → `spring/src/test/resources/settings/rails_settings_matrix.json`
  has 4 tier states (defaults; plus override file; DB overlay on each). The DB rows cover blank
  fall-through, wholesale replace, symbols, numbers, nested hashes, HWIA, JSON and a disallowed
  class. `RailsSettingsParityTest` replays every key and dig.
- `rake ffcrm:migration:i18n_matrix` → `spring/src/test/resources/i18n/rails_i18n_matrix.json`
  holds 6265 `I18n.t` cases across the 18 locales: every plural key at 14 counts, every
  interpolated key, sampled plain keys, en-US fallbacks and missing keys. It also records the
  fallback chains. `RailsI18nParityTest` replays them for exact string equality.
- CI job `settings-i18n` (`.github/workflows/spring-api.yml`) does four things:
  1. Regenerates the properties and fails on any diff.
  2. Runs the coverage `CHECK=1`.
  3. Regenerates both Rails fixtures on postgres:16 and fails on any diff.
  4. Runs the four new specs.

### Known deltas (explicit in the tests)

- `db_bad_class` setting row: Rails raises `Psych::DisallowedClass` (`config/application.rb:87-99`).
  Spring treats the row as absent instead of failing the request.
- Rails results that come from gem-provided translations Spring does not ship (see Decision 8).
  These are tolerated only when Spring has no translation for the key at all. 46 cases.
- pt-BR `pluralize.*` with count 0: rails-i18n's pt-BR rule puts 0 in `other`, while CLDR/ICU4J puts
  it in `one` (rails-i18n `rails/pluralization/pt-BR.rb:3`). 6 cases.
- A plural key called without `count`: Rails returns the raw Hash, but `I18nService` returns a
  String. 7 cases.

`RailsI18nParityTest` pins each delta count, so any new delta fails the build.

### Alternatives considered

- **Spring `ResourceBundleMessageSource` with `java.text.MessageFormat`:** rejected. It has no CLDR
  plural categories (`few`/`many` for ru/pl).
- **Per-request cache clearing like Rails:** rejected. It means a full-table read on every request.
  A 30s TTL bounds staleness at low cost.
- **Converting `settings.value` to JSON now:** rejected by the ticket. Rails keeps writing
  YAML while it runs, so the table would end up with mixed formats. The conversion runs once at
  the AB-274 cutover, after Rails stops writing.

### Deviations

- Jira says "30+ locales". The app owns 18; the rest come from gems (counts above).
- Plain strings keep Rails `%{name}` syntax instead of ICU `{name}`. `I18nService` interpolates them
  Rails-style, because Rails returns the raw string when no arguments are given and the parity
  fixture requires that behavior. Only plural hashes are ICU patterns.

### ARB triage

```
ARB triage: NO_ARB
Triggers:
- (none) The heuristic detector reported T2/T3/T6 hits. All of them are false positives after reading the diff:
  - T2 matched docs/migration/* and lib/fat_free_crm/migration/* file names. That is offline tooling; no
    data store or column is added or changed, and the YAML->JSON conversion only ships as an AB-274 tool.
  - T3 matched the build.gradle.kts dependency block, a locale string mentioning "OpenAI", and a URL in a
    YAML comment. com.ibm.icu:icu4j:77.1 is an offline library with no dependencies, not a vendor or
    network integration.
  - T6 matched a test import of Jwt. FfcrmLocaleResolver only reads the existing security context for
    the locale preference; authn/authz is unchanged.
Not triggered: T1, T2, T3, T4, T5, T6, T7, T8, T9
Required next steps: none. The new dependency is recorded here (Decision 9). Re-triage under AB-274 before
executing the settings conversion against the shared database.
```
