# ADR 0004 — Build tool, language level, and platform floors

- **Status**: Accepted
- **Date**: 2026-08-21
- **Phase**: 0 (constrains Phase 1 track A1 and every phase after it)

## Decision

| Choice | Decision | Rationale |
|---|---|---|
| Build tool | **Gradle (Kotlin DSL)**, wrapper committed | Faster incremental builds and configuration cache on a module count that will grow through Phases 4–6; Spring Boot plugin support is equivalent to Maven's. Maven's declarative rigidity would be preferable for a single fixed-shape module, which this is not. |
| Java | **21 (LTS)**, toolchain pinned in the build | Records for DTOs, pattern matching, virtual threads available for the IMAP/scheduled work in H6d; supported by Spring Boot 3.x. Java 25 is deliberately not chosen so the platform is not on the newest release during a migration. |
| Framework | **Spring Boot 3.x** (latest patch at Phase 1 start), Jakarta namespace | Per `target-architecture.md`. |
| Database | **PostgreSQL 14 floor** | JSONB + GIN need ≥ 9.4 (ADR 0003); 14 is the realistic floor for a supported server and gives `jsonb_path_*` predicates for Phase 4 G4 search. SQLite (the repo's dev default) is **not** supported by the Java app. |
| Schema ownership | **Flyway**, baseline from a real deployment dump; Hibernate `ddl-auto: validate`, never `update` | Rails migrations remain the only schema writer until Phase 7; see ADR 0003 and the baseline runbook. |
| Persistence | Spring Data JPA + Hibernate, `Specification` composition | Phase 3 needs authorization and search to compose into one query. |
| Tests | JUnit 5 + Testcontainers-PostgreSQL; no H2 | The app depends on Postgres-specific behaviour (JSONB, `ILIKE`, arrays); an in-memory substitute would make the Phase 2 fidelity tests meaningless. |
| Static analysis | Checkstyle + SpotBugs in CI, build fails on violations | Cheap to adopt at skeleton time, expensive to retrofit across parallel Phase 4/5 branches. |
| Java package root | `com.fatfreecrm` with the layout in `target-architecture.md` §2.2 | — |

## Consequences

- Phase 1 A1 delivers the Gradle wrapper, the Java 21 toolchain declaration, the Flyway baseline,
  the Testcontainers harness, and CI wiring — and freezes them; parallel tracks from Phase 3
  onward must not change build conventions.
- Deployments still on PostgreSQL < 14, or on MySQL/SQLite, are out of scope. A pre-migration
  upgrade is a prerequisite, not part of the migration.
- The Rails app keeps `sqlite3` for local development; the two apps therefore cannot share a
  local development database unless the developer runs Postgres. Phase 1 should ship a compose
  file with Postgres and point `config/database.yml` at it for anyone working across both apps.
- Checkstyle/SpotBugs configuration lives in the repository root so all Phase 4/5 branches
  inherit identical rules.
