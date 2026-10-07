# Custom fields: `cf_*` columns vs `custom_fields jsonb` benchmark (AB-267)

- **Ticket**: [AB-267](https://cog-gtm.atlassian.net/browse/AB-267). Phase 2 design spike, part of epic AB-261.
- **Decision under test**: [ADR 0003](../decisions/0003-custom-field-storage.md) (one `custom_fields jsonb`
  column per `has_fields` table, final go/no-go deferred to this spike).
- **Companion**: [`custom-fields-dual-read-design.md`](custom-fields-dual-read-design.md) (dual-read shim,
  backfill and type-enforcement rules).
- **Raw results**: [`custom-fields-jsonb-benchmark/`](custom-fields-jsonb-benchmark/): `queries.csv`,
  `sizes.csv`, `writes.csv`, `environment.json`, `plans-<N>.txt` (every `EXPLAIN (ANALYZE, BUFFERS)`).
- **Harness**: Removed by AB-271 after promotion; the committed benchmark results remain below.

## Recommendation: **GO** (with conditions)

JSONB is a safe target for custom fields. With the right index per access pattern, every paginated
list query (the shape the API serves: filter + `ORDER BY` + `LIMIT 25`) stays at or below **2 ms p50 at
1M rows**, on par with `cf_*` columns. Multi-value (`check_boxes`) filtering is **faster and correct**
on JSONB, while the `cf_*` YAML text can only be searched with a fragile `LIKE`. Single-row write cost of
a JSONB-only table matches `cf_*` within noise.

The price is paid on **large-result `count(*)` queries** (3–12x slower than `cf_*` at 1M rows, because a
plain column btree allows index-only scans and an expression index over `jsonb` does not), **storage**
(JSONB-only heap 1.65x the `cf_*` heap; GIN `jsonb_path_ops` index about 210 MB per 1M rows), and the
**coexistence trigger** (about 2x per-row write latency, +0.1 ms absolute, while both representations
exist).

Conditions that G4 (implementation) and AB-269 (search) must meet:

1. **Index per access pattern, not one GIN for everything.**
   - `GIN (custom_fields jsonb_path_ops)` per table: serves `@>` equality and `check_boxes`
     containment (and `@?` equality paths). Prefer it over default `jsonb_ops`: 36% smaller
     (210 MB vs 328 MB at 1M), builds in 60% of the time, and the same or faster for every containment query.
   - **btree expression indexes** for fields that are range-filtered or sorted:
     `((custom_fields->>'cf_x')::numeric)` for decimal/integer/float, `((custom_fields->>'cf_x'))` for
     date/datetime (ISO strings sort chronologically) and for high-traffic select/string equality. Created
     per field on demand (admin marks a field "filterable/sortable"), each costs the same as the
     equivalent `cf_*` btree (identical sizes in `sizes.csv`).
   - Do **not** rely on `jsonb_ops` for `?` key existence: it is used, but at 1M rows it is slower than
     a sequential scan (126 ms vs 100 ms) for a 35%-selective key. Use `(custom_fields->>'cf_x') IS NOT
     NULL` with the expression index, or a partial index, when key-exists filters matter.
2. **Query builder must emit operator forms that match the indexes exactly.**
   `custom_fields @> :json::jsonb`, `custom_fields @? :path::jsonpath` (equality/membership paths only), and
   `(custom_fields->>'cf_x')::numeric BETWEEN ...` with the **same expression** as the index. Never emit
   `jsonb_contains(...)` / `jsonb_path_exists(...)` function forms (never use GIN, asserted in
   `CustomFieldPredicatesIntegrationTest`), and never use jsonpath comparisons for ranges (`@? '$.x ? (@ > 1)'` is always a
   sequential scan: 146 ms at 1M vs 64 ms on the expression index).
3. **Counts**: accept 3–12x slower large counts (worst observed 110 ms p50 at 1M rows for a 13%-selective
   `@>`, about 40 ms with the expression index), or keep total counts bounded (`count` over the filtered,
   authorised set is what Rails does today; consider a capped count or planner estimate for very broad
   filters). Keep autovacuum healthy; bitmap heap scans dominate these plans.
4. **Storage budget**: plan for about 2x heap during coexistence (both representations) and about 1.65x
   after cutover, plus 210 MB GIN per 1M rows. Values below are for an accounts-shaped table with 12
   custom fields; the JSON key names (`cf_...`) repeat in every row and are the main overhead.
5. **Trigger for coexistence** (`ffcrm_sync_custom_fields`, see the dual-read design): +0.1 ms per row
   write, backfill at about 16k rows/s via trigger or about 43k rows/s set-based; use set-based batched
   backfill and keep the trigger for live Rails writes only.
6. **Representation rules from §Type enforcement** (JSON numbers for numeric types, ISO strings for
   dates, arrays for check_boxes) are mandatory: the expression indexes and `@>` semantics depend on them.
   `jsonb` does not preserve decimal scale (`1234.50` is stored as `1234.5`).
7. PostgreSQL 14 floor (ADR 0004) is sufficient: everything used here (`jsonb_path_ops`, `@?`,
   expression indexes) exists since 12; the benchmark ran on 16.

A **NO-GO** would be warranted only if product requirements need large exact counts over arbitrary
custom-field filters at multi-million row scale with sub-10 ms latency. Nothing in the current Rails app
suggests that: Rails creates `cf_*` columns without any index, so today's custom-field filters are
sequential scans (the "`cf_*` no index" column above).

## Environment

| Item | Value |
|---|---|
| Date | 2026-10-07 |
| Database | PostgreSQL 16.15 (Testcontainers `postgres:16`, Debian build), single container |
| PG settings | `shared_buffers=1GB`, `work_mem=32MB`, `maintenance_work_mem=512MB`, `effective_cache_size=4GB`, `random_page_cost=1.1`, `jit=off`, `max_parallel_workers_per_gather=2`, `fsync=on`, `synchronous_commit=on` |
| Host | 8 vCPU Intel Xeon Platinum 8559C, 31 GB RAM, Linux 6.8 (AWS VM), Docker |
| Client | Java 21.0.12, PgJDBC (Spring Boot 3.5.16 BOM), JDBC autocommit, same host |
| Rows | **10k, 100k and 1M** (all three were run with the prototype benchmark harness, now removed by AB-271) |
| Repetitions | 3 warm-up + 20 measured per query; 2,000 single-row writes per write test |
| Wall time | 6 min 2 s for all three sizes (1M dominates) |

All numbers are warm-cache (`shared_read = 0` for every query in `queries.csv`): they measure CPU and
buffer work, not disk I/O. Absolute numbers will be higher on shared production hardware; the ratios
are what matters.

## Methodology

**Data.** One `bench_accounts` table shaped like `accounts` (core columns from the Rails schema) with 12
`cf_*` columns taken from the AB-262 census and `script/migration/production_shaped_manifest.rb` field types,
**plus** the equivalent `custom_fields jsonb` (built the way the dual-read trigger builds it, nulls omitted):

| Column | `as` | PG type | Fill | Distribution |
|---|---|---|---|---|
| `cf_account_segment` | select | varchar | 90% | SMB 40%, Mid-Market 25%, Enterprise 15%, … Strategic 0.5% |
| `cf_account_code` | string | varchar | 95% | unique |
| `cf_account_email` | email | varchar | 60% | |
| `cf_annual_value` | decimal | numeric(15,2) | 70% | skewed (u³ × 2M) |
| `cf_employee_count` | integer | integer | 50% | skewed |
| `cf_health_score` | float | double precision | 60% | uniform 0–100 |
| `cf_renewal_date` | date | date | 35% | 2025–2027 |
| `cf_last_reviewed_at` | datetime | timestamp | 50% | 2024–2026 |
| `cf_marketing_opt_in` | boolean | boolean | 80% | 30% true |
| `cf_profile_url` | url | varchar | 30% | |
| `cf_interests` | check_boxes | text (Psych YAML) | 55% | 0–5 of 5 options + "Partner program" 5% |
| `cf_notes` | text | text | 20% | ~100 chars |

The YAML for `cf_interests` is byte-identical to what Psych emits for `serialize(type: Array)`
(`---\n- Email\n- Events\n`, `--- []\n`), so the `cf_*` side is queried exactly as Rails stores it.
Seeded with `setseed(0.267)`; every variant of a scenario is asserted to return the same rows.

**Phases** per N: (1) no custom-field indexes; (2) `cf_*` btrees (`segment`, `annual_value DESC NULLS
LAST, id DESC`, `renewal_date`), a `pg_trgm` GIN on `cf_interests` (the best a YAML text column can get),
GIN `jsonb_path_ops`, and JSONB expression btrees mirroring the `cf_*` btrees; (3) GIN `jsonb_path_ops`
swapped for default `jsonb_ops` (GIN-using variants only). `VACUUM ANALYZE` after load and indexing.

**Query forms**: `count` = `SELECT count(*) WHERE <pred>`; `page` = `SELECT id, name WHERE <pred> ORDER BY
id DESC LIMIT 25` (the API list shape); sort scenarios = `ORDER BY <field> DESC NULLS LAST, id DESC LIMIT
25 OFFSET 0|5000`.

**Writes**: three copies of the table (`w_cf`: `cf_*` only; `w_jsonb`: JSONB only; `w_both`: both with the
sync trigger) with the same indexes; 2,000 autocommitted single-row INSERTs and 2,000 single-field
UPDATEs each; then two backfill strategies over the whole table (trigger via no-op UPDATE vs one set-based
`UPDATE ... SET custom_fields = jsonb_strip_nulls(jsonb_build_object(...))`), asserted to produce identical
documents.

## Results at 1M rows (p50 / p95 ms; full matrix for all N in `queries.csv`)

### Filters, `count(*)` form (worst case: touches every matching row)

| Scenario (matching rows) | `cf_*` no index | `cf_*` btree | JSONB no index | JSONB GIN `@>` (`jsonb_path_ops`) | JSONB expr btree | JSONB `@?` jsonpath |
|---|---|---|---|---|---|---|
| eq rare: segment = Strategic (4.4k) | 75.4 / 80.9 | **0.26** / 0.46 (index-only) | 120.7 / 127.7 | 4.04 / 4.27 | 1.57 / 1.71 | – |
| eq common: segment = Enterprise (135k) | 75.5 / 85.5 | **5.0** / 5.6 (index-only) | 122.1 / 141.2 | 110.7 / 119.9 | 40.7 / 49.3 | – |
| check_boxes contains "Partner program" (22k) | 99.0 / 108.4 (`LIKE`) | 24.0 / 25.6 (`LIKE` + trigram GIN) | 129.8 / 142.4 | **17.9** / 18.7 | – | 23.5 / 29.4 (GIN) |
| numeric range annual_value 250k–500k (91k) | 93.9 / 97.8 | **19.0** / 24.3 (index-only) | 152.4 / 163.4 | – | 64.5 / 72.5 | 146.6 / 155.4 (seq scan) |
| date range renewal Q1 2026 (29k) | 85.4 / 97.1 | **1.0** / 1.2 (index-only) | 121.7 / 128.3 | – | 12.7 / 14.0 | 125.0 / 132.7 (seq scan) |
| key exists: renewal_date (350k) | 81.7 / 86.6 | **6.9** / 7.3 (index-only) | 100.8 / 107.8 (`?`) | `?` with `jsonb_ops`: 126.5 / 134.9 | 91.8 / 98.6 | 110.3 / 120.7 (seq scan) |

### Filters, paginated form (`ORDER BY id DESC LIMIT 25`) and custom-field sort

| Scenario | `cf_*` indexed | JSONB best indexed |
|---|---|---|
| eq rare (Strategic) | 0.70 / 0.82 | 1.49 / 1.62 (expr) · 1.75 / 3.58 (GIN) |
| eq common (Enterprise) | 0.09 / 0.11 | 0.11 / 0.14 |
| check_boxes contains | 0.31 / 0.61 (`LIKE`) | 0.42 / 0.58 (`@>`) |
| numeric range | 0.14 / 0.17 | 0.20 / 0.21 |
| date range | 0.28 / 0.52 | 0.50 / 0.70 |
| key exists | 0.08 / 0.09 | 0.08 / 0.09 |
| sort by annual_value, page 1 | 0.07 / 0.08 | 0.09 / 0.30 |
| sort by annual_value, OFFSET 5000 | 0.49 / 0.62 | 3.47 / 3.75 |

Without indexes the sort is 115 ms (`cf_*`) vs 166 ms (JSONB) at 1M; with the expression index both are
sub-millisecond for page 1. Deep OFFSET pagination is about 7x slower on JSONB (index scan plus heap
fetches vs index-only scan); keyset pagination (AB-269) avoids that.

### Scaling (count form, best index per side, p50 ms)

| Scenario | 10k cf / JSONB | 100k cf / JSONB | 1M cf / JSONB |
|---|---|---|---|
| eq rare | 0.07 / 0.07 | 0.08 / 0.13 | 0.26 / 1.57 |
| eq common | 0.12 / 0.41 | 0.54 / 3.71 | 5.01 / 40.69 |
| check_boxes contains | 0.32 (`LIKE`) / 0.33 | 2.86 / 2.02 | 24.03 / 17.89 |
| numeric range | 0.24 / 0.51 | 1.86 / 4.23 | 19.00 / 64.45 |
| date range | 0.09 / 0.19 | 0.17 / 1.48 | 1.00 / 12.72 |

(Phase `btree_gin_path_ops` in `queries.csv`; JSONB = expression btree, or GIN `@>` for check_boxes.)
Both sides scale linearly with the number of matching rows; the JSONB constant factor is the heap
recheck (bitmap heap scan) that the `cf_*` index-only scan avoids.

### Sizes at 1M rows

| Relation | Size |
|---|---|
| `cf_*` values (sum of `pg_column_size`) | 99.7 MB |
| `custom_fields` values (sum of `pg_column_size`) | 279.2 MB (2.8x: key names repeated per row) |
| Heap, `cf_*` only (`w_cf`) | 281 MB |
| Heap, JSONB only (`w_jsonb`) | 463 MB (1.65x) |
| Heap, both (`w_both`, coexistence) | 571 MB (2.0x) |
| GIN `jsonb_path_ops` | 210 MB (build 9.1 s) |
| GIN `jsonb_ops` (default) | 328 MB (build 15.3 s) |
| btree on `cf_annual_value` vs expr btree on JSONB | 36.7 MB vs 36.7 MB |
| btree on `cf_renewal_date` vs expr btree | 7.0 MB vs 7.1 MB |
| trigram GIN on `cf_interests` YAML | 16.9 MB |

No TOAST was used (documents stay inline, about 280 bytes average). Tables with many long `text` custom
fields would TOAST the whole document; worth re-measuring in G4 if a deployment has such fields.

### Writes (1M-row tables, all indexes present, autocommit, p50 / p95 ms per statement)

| Operation | `cf_*` only | JSONB only | Both + sync trigger |
|---|---|---|---|
| INSERT one row | 0.081 / 0.261 (9.3k/s) | 0.089 / 0.259 (8.6k/s) | 0.180 / 0.475 (4.0k/s) |
| UPDATE one custom field | 0.091 / 0.347 (7.2k/s) | 0.094 / 0.155 (9.0k/s) | 0.213 / 0.481 (3.7k/s) |

| Backfill all rows (1.002M) | Time | Rate |
|---|---|---|
| Via trigger (`UPDATE ... SET custom_fields = custom_fields`) | 63.1 s | 15.9k rows/s |
| Set-based `jsonb_build_object` | 23.3 s | 43.1k rows/s |

Note: the committed backfill timings above were measured with the earlier plain-assignment expression;
the lossless merge form (`(custom_fields - cf_* keys) || jsonb_build_object`, which preserves Java-only
keys and marks undecodable YAML) re-measured at 10k/100k to trigger backfill 626.6 / 6,079.7 ms and
set-based backfill 276.5 / 2,215.6 ms (committed: 617.4 / 5,945.9 ms and 262.1 / 2,201.0 ms) — the
merge form costs the same within noise.

10k and 100k show the same ratios (`writes.csv`). JSONB-only writes cost the same as `cf_*` writes
(`jsonb_set` on one key rewrites the row either way under MVCC). The trigger's overhead is `to_jsonb(NEW)`
plus one `fields` lookup per row; caching the check_boxes list (dual-read design §4) will reduce it.

## JPA mapping comparison (from `JsonbMappingComparisonTest`)

| | `AttributeConverter<Map,String>` + `@Convert` | `@JdbcTypeCode(SqlTypes.JSON)` |
|---|---|---|
| Binding to `jsonb` | **Fails** without help: `column "custom_fields" is of type jsonb but expression is of type character varying`. Needs `@ColumnTransformer(write = "?::jsonb")`. | Works out of the box (Hibernate's PostgreSQL JSON type). |
| Numbers | `BigDecimal` (with `USE_BIG_DECIMAL_FOR_FLOATS`) | `Double` (Hibernate's default `FormatMapper`; `1234.50` → `1234.5`). Configure `hibernate.type.json_format_mapper` with a BigDecimal-enabled Jackson mapper. |
| In-place `map.put(...)` dirty checking | Detected (snapshot of converted String) | Detected (deep-copy snapshot) |
| Flush granularity | Whole document | Whole document |

**Recommendation: `@JdbcTypeCode(SqlTypes.JSON)`** with a custom JSON `FormatMapper` (Jackson with
`USE_BIG_DECIMAL_FOR_FLOATS`), so decimals are not silently converted to binary floating point, and with
`@Column(updatable = false)` on the attribute while Rails can write the same rows (use key-level
`jsonb_set` updates instead; see the dual-read design §6). The converter works but needs a SQL write
transformer, which is easy to forget on new entities.

## Query shapes for AB-269 (promoted to `CustomFieldPredicatesIntegrationTest`)

- **Criteria / Specification**: register functions with a Hibernate `FunctionContributor` (service file
  `META-INF/services/org.hibernate.boot.model.FunctionContributor`):
  `ffcrm_jsonb_contains` → pattern `(?1 @> cast(?2 as jsonb))`, used as
  `cb.isTrue(cb.function("ffcrm_jsonb_contains", Boolean.class, root.get("customFields"), cb.literal(json)))`.
  Generated SQL: `... where (sja1_0.custom_fields @> cast(? as jsonb))=true`; plan uses the GIN index.
- `@?` **cannot** be a `registerPattern`: Hibernate's `PatternRenderer` treats every `?` as a parameter
  placeholder (`NumberFormatException`). The prototype-only custom descriptor used to render
  `(a @? cast(p as jsonpath))` was not promoted.
- **JPQL/HQL** works with the same registered functions (`where ffcrm_jsonb_contains(a.customFields, :json) = true`).
- **Native `@Query`** works for `@>` (`custom_fields @> cast(:json as jsonb)`), but not for `?`, `?|`,
  `?&` or `@?`: Hibernate parses `?` as an ordinal parameter. Use the registered functions, or plain
  JDBC/`JdbcTemplate`, for those.
- Function forms `jsonb_contains(...)` and `jsonb_path_exists(...)` return the right rows but **never use
  GIN** (asserted). Only the operator forms are indexable.

## Reproducing

The prototype benchmark harness was removed by AB-271 after production promotion. The results above
are retained as the recorded benchmark; the `benchmarkTest` task is no longer available.

## Limitations

- Single-client latency, warm cache, one container on one VM. No concurrency, no I/O-bound runs; the
  ratios, plan shapes and index usage are the transferable result.
- One table shape (accounts, 12 custom fields). Deployments with dozens of custom fields have larger
  documents (bigger GIN, possible TOAST) and should re-run with their census.
- Authorization predicates (AB-269/C3) and text search were not combined with custom-field filters.
- `@?` jsonpath range comparisons never use an index in PostgreSQL 16; if that changes, revisit §2.
