# AB-273. Peripherals: Settings tiers, YAML→JSON conversion, and i18n

- **Status:** Proposed
- **Date:** 2026-10-08
- **ARB ticket:** TO BE CREATED
- **Authors:** Devin (AB-273)
- **Owning team:** TBD — owner to confirm before ARB (Fat Free CRM migration team, epic AB-261)
- **Related ADRs:** `0001-spring-boot-api-service-skeleton.md`, `ab-271-custom-fields-jsonb.md`

## Context

The strangler migration needs Rails `Setting` semantics and the `config/locales`
translation catalog on the Spring side: `Setting[:key]` drives locale selection and later
migration tracks, and Rails' per-locale strings must render identically for parity work.
The Rails ground truth (`app/models/setting.rb:55-65`, `config/application.rb:87-99`):

- `Setting[:name]` reads the first `settings` row whose serialized `value` is present;
  blank values (nil/false/""/whitespace/[]/{}) fall through to `yaml_settings`, a
  deep-merged `HashWithIndifferentAccess` of `config/settings.default.yml` then
  `config/settings.yml`. `Setting.clear_cache!` runs every request.
- `settings.value` is Psych-serialized; Rails loads it with
  `config.active_record.yaml_column_permitted_classes` (Symbol, Date, Time, BigDecimal,
  HWIA, `ActiveSupport::TimeWithZone`, `ActiveSupport::TimeZone`, `ActsAsTaggableOn::*`,
  `ActiveRecord::Type::Time::Value`) — anything else raises `Psych::DisallowedClass`.
- The i18n gem only interpolates `%{name}`/`%<name>s` when arguments are passed; with no
  args the raw string is returned, and plural hashes are returned as hashes. Observed
  fallback chains (fixture `rails_i18n_matrix.json`): `en → [en-US]` (FFCRM overrides
  `I18n.fallbacks[:en]`), `en-GB → [en-GB, en]`, `de-DE → [de-DE, de, en]`,
  `en-US → [en-US, en]`, everything else `[<lang>, en]`-style with `en` appended.

## Decision

- **Settings:** `SettingsService` implements the two tiers (DB row wins wholesale when
  decoded-and-present; else deep-merged defaults+overrides YAML) behind a TTL snapshot
  (`ffcrm.settings.cache-ttl`, 30s) keyed on the injected `Clock`; `overrides` points at
  `${FFCRM_SETTINGS_FILE}` and startup fails fast on ERB (`<%`) in it. Decoding mirrors
  Psych: `!ruby/<kind>:<class>` tags are rejected unless the class is in the Rails
  permitted list; undecodable rows are skipped (logged). `db_bad_class` is a recorded
  known delta: Rails raises `Psych::DisallowedClass`, Spring tolerates (nil). Duplicate
  `settings` rows resolve to the lowest `id`.
- **Conversion:** `SettingsJsonConversion` + `convert-settings-json` profile is the
  operator-run YAML→JSON cutover for AB-274: per-row decode → strict JSON re-encode →
  `deepEquals` verification → guarded `UPDATE ... AND value = ?`. Non-dry-run refuses
  unless `ffcrm.settings.conversion.confirm-rails-stopped=true`.
- **i18n:** `rake ffcrm:migration:i18n_properties` emits
  `spring/src/main/resources/i18n/messages_<tag>.properties` per
  `config/locales/fat_free_crm.*.yml`. Plain leaves stay verbatim (`%{name}`/`%%`/`%<x>s`)
  because Rails skips interpolation without args; plural hashes become ICU
  `{count, plural, ...}` (`zero` → `=0`, missing `other` raises). `I18nService` resolves
  through the fixture-captured fallback chains with the found-locale `ULocale`; it
  reimplements `%{name}` substitution and uses ICU4J `MessageFormat` for plural formats,
  raising `MissingInterpolationArgumentException` on missing args and
  `"Translation missing: <tag>.<key>"` when the key is absent everywhere.
  `FfcrmMessageSource` (bean `messageSource`) follows the MessageSource contract
  (`defaultMessage`/`NoSuchMessageException`, never the Rails string), and
  `FfcrmLocaleResolver` (bean `localeResolver`) resolves user preference →
  `Accept-Language` → `Setting[:locale]` → `en-US`.
- **Known i18n deltas:** gem-owned locales Spring intentionally does not ship
  (`simple_form.*`, `ransack.*`, `errors.*`, `activerecord.*`, `activemodel.*`,
  `helpers.*`, `number.*`, `date.*`, `datetime.*`, `time.*`, `support.*`,
  `will_paginate.*` where the app locale files don't define the key), the 6 pt-BR
  `pluralize.*` count=0 cases where rails-i18n puts 0 in `other` and CLDR/ICU puts it in
  `one`, and plural keys invoked with no args (Rails returns the raw hash; Spring keeps
  the ICU pattern).
- **Coverage:** `rake ffcrm:migration:settings_cf_coverage` scans `Setting[...]` /
  `Setting.<method>` / `cf_` sites into `docs/migration/settings-cf-coverage.md`
  (229 sites) classified by `settings-cf-coverage.rules.yml`; `CHECK=1` fails on
  unclassified or drift.
- **CI:** a `settings-i18n` job verifies generators are idempotent (zero git diff), runs
  the coverage CHECK, regenerates both Rails fixtures, and runs the four new specs.

ARB triggers: none expected — no new deployable, datastore, network or auth change; the
single new dependency is `com.ibm.icu:icu4j:77.1` (library, not vendor/integration).
Assessed against the epic's ARB trigger list for completeness.

### ARB triage

TO BE FILLED BY LEAD
