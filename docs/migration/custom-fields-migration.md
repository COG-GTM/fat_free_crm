# Dynamic Custom Fields (`cf_*` columns) — Analysis and Migration Plan

## How it works today

Fat Free CRM lets admins define custom fields at runtime without migrations or restarts.
The moving parts:

- **`fields` table** (metadata registry) with STI `type` column: `CoreField` rows describe
  built-in attributes; `CustomField` (and plugin subclasses such as `CustomFieldDatePair`)
  rows describe user-defined fields. Each row stores `name` (the physical column name),
  `label`, `as` (UI/field type key), validation settings, and `field_group_id`.
- **`field_groups`** groups fields per entity class (`klass_name` ∈ Account, Contact, Lead,
  Opportunity, Campaign, Task — every model that calls `has_fields`), optionally scoped
  to a tag.
- **`CustomField` callbacks** (`app/models/fields/custom_field.rb`):
  - `before_create :add_column` — executes a live DDL
    `ALTER TABLE <entity_table> ADD COLUMN <cf_name> <type>` via
    `klass.connection.add_column`, then `reset_column_information` and
    `serialize_custom_fields!`.
  - `after_validation :update_column, on: :update` — `change_column` only when the type
    transition is "safe" (`SAFE_DB_TRANSITIONS`: date↔time↔timestamp, integer↔float,
    string→text). Columns are **never renamed or dropped** (a rake task purges orphans).
  - Column names are generated from the label: `cf_` + snake-cased label, with numeric
    suffixes on collision (`cf_custom`, `cf_custom_2`, ...) — `generate_column_name`.
- **Type mapping** (`Field::BASE_FIELD_TYPES`, `app/models/fields/field.rb`):

  | `as` (field type) | DB column type | Notes |
  |---|---|---|
  | string, email, url, tel, select, radio_buttons | `string` | |
  | text | `text` | |
  | check_boxes | `text` | **YAML-serialized Array** (`serialize_custom_fields!`) |
  | boolean | `boolean` | |
  | date | `date` | |
  | datetime | `timestamp` | |
  | decimal | `decimal(15,2)` | |
  | integer | `integer` | |
  | float | `float` | |

  Plugins can register more types via `Field.register`.
- **Runtime resilience**: `lib/fat_free_crm/fields.rb` rescues `unknown attribute` /
  `NoMethodError` and calls `reset_column_information` — so a column added by one server
  process becomes usable in others without restart.

### Consequence for migration

**`db/schema.rb` does not describe production.** Every deployment's entity tables contain
an arbitrary, deployment-specific set of extra `cf_*` columns (possibly including orphaned
columns for deleted fields). Any Java/JPA schema generated from `schema.rb` will silently
drop this data unless the migration explicitly handles it. Static JPA entities cannot map
columns that differ per deployment.

## Target-state options for Spring Boot / JPA

### Option A — EAV table

`custom_field_values(id, entity_type, entity_id, field_id, string_value, text_value, number_value, date_value, datetime_value, boolean_value)`

- Pros: fully relational, static JPA mapping, per-value FK to field definitions.
- Cons: the FFCRM authors explicitly rejected EAV for performance ("filter and search
  across thousands of records with potentially hundreds of custom fields"); every entity
  fetch needs a join/second query; filtering on N fields = N joins; typed-column sprawl.

### Option B — JSONB column (recommended)

Add one `custom_fields jsonb NOT NULL DEFAULT '{}'` column to each `has_fields` table
(accounts, contacts, leads, opportunities, campaigns, tasks), keyed by field name:

```java
@Entity @Table(name = "contacts")
public class Contact {
    ...
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "custom_fields", columnDefinition = "jsonb")
    private Map<String, Object> customFields = new HashMap<>();
}
```

- Keep the `fields` / `field_groups` tables as the **metadata registry** (static JPA
  entities) driving UI rendering, validation (required/min/max/collection), and type
  coercion in a `CustomFieldService` — replacing `method_missing` magic with explicit
  map access.
- Adding a custom field becomes a pure metadata INSERT — **no runtime DDL**, which is the
  single biggest operational win (runtime `ALTER TABLE` takes locks and breaks with
  connection poolers, replicas, and strict-migration setups).
- PostgreSQL GIN index per table for filtering:
  `CREATE INDEX idx_contacts_custom_fields ON contacts USING gin (custom_fields jsonb_path_ops);`
  plus targeted expression indexes (e.g. `((custom_fields->>'cf_region'))`) for hot fields.
  This preserves the search performance rationale that motivated real columns in Rails.
- Typing: store JSON-native types (string/number/boolean/array) and ISO-8601 strings for
  date/datetime; the registry's `as` value drives coercion both ways.
- Check-box fields map naturally to JSON arrays (vs. YAML text today).

Recommendation: **Option B**. It matches the dynamic nature of the feature, avoids EAV
join explosion, avoids runtime DDL, and PostgreSQL JSONB + GIN keeps filtering fast.
(If the target DB were not PostgreSQL, reconsider: on e.g. SQL Server/Oracle use their
JSON support or fall back to Option A.)

## Data-migration plan

Because the `cf_*` column set is only knowable from the **live** database + `fields` rows,
the migration must be data-driven, not schema.rb-driven:

1. **Inventory (per deployment)**
   - `SELECT name, as, field_group_id FROM fields WHERE type != 'CoreField'` joined to
     `field_groups.klass_name` → authoritative list of (entity table, column, type).
   - Diff against `information_schema.columns` for each entity table to find **orphaned**
     `cf_*` columns (field deleted but column retained). Decide keep/drop per column with
     the customer; default: exclude orphans from migration but archive them.
2. **Schema prep (target)**
   - Create the JSONB `custom_fields` column on each entity table (or the EAV table if
     Option A was chosen), plus GIN indexes.
   - Migrate `fields` / `field_groups` as-is (they are static tables), converting the
     serialized `collection` (YAML Array) and `settings` (YAML Hash) columns to JSONB.
3. **Backfill**
   - For each entity table, one set-based UPDATE (or export/transform/load) building the
     JSON object from the inventoried columns, e.g.:

     ```sql
     UPDATE contacts SET custom_fields = jsonb_strip_nulls(jsonb_build_object(
       'cf_region', cf_region,
       'cf_renewal_date', to_char(cf_renewal_date, 'YYYY-MM-DD'),
       ...));
     ```

   - **check_boxes columns need YAML decoding** (they hold `---\n- A\n- B\n`): run this
     transform through a Ruby script (`rails runner`) or a YAML-aware ETL step that emits
     JSON arrays; plain SQL is not sufficient.
   - Validate: row counts, per-field non-null counts before/after, spot-check typed values.
4. **Coexistence / cutover**
   - If Rails and Spring must run side-by-side, keep writing the `cf_*` columns from Rails
     and treat JSONB as read-model until cutover (re-run backfill delta by `updated_at`),
     then flip writes to JSONB and freeze the Rails custom-field admin UI.
5. **Decommission**
   - After cutover + verification window, drop the physical `cf_*` columns and the
     runtime-DDL code path. `versions` rows still reference old attribute names in YAML —
     see the PaperTrail note in [data-model.md](data-model.md).

### Effort/risk notes

- Riskiest step is the YAML decode of check-box values and any plugin-registered field
  types (`Field.register`) present in the deployment — inventory those first.
- The migration script must be generated per environment (dev/staging/prod may have
  different `cf_*` sets).
- Ransack-based advanced search on custom fields must be reimplemented against JSONB
  (Spring Data JPA `Specification`s emitting `jsonb_extract_path_text` / `@>` predicates).
