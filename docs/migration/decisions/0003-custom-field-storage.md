# ADR 0003 — Custom-field storage target: one `jsonb` column per table

- **Status**: Accepted (target); final go/no-go deferred to AB-267's benchmark
- **Date**: 2026-10-07
- **Phase**: 0 (AB-262; constrains Phase 2 benchmark AB-267, Phase 4 G4, Phase 7 conversion)

## Context

`CustomField` runs live DDL: creating a field executes `ALTER TABLE <entity> ADD COLUMN cf_<label>
<type>`, and columns are not dropped when the field is deleted. Consequently, `db/schema.rb` does
not describe every deployment, and each installation carries its own `cf_*` set plus orphans.
Static JPA entities cannot map per-deployment columns. Options were analysed in
[`../custom-fields-migration.md`](../custom-fields-migration.md): EAV table (Option A) versus a
single JSONB column per table (Option B).

The committed [`../baseline/column-census.md`](../baseline/column-census.md) and its JSON
companion record the production-shaped custom-column inventory used to inform the baseline.

## Decision

Adopt **Option B as the target**: add `custom_fields jsonb NOT NULL DEFAULT '{}'` to each
`has_fields` table (`accounts`, `campaigns`, `contacts`, `leads`, `opportunities`, `tasks`), keyed
by the existing field name. `fields` / `field_groups` remain the metadata registry and drive
validation and type coercion in a `CustomFieldService`, replacing the `method_missing` magic in
`lib/fat_free_crm/fields.rb`.

Migration shape while Rails is live:

1. **Additive migration** adds the `custom_fields` column; Rails is untouched and keeps writing
   `cf_*`.
2. **Dual-read shim**: Java reads `custom_fields` first, falling back to the `cf_*` column census
   for keys not yet migrated. Java writes both until Rails stops writing.
3. **Final conversion** in the Phase 7 maintenance window: copy `cf_*` to `custom_fields`, convert
   YAML-serialized `check_boxes` arrays to JSON arrays, verify row counts, then drop the shim.
   Orphaned `cf_*` columns are **archived** first and are not migrated.
4. No `cf_*` column is dropped before conversion is verified.

Add a GIN index on each `custom_fields` column; typed access follows `fields.as`, not the physical
column type.

## Why not EAV

The FFCRM authors rejected EAV explicitly for filter/search performance. Every entity fetch needs
a join, and filtering on N fields needs N joins — directly against the Phase 3 requirement that
authorization and search compose into a single SQL query with correct pagination totals.

## Consequences

- **AB-267 is the Phase 2 benchmark.** It must evaluate JSONB-path filtering against native-column
  filtering on realistic row counts and assess the type-enforcement rules. A no-go reopens this
  ADR; the accepted target is not final go-live authorization.
- `check_boxes` fields are YAML-serialized Ruby arrays in `text` columns today; they need a
  one-time Ruby-side YAML-to-JSON conversion.
- Ransack search over custom fields becomes JSONB-path search (Phase 4 G4), and the
  `ransack_column_select_options` behavior must be reproduced from `fields.collection`.
- CSV export reflects over all columns, so it currently includes `cf_*` automatically. H6b must
  add them explicitly after values move to `custom_fields`.
- PostgreSQL 14 is the minimum supported platform for JSONB and GIN (ADR 0004).
- Phase 1's Flyway baseline must come from a real deployment dump, not `schema.rb`, or
  `ddl-auto: validate` will fail on deployment-specific columns. See the
  [`schema-baseline-runbook`](../schema-baseline-runbook.md).
