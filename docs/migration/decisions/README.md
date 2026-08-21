# Migration decision records

Phase 0 of the [Rails → Spring Boot migration plan](../migration-plan.md) exists to close the
decisions that every later phase forks from. Each record is small on purpose: context, the
decision, what it costs elsewhere, and how it can be revisited.

| ADR | Decision | Status | Phases it constrains |
|---|---|---|---|
| [0001](0001-api-response-format-scope.md) | Response-format scope for `/api/v1` (JSON kept, XML/Atom/RSS deprecated, CSV/XLS/vCard kept) | Accepted, pending log confirmation | 4, 6 (H6b) |
| [0002](0002-auth-model-and-ui-ownership.md) | JWT for `/api/v1` from Phase 1; Rails keeps its own cookie sessions and the UI | Accepted (API auth); UI ownership **pending product sign-off** | 1 (C1), 7 (C7), 8 |
| [0003](0003-custom-field-storage.md) | `cf_*` dynamic columns → single `custom_fields jsonb` per table | Accepted | 2 (G2), 4 (G4), 7 |
| [0004](0004-build-toolchain-and-platform-baselines.md) | Gradle + Java 21 + Spring Boot 3.x, PostgreSQL 14 floor | Accepted | 1 (A1), all |
| [0005](0005-openapi-compatibility-baseline.md) | `openapi.yaml` frozen at tag `openapi-baseline-v1` as the compatibility contract | Accepted | 2 (A2), 4, 5 |

Status legend: **Accepted** — implement against it; **Proposed** — implement nothing that
depends on the choice until signed off; **Superseded** — replaced by a later ADR, kept for
history.

## Conventions

- One decision per file, numbered sequentially, never rewritten in place: a reversal is a new
  ADR that marks the old one superseded.
- "Consequences" must name the phase and track that pays the cost, so the plan's dependency
  graph stays honest.
- Anything that needs data we do not have yet (production logs, a product decision) says so
  explicitly and names the owner, rather than being silently assumed.

## Supporting artifacts produced in Phase 0

- [`../api-format-inventory.md`](../api-format-inventory.md) — per-format audit of what the
  Rails app actually serves, feeding ADR 0001.
- [`../schema-baseline-runbook.md`](../schema-baseline-runbook.md) — how to produce the
  production-shaped schema dump and `cf_*` census that Phase 1's Flyway baseline needs.
- `rake ffcrm:migration:column_census` / `rake ffcrm:migration:baseline_dump`
  (`lib/tasks/ffcrm/migration.rake`) — the census and dump tooling itself.
