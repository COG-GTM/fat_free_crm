# ADR 0003 — Custom-field storage target: one `jsonb` column per table

- **Status**: Accepted (go/no-go re-confirmed by the Phase 2 G2 benchmark)
- **Date**: 2026-08-21
- **Phase**: 0 (constrains Phase 2 G2, Phase 4 G4, Phase 7 conversion)

## Context

`CustomField` runs live DDL: creating a field executes `ALTER TABLE <entity> ADD COLUMN cf_<label> <type>`,
and columns are never dropped when the field is deleted. Consequently `db/schema.rb` does not
describe any real deployment, and each installation carries its own `cf_*` set plus orphans.
Static JPA entities cannot map per-deployment columns. Options were analysed in
[`../custom-fields-migration.md`](../custom-fields-migration.md): EAV table (Option A) vs. a
single JSONB column per table (Option B).

## Decision

Adopt **Option B**: add `custom_fields jsonb NOT NULL DEFAULT '{}'` to each `has_fields` table
(`accounts`, `campaigns`, `contacts`, `leads`, `opportunities`, `tasks`), keyed by the existing
field name. `fields` / `field_groups` remain the metadata registry and drive validation and type
coercion in a `CustomFieldService`, replacing the `method_missing` magic in
`lib/fat_free_crm/fields.rb`.

Migration shape while Rails is live:

1. **Additive migration** adds the `custom_fields` column; Rails is untouched and keeps writing
   `cf_*`.
2. **Dual-read shim**: Java reads `custom_fields` first, falling back to the `cf_*` column
   census (below) for keys not yet migrated. Java writes both until Rails stops writing.
3. **Final conversion** in Phase 7 maintenance window: copy `cf_*` → `custom_fields`, convert
   YAML-serialized `check_boxes` arrays to JSON arrays, verify row counts, then drop the shim.
   Orphaned `cf_*` columns are *not* migrated — they are dumped to an archive table first, so the
   drop is reversible.
4. No `cf_*` column is dropped before the conversion is verified.

GIN index on each `custom_fields` column; typed access enforced from `fields.as` rather than
from the physical column type.

## Why not EAV

The FFCRM authors rejected EAV explicitly for filter/search performance, and it is worse here:
every entity fetch needs a join, and filtering on N fields needs N joins — directly against the
Phase 3 requirement that authorization and search compose into a *single* SQL query with correct
pagination totals.

## Consequences

- Phase 2 G2 must produce the benchmark that confirms this (JSONB-path filter vs. native column
  on a realistic row count) plus the type-enforcement rules. A no-go there reopens this ADR.
- `check_boxes` fields are YAML-serialized Ruby arrays in `text` columns today; they need a
  one-time Ruby-side conversion (same script family as the `settings.value` conversion in H6c),
  because Java should not parse Ruby YAML.
- Ransack search over custom fields becomes JSONB-path search (Phase 4 G4), and the
  `ransack_column_select_options` behaviour must be reproduced from `fields.collection`.
- The CSV export reflects over all columns, so it currently includes `cf_*` automatically. Once
  values live in `custom_fields`, H6b must add them explicitly or the export silently loses
  columns.
- Requires PostgreSQL ≥ 9.4 for JSONB and GIN — satisfied by the ADR 0004 floor of 14.
- Phase 1's Flyway baseline must be taken from a **real** deployment dump, not `schema.rb`, or
  `ddl-auto: validate` will fail on the extra `cf_*` columns. See
  [`../schema-baseline-runbook.md`](../schema-baseline-runbook.md).
