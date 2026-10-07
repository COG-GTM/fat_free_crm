# Custom fields: dual-read shim and type-enforcement design (AB-267)

- **Ticket**: [AB-267](https://cog-gtm.atlassian.net/browse/AB-267). Phase 2 design spike, part of epic AB-261.
- **Status**: Design proposal. Implementation belongs to G4 (custom fields JSONB + dual-read) and
  AB-265/AB-271 (entity mapping and API).
- **Companion**: [`custom-fields-jsonb-benchmark.md`](custom-fields-jsonb-benchmark.md) (numbers and the GO/NO-GO).
  Builds on [ADR 0003](../decisions/0003-custom-field-storage.md) and the
  [`cf_*` column census](../baseline/column-census.md).
- **Prototype code** (test source set only, nothing in `src/main`):
  `spring/src/test/java/com/fatfreecrm/spike/customfields/`
  (`CustomFieldsDualReader`, `CheckBoxesYamlCodec`, `CustomFieldTypeValidator`) and
  `spring/src/test/resources/spike/customfields/dual_read_trigger.sql` (`spike_sync_custom_fields()`
  trigger function and the `spike_yaml_string_array()` SQL YAML decoder).

## 1. Problem

Rails keeps creating and writing `cf_*` columns during the strangler period: `CustomField#add_column`
runs `ALTER TABLE <table> ADD COLUMN cf_<label>` at runtime, and Rails reads and writes those columns
through ActiveRecord. Java maps one `custom_fields jsonb` column per `has_fields` table (ADR 0003). Both
applications must therefore see the same custom-field values while both are live, without Java having
to know each deployment's physical `cf_*` set at compile time (`ddl-auto: validate` cannot map it).

## 2. Recommendation (summary)

| Concern | Decision |
|---|---|
| Source of truth during coexistence | **`cf_*` columns** (Rails is the writer). `custom_fields` is a derived copy. |
| How `custom_fields` is kept fresh | **`BEFORE INSERT OR UPDATE` row trigger** (`spike_sync_custom_fields(<klass>)`) plus a **one-off set-based backfill**. No periodic job. Java merge-on-read stays as the safety net. |
| Java read precedence | Physical `cf_*` column (even NULL) > `custom_fields` key > absent. Unknown keys dropped. READ-mode normalisation. |
| Java writes before cutover | **Write the `cf_*` columns** (Rails' representation, including YAML for check_boxes) and let the trigger derive JSONB. Java-only keys (no `cf_*` column) go straight to `custom_fields`. |
| Runtime `ADD COLUMN cf_*` | No trigger change needed; trigger iterates `to_jsonb(NEW)`; next write of the row picks up the column; optional backfill of the new key is a no-op because a new column is NULL everywhere. |
| check_boxes YAML | SQL decoder for the Psych-emitted simple sequences; `{"$yaml": "<raw>"}` marker for anything else, decoded by Java (`CheckBoxesYamlCodec`, SnakeYAML `SafeConstructor`). |
| Cutover / contract | Freeze Rails custom-field writes → final backfill + marker resolution → verify → switch reads to JSONB-only → drop trigger → archive and drop `cf_*` (separate, later migration). |

## 3. Precedence rules (Java read path)

Implemented by `CustomFieldsDualReader.read(cfColumns, customFieldsJsonb, fieldDefinitions)` and
unit-tested by `CustomFieldsDualReaderTest`. Inputs: the physically present `cf_*` columns for the row
(name → raw JDBC value), the `custom_fields` document, and the `fields` rows for the entity's klass.

For every field definition (iteration is metadata-driven, so orphans never leak):

1. **Column present** (the `cf_*` column exists on the table): the column wins, *including NULL*.
   NULL means "cleared by Rails" and the key is absent from the output, even when a stale JSONB value
   exists (`nullColumnWinsAndRemovesKey`). Rationale: during coexistence Rails is the writer; a JSONB
   value that disagrees with the column is by definition stale (trigger disabled, or a write that
   bypassed it).
2. **check_boxes column**: YAML text is decoded by `CheckBoxesYamlCodec` (`checkBoxesColumnIsYamlDecoded`).
3. **No column** (Java-only field, or a deployment where the column was never created): use the JSONB
   value (`jsonbOnlyFieldIsReadFromDocument`); a `{"$yaml": ...}` marker is decoded by the codec
   (`yamlMarkerInJsonbIsDecoded`).
4. **Not in `fields` metadata** (orphan `cf_*` columns, stale JSONB keys): dropped
   (`orphanKeysNotInMetadataAreDropped`). The census found two orphans in the production-shaped baseline.
5. Everything is normalised through `CustomFieldTypeValidator` in **READ** mode (see §8): stored data is
   never rejected; values that cannot be coerced are returned raw.

When the trigger is installed and has back-filled every row, rules 1 and 3 give the same answer, so
the steady-state Java read can be **JSONB-only** (no `cf_*` in the SELECT list, nothing deployment-
specific in the entity). The column-reading path is needed only (a) before the backfill finishes, and
(b) as a verification/diagnostic mode. Recommendation for G4: ship the JSONB-only read plus a
`CustomFieldConsistencyCheck` job/endpoint that runs rules 1–5 and reports drift; do not put
`cf_*` columns into the JPA entity.

## 4. Keeping `custom_fields` in step: options compared

| Option | Freshness | Cost | Runtime `ADD COLUMN cf_*` risk | Verdict |
|---|---|---|---|---|
| **A. Row trigger on `cf_*` writes** | Synchronous, same transaction | +1 `to_jsonb(NEW)` + one `fields` lookup per written row; see benchmark §writes (single-row insert/update latency roughly doubles in absolute terms, still sub-millisecond; ~2x on bulk backfill) | None: trigger iterates `jsonb_each(to_jsonb(NEW))`, so new columns are picked up without DDL on the trigger. `ALTER TABLE ADD COLUMN` (nullable, no default) is metadata-only and does not fire row triggers. | **Recommended** |
| B. Periodic backfill job | Eventually consistent (lag = job period) | Batch rewrite of changed rows; needs a change marker (`updated_at`), which Rails touches on every save anyway | Job must re-discover columns each run | Rejected: stale reads in Java API responses are visible contract-diff deltas; extra moving part |
| C. Java merge-on-read only (no JSONB writes until cutover) | Always fresh | Java SELECTs must include every `cf_*` column; JSONB GIN/expr indexes useless until cutover (JSONB is empty) | Java must reflect on `information_schema` at runtime and rebuild queries when a column appears | Rejected as the primary mechanism (kills the search/index benefit and forces dynamic SQL). Kept as the diagnostic path in §3. |

Trigger details (`dual_read_trigger.sql`, tested by `CustomFieldsSyncTriggerTest`):

- `CREATE TRIGGER ... BEFORE INSERT OR UPDATE ON accounts FOR EACH ROW EXECUTE FUNCTION
  spike_sync_custom_fields('Account')`; the argument is the `field_groups.klass_name`.
- Every `cf_` key present in `NEW` is first removed from the document, then non-null values are
  re-added: `NEW.custom_fields := (coalesce(NEW.custom_fields,'{}') - <cf_ names>) || <non-null cf values>`.
  Therefore a NULL column removes its key, and **Java-only keys survive** (`updateNullAndJavaOnlyKeySemantics`).
- check_boxes columns (from `fields."as" = 'check_boxes'` for the klass) are decoded by
  `spike_yaml_string_array()`. If it returns NULL (anything other than a simple Psych block sequence of
  scalars, e.g. the `|-` multi-line block scalar), the value is stored as `{"$yaml": "<raw>"}` and decoded
  in Java (`psychFixturesDecodeOrProduceMarker`: 8 of 9 Psych fixtures decode in SQL, 1 marker).
  Note: the SQL decoder assumes Psych-emitted YAML — Rails writes check_boxes via
  `serialize(type: Array)`/Psych, which quotes ambiguous scalars (`'true'`, `'123'`, `'2024-01-01'`),
  so plain scalars are literal strings. Hand-edited or imported rows may not be canonical; G4's
  pre-cutover verification should therefore compare the SQL decode against Psych `YAML.safe_load` for
  every check_boxes row and turn mismatches into `$yaml` markers.
- Values are taken from `to_jsonb(NEW)`, so types follow PostgreSQL's canonical JSON rendering:
  numeric → JSON number (scale is **not** preserved: `1234.50` becomes `1234.5`), date → `"YYYY-MM-DD"`,
  timestamp → `"YYYY-MM-DDTHH:MI:SS[.ffffff]"` (no offset; Rails stores UTC), boolean → JSON boolean.
- Orphan columns are copied too (the trigger has no reason to consult metadata for non-check_boxes
  columns). Filtering is a read-time concern (§3 rule 4); this keeps the trigger cheap and means an
  orphan that is later re-attached to a field needs no backfill.
- Production hardening for G4 (not done in the spike): cache the check_boxes name list per klass instead
  of querying `fields` per row (or regenerate a per-table function when `fields` changes); guard
  `pg_trigger_depth()`; use `IS DISTINCT FROM` to skip rewriting `custom_fields` when nothing changed,
  which also avoids TOAST churn on wide documents.

### Backfill

- **Initial**: one set-based `UPDATE <table> SET custom_fields = (coalesce(custom_fields,'{}') - <every
  cf_ column>) || jsonb_strip_nulls(jsonb_build_object(...))` generated from the census columns
  (`CustomFieldsBackfill.setBasedExpression()`), batched by `id`
  range in production (e.g. 10k rows per transaction) to keep lock time and WAL bursts small. The
  subtraction form keeps Java-only keys (no `cf_` column), and check_boxes values fall back to the
  `{"$yaml": ...}` marker when the SQL decoder cannot handle them — the same merge semantics as the
  trigger, which the benchmark verifies against (`setBasedBackfillIsLossless` also asserts Java-only
  key preservation, marker fallback and trigger equality). Measured
  at about half the cost of a no-op `UPDATE ... SET custom_fields = custom_fields` through the trigger
  (benchmark `writes.csv`, `backfill_set_based` vs `backfill_via_trigger_noop_update`), and both produce
  identical documents (asserted by the benchmark).
- Order: install the trigger first, then backfill. Rows written by Rails during the backfill are covered
  by the trigger, rows not yet visited by the backfill are covered by it later; nothing falls through.
- `$yaml` markers left by the SQL decoder are resolved by a Java batch job (decode with
  `CheckBoxesYamlCodec`, write the JSON array) before cutover. Expect very few (only non-trivial strings).

## 5. Rails adds a custom field at runtime

1. Admin creates a field → Rails runs `ALTER TABLE accounts ADD COLUMN cf_new <type>` (nullable, no
   default: catalog-only, does not rewrite rows, does not fire the trigger) and inserts the `fields` row.
2. Trigger needs no change. Existing rows have `cf_new IS NULL`, which is exactly "key absent" in JSONB,
   so no backfill is needed (`runtimeAddedColumnAndOrphanAndBackfill`).
3. The first Rails write of `cf_new` on a row is mirrored by the trigger.
4. Java learns the field from `fields` (metadata cache keyed by `fields.updated_at`/max id; a short TTL
   is enough). Until it refreshes, the new key is simply not returned: rule 4 drops keys not in the
   cached metadata. No Java DDL, no restart, `ddl-auto: validate` unaffected because the entity never maps
   `cf_*`.
5. If the new field is `check_boxes`, the trigger's per-row `fields` lookup sees it immediately.
6. Type changes (`CustomField#update_column`, only `SAFE_DB_TRANSITIONS`: date↔timestamp,
   integer↔float, string→text) do not rewrite JSONB. READ normalisation handles the old representation
   (e.g. a date field reading a stored datetime string truncates it; an integer field reading `3.0`).
   Rails' own type change rewrites the column; a follow-up no-op UPDATE re-derives JSONB if needed.

Risk accepted: `ALTER TABLE ... ADD COLUMN` takes an `ACCESS EXCLUSIVE` lock briefly; this exists today
and is unaffected by the trigger.

## 6. Java writes before cutover (consistency with Rails reads)

**Write-through to `cf_*`.** For fields that have a physical column, Java writes the column (and lets
the trigger derive JSONB). This is the only option under which Rails keeps reading correct values
without any Rails change:

- Java validates in WRITE mode (`CustomFieldTypeValidator`), then converts the normalised value to the
  Rails column representation: check_boxes → Psych-compatible YAML (`CheckBoxesYamlCodec.encode`, round-
  trip tested against the Psych fixtures); datetime → UTC `timestamp`; decimal → `numeric(15,2)`.
- Column names come from `fields.name`, validated against `^cf_[a-z0-9_]+$` and the live
  `information_schema.columns` set before being spliced into SQL (the only dynamic SQL in the design).
- A single `UPDATE accounts SET cf_a = ?, cf_b = ?, updated_at = now() WHERE id = ? AND lock_version...`
  per save. Rails does not use optimistic locking on these tables, so last-writer-wins applies to both
  apps, exactly as Rails-vs-Rails today.
- Fields without a column (Java-only fields created after Java owns field admin) are written to
  `custom_fields` with `jsonb_set`/`||` on the specific key, never by rewriting the whole document, to
  avoid clobbering a concurrent trigger-derived value. Note from the JPA spike: both mapping variants
  dirty-check in-place map mutation and rewrite the **whole** document on flush; G4 should therefore not
  let JPA flush `custom_fields` on entities Rails can concurrently write (`@Column(updatable = false)`
  on the JSONB attribute plus explicit key-level updates).

Rejected: "Java read-only for custom fields until cutover" (blocks AB-271 write endpoints for custom
fields for the whole coexistence period) and "write both representations from Java" (duplicates the
trigger's logic in Java and races with it).

## 7. Cutover / contract step (Phase 7)

1. **Freeze**: Rails custom-field admin disabled (no new fields), Rails entity writes routed to Java or
   paused for the window.
2. **Final sync**: run the set-based backfill once more; resolve every `$yaml` marker in Java; assert
   zero markers.
3. **Verify**: for each table, `SELECT count(*) WHERE custom_fields IS DISTINCT FROM <set-based expr>` = 0
   and the Java consistency check (§3) reports no drift; record counts per key.
4. **Switch reads**: Java reads JSONB-only (already the steady state), disable the column-reading path.
5. **Drop the trigger** and `spike_yaml_string_array` (renamed in production).
6. **Contract migration (separate Flyway version, after a soak period)**: archive orphaned and mapped
   `cf_*` columns (`CREATE TABLE cf_archive_<table> AS SELECT id, cf_... FROM <table>`), then
   `ALTER TABLE <table> DROP COLUMN cf_...` per census column. Re-run the census first; a deployment-
   specific column that is not in `fields` is archived, not migrated.
7. Rails is no longer able to read custom fields after step 6; this must be after Rails UI ownership is
   settled (open product decision in the epic).

## 8. Type-enforcement rules driven by `fields`

Prototype: `CustomFieldTypeValidator.validate(List<FieldDefinition>, Map<String,Object>, Mode)` →
`ValidationResult(normalized, errors)`; `FieldDefinition` mirrors a `fields` row (`name`, `label`, `as`,
`required`, `minlength`, `maxlength`, `collection`, `id`, `pair_id`). 22 unit tests in
`CustomFieldTypeValidatorTest` cover every `as` type. Error map shape is the epic's 422 contract
`{"errors": {"cf_x": ["..."]}}`.

**Modes.** `WRITE` (API input): full validation, errors returned, unknown keys rejected. `READ` (stored
data, dual-read): normalise only; no required/length/collection errors; unknown keys dropped; a value
that cannot be coerced is returned raw instead of failing the read.

| `as` | Rails column | JSON representation | WRITE rules (beyond required/min/maxlength) | READ normalisation |
|---|---|---|---|---|
| `string`, `email`, `url`, `tel` | `varchar` | string | must be a JSON string. No format validation (Rails has none; the HTML input type is client-side only). Lengths count code points. | as-is |
| `text` | `text` | string | as string | as-is |
| `select` | `varchar` | string | must be in `fields.collection` (when collection is non-empty) | membership not checked (options change over time) |
| `radio_buttons` | `varchar` | string | same as select | same as select |
| `check_boxes` | `text`, YAML `Array` | array of strings | scalar is wrapped into a 1-element array; blanks removed; duplicates removed, order preserved; every element in `collection` | same normalisation; membership not checked; YAML decoded upstream |
| `boolean` | `boolean` | boolean | `true`/`false`, or `"1"/"0"/"true"/"false"/"t"/"f"` (case-insensitive) | same |
| `date` | `date` | `"YYYY-MM-DD"` | ISO local date only | also accepts an ISO datetime and truncates (former `timestamp` column, `SAFE_DB_TRANSITIONS`) |
| `datetime` | `timestamp` (UTC) | `"YYYY-MM-DDTHH:MM:SS[.ffffff]Z"` | ISO-8601; offset converted to UTC; no offset = UTC (Rails `timestamp without time zone` holds UTC) | also accepts a bare date as midnight UTC |
| `decimal` | `numeric(15,2)` | JSON number | number or numeric string; HALF_UP to scale 2 (PostgreSQL rounding); integer part ≤ 13 digits | same, raw on failure |
| `integer` | `integer` | JSON number | int32 range; fractional values rejected (stricter than Rails, which truncates; documented delta) | same, raw on failure |
| `float` | `double precision` | JSON number | finite only (NaN/±Infinity rejected; JSON cannot carry them) | same |
| `date_pair` / `datetime_pair` | two `date`/`timestamp` columns | two keys, as date/datetime | each half as above; end before start → error on the **end** field (Rails `CustomFieldDatePair#custom_validator`), equal is OK; the end half inherits `required` from the start half | as date/datetime, no pair check |

Common rules (all types), matching `CustomField#custom_validator`:

- **required**: Rails `blank?` semantics: `null`, `""`, whitespace-only, `[]` and **`false`** are blank.
  A required boolean therefore cannot be saved as `false`. Kept for contract parity (test
  `requiredBooleanCannotBeFalse`); flag as a candidate allow-listed delta if product wants to fix it.
- **minlength / maxlength**: applied to string values only when `> 0` (Rails: `minlength.to_i > 0`).
  Rails counts characters with `String#length` (code points), the prototype does the same.
- Absent key on WRITE is treated like `null` (required check fires). Absent key on READ stays absent.
- Error messages follow the Rails i18n keys (`activerecord.errors.models.custom_field.*`); exact wording
  is owned by AB-271's error contract.

Representation choices to carry into G4:

- Store JSON numbers, not strings, for decimal/integer/float so `jsonb_path` comparisons and
  `(x)::numeric` expression indexes work. Decimal scale is not preserved by `jsonb` (`1234.50` →
  `1234.5`); the API layer formats decimals to 2 places on output.
- Store dates/datetimes as ISO strings: lexicographic order equals chronological order, so the
  `->>` btree expression index serves date range and sort queries (benchmark `date_range`).
