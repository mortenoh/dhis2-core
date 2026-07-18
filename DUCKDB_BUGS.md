# DuckDB Backend — Bugs Found During End-to-End Validation

Bugs surfaced by running the DuckDB analytics backend against a real DHIS2 instance
(Sierra Leone dev demo database, full analytics table exports, query API verification).
None of these were caught by the 47 pre-existing unit tests — all required a live run.
See `DUCKDB.md` for the backend overview.

## 1. `qualifyTable` broke resource table replication (fixed)

**Severity**: critical — the DATA_VALUE (aggregate) analytics stage silently produced nothing.

**Symptom**: a full analytics export completed "successfully" in seconds, but
`/api/analytics` queries failed with `Table with name analytics does not exist`. The log
showed `Table update aborted, nothing to update: 'analytics'` for the DATA_VALUE and
COMPLETENESS stages.

**Root cause**: `DuckDbSqlBuilder.qualifyTable(name)` special-cased names starting with
`analytics` to resolve to the *local* DuckDB table, on the theory that generated tables are
owned locally. But `JdbcTableReplicationStore.replicateTable` relies on the opposite
contract — it copies `analytics_rs_*` resource tables from the transaction database into
identically-named local tables by reading `insert into <local> select * from
qualifyTable(name)`. With the prefix heuristic, that read resolved to the just-created,
empty local table: the replication copied nothing into itself, reported no error, and left
every resource table (`analytics_rs_periodstructure` etc.) empty in DuckDB. The data-years
detection query then found no years, and the aggregate stage concluded there was nothing to
update. Doris and ClickHouse qualify unconditionally to the source catalog, which is why
they don't hit this.

**Fix** (`11705913ce`): `qualifyTable` now unconditionally targets the attached read-only
`pg` source database, matching the Doris/ClickHouse contract. The three call sites that
genuinely reference locally-owned generated tables (the `removeUpdatedData` partial-update
delete targets in `JdbcAnalyticsTableManager` and `JdbcEventAnalyticsTableManager`) use
`sqlBuilder.quote(name)` instead — byte-identical SQL on PostgreSQL, and it corrects the
delete target for Doris/ClickHouse too, where `qualifyTable` would have aimed the delete at
the source catalog.

**Lesson**: `qualifyTable` has a single meaning across all backends — "reference a
transaction-database table". Owned/generated tables are referenced with `quote()`.

## 2. `tableExists` checked the wrong schema (fixed)

**Severity**: medium — wrong "master table exists" decisions during swaps; would corrupt
partial-update ("latest") logic.

**Symptom**: `Master table exists: 'false'` logged during swaps for tables that
demonstrably existed in the DuckDB file.

**Root cause**: DuckDB inherited `PostgreSqlBuilder.tableExists`, which filters
`table_schema = 'public'`. Local DuckDB tables live in schema `main` of the current
database, so the check never matched a local table. Worse, DuckDB's
`information_schema.tables` also lists tables of *attached* databases — the Sierra Leone
demo dump ships with pre-generated `analytics_*` tables in PostgreSQL's `public` schema, so
the check could return *true* by matching a same-named table in the attached source rather
than the local analytics database.

**Fix** (`11705913ce`): DuckDB override scopes the check to
`table_catalog = current_database() and table_schema = 'main'`. Covered by a live-execution
test that also asserts the full drop + rename swap shape leaves exactly the main table.

## 3. Upstream: `AnalyticsCache` breaks cached event analytics responses (not ours, unfixed)

**Severity**: high for affected deployments — but backend-independent and pre-existing
upstream; not introduced or fixable by this branch.

**Symptom**: `/api/analytics/events/aggregate/...` returned HTTP 200 with an empty grid
while the identical SQL returned rows when run directly against the analytics database. The
log showed `SerializationException: java.io.NotSerializableException:
org.hisp.dhis.common.Pager` from `AnalyticsCache.getGridClone`.

**Root cause**: `AnalyticsCache` deep-clones grids with
`SerializationUtils.clone` (Java serialization). Event analytics grids carry a
`Pager`/`SlimPager` in their metadata when paging applies, and `Pager` does not implement
`Serializable` — the clone throws, and the response degrades to an empty grid. Triggered on
any backend (PostgreSQL included) whenever the analytics cache is enabled (the demo
database enables it via system settings; `keyCacheStrategy = NO_CACHE` works around it).

**Status**: needs an upstream fix (make `Pager` serializable, or clone without Java
serialization). Worth filing as a separate issue/PR against master — do not bundle it into
the DuckDB branch.

## Observations that looked like bugs but weren't

- **"Leftover" `_temp` staging tables when inspecting the `.duckdb` file externally**: the
  file alone is not the full database state — un-checkpointed catalog changes (e.g. the
  final rename of the last-processed table type) live in `analytics.duckdb.wal`. Copy both
  files when inspecting a live instance, or the catalog appears stale. (Cost roughly an hour
  of chasing phantom swap failures.)
- **Tracked-entity analytics ignoring DuckDB entirely**: the three TE table managers are
  wired with `analyticsPostgresJdbcTemplate` and `postgresSqlBuilder` by upstream design —
  TE analytics tables always live in PostgreSQL regardless of the configured analytics
  database (also true for ClickHouse/Doris). Explains Postgres-style index creation
  (`369 indexes`) and `Master table exists: 'true'` for TE tables during exports.
- **Enrollment queries returning 0 rows for `LAST_5_YEARS`**: correct behavior — the demo
  dataset's enrollments are dated 2026–2027.
