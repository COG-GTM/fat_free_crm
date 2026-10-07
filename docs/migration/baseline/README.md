# Production-shaped baseline artifacts

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
