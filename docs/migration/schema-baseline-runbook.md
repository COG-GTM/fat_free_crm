# Runbook — production schema baseline and `cf_*` column census

Phase 0 exit criterion: *a real production-shaped schema dump (with `cf_*` columns) available for
Flyway baselining*. `db/schema.rb` cannot be used — `CustomField` adds columns at runtime, so
every deployment's entity tables differ (see [ADR 0003](decisions/0003-custom-field-storage.md)).
Run this against each environment that will be migrated, staging first.

Both steps are read-only.

## 1. Census the dynamic columns

```bash
# Human-readable, for review
RAILS_ENV=production bundle exec rake ffcrm:migration:column_census \
  OUTPUT=tmp/cf-census-$(date -u +%Y%m%d).md

# Machine-readable, for the Phase 2 fidelity tests and the Phase 7 conversion script
RAILS_ENV=production bundle exec rake ffcrm:migration:column_census \
  FORMAT=json OUTPUT=tmp/cf-census-$(date -u +%Y%m%d).json

# Skip the per-column COUNT(*) queries on a very large database
RAILS_ENV=production bundle exec rake ffcrm:migration:column_census COUNT_ROWS=false
```

Implementation: `FatFreeCRM::Migration::ColumnCensus` (`lib/fat_free_crm/migration/column_census.rb`).
It walks every model that declares `has_fields` (Account, Campaign, Contact, Lead, Opportunity,
Task), lists each table's `cf_*` columns, and cross-references them against the `fields`
metadata registry.

### Reading the report

| Status | Meaning | What Phase 1/7 must do |
|---|---|---|
| `mapped` | Column has a live `fields` row | Migrate the data into `custom_fields` JSONB |
| `orphaned` | Column exists, no `fields` row — a deleted field's data | Archive, then drop. **Not** migrated |
| `missing_column` | `fields` row with no column — metadata created against a different database | Investigate before baselining; Rails would ALTER the table on first write |
| `unattached_field` | `fields` row with no field group, so it belongs to no entity | Data-cleanup candidate |

Two flags matter as much as the statuses:

- **`type mismatch`** — the physical column type no longer matches `fields.as`, a legacy of the
  `SAFE_DB_TRANSITIONS` type-change path. JPA type mapping must follow the *column*, while
  validation follows the *metadata*.
- **`YAML`** — a `check_boxes` field whose `text` column holds a YAML-serialized Ruby array.
  These need the one-time YAML→JSON conversion, not a straight copy.

Also compare the census across environments: a column present in production but not in staging
means the Flyway baseline is environment-specific, and the Phase 1 `ddl-auto: validate` run has
to be repeated per environment.

## 2. Dump the schema for Flyway

```bash
RAILS_ENV=production bundle exec rake ffcrm:migration:baseline_dump \
  OUTPUT=tmp/schema-baseline.sql
```

On PostgreSQL this shells out to `pg_dump --schema-only --no-owner --no-privileges` using the
credentials from `config/database.yml`; on other adapters it falls back to the Rails structure
dump. Only DDL is emitted — no row data leaves the environment.

Hand-off to Phase 1 (A1):

1. Place the dump as the Flyway baseline (`V1__baseline.sql`) with
   `flyway.baselineOnMigrate=true` and `baselineVersion=1`.
2. Set `spring.jpa.hibernate.ddl-auto=validate` and run the Java test suite against a
   Testcontainers-Postgres restored from the dump; a validation failure means an unmapped
   column, usually a `cf_*` one.
3. Keep the dump and the JSON census together in the migration artifact store — the Phase 7
   conversion script needs the exact column list it was written against, and re-running the
   census immediately before the maintenance window is the check that nothing was added since.

## Caveats

- Custom fields can be created at any time by an admin, so a census is a snapshot. Re-run it at
  the start of every phase that touches custom fields, and freeze field creation for the Phase 7
  window.
- The census reads `fields` through ActiveRecord, so it must run with the application's own
  environment, not a bare `psql` session.
- `schema_migrations.version` is recorded in the report; if it does not match the deployed Rails
  release, the dump is from an environment mid-migration and should not be used as the baseline.
