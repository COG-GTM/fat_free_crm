# Migration decision records

Phase 0 of the [Rails → Spring Boot migration plan](../migration-plan.md) records the decisions
that later phases depend on. Each record names its status, owner for any open sub-decision, and
how it can be revisited.

| ADR | Decision | Status | Phases it constrains |
|---|---|---|---|
| [0001](0001-api-response-format-scope.md) | Response-format scope for `/api/v1` (JSON first-class; CSV/XLS/vCard kept; XML/Atom/RSS deprecated) | Accepted — pending log confirmation | 4, 6 (H6b) |
| [0002](0002-auth-model-and-ui-ownership.md) | Stateless JWT access/refresh for `/api/v1`; Rails keeps Devise cookie sessions and serves UI | Accepted for API auth; whether the Rails UI survives past cutover pending product sign-off | 1 (C1), 7, 8 |
| [0003](0003-custom-field-storage.md) | `cf_*` dynamic columns → one `custom_fields jsonb NOT NULL DEFAULT '{}'` column per `has_fields` table | Accepted target; go/no-go deferred to AB-267 benchmark | 2 (AB-267), 4 (G4), 7 |
| [0004](0004-build-toolchain-and-platform-baselines.md) | Gradle Kotlin DSL, Java 21, Spring Boot 3.x, PostgreSQL 14 floor | Accepted | 1 (A1), all |
| [0005](0005-openapi-compatibility-baseline.md) | Freeze `openapi.yaml` at `openapi-baseline-v1` | Accepted | 2 (AB-267), 4, 5 |
| [0006](0006-arb-spring-boot-strangler-service.md) | ARB submission for the strangler Spring Boot service (rolls up 0001–0005) | Proposed | all |

**Status legend:** Accepted decisions are implementation inputs. Any pending confirmation or
sub-decision is explicitly called out above and in its ADR; it is not silently assumed.

## Supporting artifacts

- [`../api-format-inventory.md`](../api-format-inventory.md) — current-code response-format audit
  and reproducible controller grep results.
- [`../schema-baseline-runbook.md`](../schema-baseline-runbook.md) — production census, dump, and
  rehearsal instructions.
- [`../baseline/README.md`](../baseline/README.md) — committed schema/census artifacts and their
  provenance.
- [`../baseline/production-shaped-schema.sql`](../baseline/production-shaped-schema.sql) —
  PostgreSQL schema-only dump produced by Rails migrations.
- [`../baseline/column-census.md`](../baseline/column-census.md) and
  [`../baseline/column-census.json`](../baseline/column-census.json) — human- and
  machine-readable custom-field inventory.
- `rake ffcrm:migration:column_census` and `rake ffcrm:migration:baseline_dump`
  (`lib/tasks/ffcrm/migration.rake`) — census and schema-dump tasks.
