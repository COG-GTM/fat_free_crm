# Runbook — production schema baseline and `cf_*` column census

Phase 0 exit criterion: a Rails-migrated schema dump with the deployment's real `cf_*` columns and
a corresponding census are available for Flyway baselining. `db/schema.rb` is not sufficient:
`CustomField` adds columns at runtime, so each deployment can differ (see
[ADR 0003](decisions/0003-custom-field-storage.md)).

## 1. Census and dump a real production database

Run this read-only workflow against each environment that will be migrated. **Validate the
procedure against staging first.** The Rails app connects to the selected database to read
metadata and schema; the dump contains DDL only, not row data.

```bash
# Human-readable review report
RAILS_ENV=production bundle exec rake ffcrm:migration:column_census \
  FORMAT=markdown OUTPUT=tmp/cf-census-$(date -u +%Y%m%d).md

# Machine-readable report for downstream comparison
RAILS_ENV=production bundle exec rake ffcrm:migration:column_census \
  FORMAT=json OUTPUT=tmp/cf-census-$(date -u +%Y%m%d).json

# Disable per-table and per-column COUNT(*) queries for very large databases
RAILS_ENV=production bundle exec rake ffcrm:migration:column_census \
  COUNT_ROWS=false FORMAT=json OUTPUT=tmp/cf-census-no-counts.json

# Schema-only dump
RAILS_ENV=production bundle exec rake ffcrm:migration:baseline_dump \
  OUTPUT=tmp/schema-baseline.sql
```

The tasks use the selected Rails environment's configured connection. The PostgreSQL dumper
captures `pg_dump` stdout and supplies database host, port, username, and password from the active
connection configuration. `PG_DUMP_HOST` overrides the configured host. Override the executable
(or provide a Docker command) with `PG_DUMP`:

```bash
PG_DUMP='pg_dump --no-password' RAILS_ENV=production \
  bundle exec rake ffcrm:migration:baseline_dump OUTPUT=tmp/schema-baseline.sql

# Example when no compatible local pg_dump is installed
PG_DUMP='docker run --rm --network host -e PGPASSWORD postgres:16 pg_dump' \
  RAILS_ENV=production bundle exec rake ffcrm:migration:baseline_dump \
  OUTPUT=tmp/schema-baseline.sql
```

When using Dockerized `pg_dump`, the database must be reachable over TCP. If Rails connects through
a Unix socket, set `PG_DUMP_HOST` to a TCP-reachable address (for example, `127.0.0.1` when using
`--network host`).

`PG_DUMP` is split as shell-style arguments; it is not evaluated by a shell. Do not put a
production password in that value. The password travels to the child process as `PGPASSWORD`.
For a huge database, pass `COUNT_ROWS=false` to avoid the count queries. On non-PostgreSQL
adapters, `baseline_dump` falls back to `ActiveRecord::Tasks::DatabaseTasks.structure_dump`.

`FatFreeCRM::Migration::ColumnCensus` walks each model declaring `has_fields` (Account, Campaign,
Contact, Lead, Opportunity, Task), identifies physical `cf_*` columns, and cross-references the
`fields` metadata. Its statuses are:

| Status | Meaning | What Phase 1/7 must do |
|---|---|---|
| `mapped` | Column has a live `fields` row | Migrate data into `custom_fields` JSONB |
| `orphaned` | Column exists without a matching `fields` row | Archive, then drop; do not migrate |
| `missing_column` | Metadata exists but the column does not | Investigate before baselining |
| `unattached_field` | Metadata has no field group | Data-cleanup candidate |

The report also flags physical/metadata type mismatches (with `datetime` matching the Rails
`timestamp` mapping) and YAML-serialized `check_boxes` arrays. Compare each environment's census:
a column present in production but not staging makes the baseline deployment-specific.

The census records the maximum `schema_migrations.version`. If it does not match the deployed Rails
release, the dump may be from an environment mid-migration and should not be used as a baseline.
Re-run the census immediately before migration and freeze custom-field creation during the Phase 7
conversion window.

## 2. Rehearse with a production-shaped database

The repository rehearsal creates a local `postgres:16` container and rebuilds the database by
running the Rails migrations, not `schema:load`. It seeds an admin, all six `has_fields` entities,
custom fields covering the supported field types, and representative records. It deliberately
creates an orphaned column, a metadata-only missing column, a type mismatch, a YAML `check_boxes`
value, and an unattached field. It then asserts the physical schema and field metadata against the
shared manifest.

```bash
script/migration/build_production_shaped_db.sh
```

The command prints a password-redacted URL and a copy-pasteable `export PRODUCTION_SHAPED_DATABASE_URL=...`
line that expands `PRODUCTION_SHAPED_PASSWORD` (defaulting to the rehearsal password). The
container stays running for inspection. Remove it when finished with:

```bash
script/migration/build_production_shaped_db.sh --down
```

The generated committed artifacts are:

- `docs/migration/baseline/production-shaped-schema.sql` — schema-only PostgreSQL dump.
- `docs/migration/baseline/column-census.md` — review-friendly census.
- `docs/migration/baseline/column-census.json` — machine-readable census.

See [`baseline/README.md`](baseline/README.md) for the command, generation date, PostgreSQL
version, and schema migration version recorded for these artifacts.

## 3. Hand off the dump to Flyway

> **Warning — do not use the synthetic rehearsal schema as V1.** `production-shaped-schema.sql`
> is a rehearsal artifact with deliberate drift, including missing `leads.cf_partner_code`,
> type-mismatched `opportunities.cf_confidence_score`, and orphan columns. It must **not** be
> copied to `spring/src/main/resources/db/migration/V1__baseline_rails_schema.sql`. V1 must come
> from `ffcrm:migration:baseline_dump` against the target production database, after its census
> has been reviewed.

For Phase 1 A1, place the verified Rails-migrated dump in
`spring/src/main/resources/db/migration/V1__baseline_rails_schema.sql`. Use it as the baseline
migration; subsequent migrations are additive only.

1. Configure `flyway.baselineOnMigrate=true` and `baselineVersion=1`.
2. Set `spring.jpa.hibernate.ddl-auto=validate`; never use `update`.
3. Restore the dump in the PostgreSQL Testcontainers harness and run the Java suite. A validation
   failure usually indicates an unmapped column, often a deployment-specific `cf_*` column.
4. Preserve the schema dump with the matching JSON census in the migration artifact store. The
   Phase 7 conversion script depends on the exact column list.
