# Production-shaped baseline artifacts

> **Warning — do not use the synthetic rehearsal schema as V1.**
> `production-shaped-schema.sql` is a rehearsal artifact with deliberate drift: missing
> `leads.cf_partner_code`, type-mismatched `opportunities.cf_confidence_score`, and orphan
> columns. It must **not** be copied to
> `spring/src/main/resources/db/migration/V1__baseline_rails_schema.sql`. V1 must come from
> `ffcrm:migration:baseline_dump` against the target production database, after its census has
> been reviewed.

These artifacts were generated on 2026-10-07 by running:

```bash
script/migration/build_production_shaped_db.sh
```

The rehearsal database used PostgreSQL 16.15 (Debian 16.15-1.pgdg13+2). Its
maximum `schema_migrations.version` was `20260413041448`.

- `production-shaped-schema.sql` is the schema produced by Rails migrations
  and captured with the `ffcrm:migration:baseline_dump` task.
- `column-census.json` is the machine-readable census, including row counts.
- `column-census.md` is the Markdown rendering of the same census.

The census and dump are generated after the production-shaped seed has created
custom fields and representative records, including deliberate drift cases.
