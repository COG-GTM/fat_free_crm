# ADR 0004 — Build tool, language level, and platform floors

- **Status**: Accepted
- **Date**: 2026-10-07
- **Phase**: 0 (AB-262; constrains Phase 1 track A1 and every phase after it)

## Decision

| Choice | Decision | Rationale |
|---|---|---|
| Build tool | **Gradle Kotlin DSL**, with the Gradle wrapper committed | Reproducible builds without requiring a system Gradle install |
| Java | **Java 21**, toolchain pinned in the build | LTS baseline for the migration |
| Framework | **Latest stable Spring Boot 3.x** at implementation time; Jakarta namespace | Per `target-architecture.md` |
| Java package root | **`com.fatfreecrm`** | Shared package root for the module |
| Module layout | One Spring module in top-level **`spring/`** | Keeps the service and Flyway baseline under one review |
| Layering | `api/`, `api/dto/`, `service/`, `repository/`, `domain/`, `security/`, and `config/`, as in [`target-architecture.md` §2.2](../target-architecture.md#22-package-layout) | Keeps HTTP, business, persistence, and security responsibilities separate |
| Dependency direction | **Controller → Service → Repository only** | Controllers do not call repositories directly |
| Database | **PostgreSQL 14 minimum**; `postgres:16` for tests and CI | PostgreSQL-specific behavior includes JSONB and GIN |
| Schema ownership | **Flyway** owns `spring/src/main/resources/db/migration` | Rails migrations continue as the source of the baseline schema |
| Baseline | `V1__baseline_rails_schema.sql` is sourced from a Rails-migrated schema dump per the runbook and `docs/migration/baseline/production-shaped-schema.sql` | Captures the real Rails schema, including deployment-specific columns |
| Later migrations | Additive only | The initial baseline is immutable once consumers use it |
| Hibernate | `spring.jpa.hibernate.ddl-auto=validate`, never `update` | Detects drift without allowing Hibernate to mutate schema |
| Tests | JUnit 5 and Testcontainers PostgreSQL; no H2 | Tests must exercise PostgreSQL behavior |
| Integration-test base | `com.fatfreecrm.support.AbstractPostgresIntegrationTest` | Shared PostgreSQL Testcontainers setup |
| Static analysis | Checkstyle and SpotBugs are part of `./gradlew build` | Build fails on violations |

## Consequences

- Phase 1 A1 delivers the Gradle wrapper, Java 21 toolchain, Flyway baseline, PostgreSQL test
  harness, and CI wiring. Later phases preserve these conventions.
- Deployments on PostgreSQL older than 14, MySQL, or SQLite are out of scope for the Spring
  application; any upgrade is a prerequisite, not part of this migration.
- Rails may keep its SQLite development default, while Spring tests use PostgreSQL. Local
  cross-application development therefore requires PostgreSQL.
- Checkstyle and SpotBugs configuration lives in the `spring/` module and applies consistently to
  subsequent work.
