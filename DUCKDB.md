# DuckDB Analytics Backend

This branch (`feat/duckdb-analytics-backend`) adds [DuckDB](https://duckdb.org/) as a fourth
analytics database backend for DHIS2, alongside PostgreSQL (the default), ClickHouse, and
Apache Doris.

## Why

DHIS2 supports pluggable analytics databases so that the analytics tables (built from the
PostgreSQL transaction database) can live in an engine optimized for analytical queries. The
existing alternatives — ClickHouse and Doris — are powerful but heavy: each is a separate
server (or cluster) that must be installed, configured, secured, and operated. That cost is
justified for large production deployments, but it makes the alternate-backend code path
expensive to exercise anywhere else.

DuckDB fills that gap. It is an embedded, in-process columnar OLAP engine: a single JDBC
dependency, no server, no daemon. The database is one file. This gives DHIS2 a real columnar
analytics engine that starts in milliseconds with (almost) zero infrastructure — the one
caveat is that the `postgres` extension used to read the transaction database is downloaded
from the DuckDB extension repository on first use, so the first startup needs outbound
network access and a writable extension directory (default `~/.duckdb`); pre-install the
extension for air-gapped deployments.

**This backend targets simplicity, not scale.** The intended uses are:

- **Testing and CI** — execute generated analytics SQL against a real engine in unit tests
  with no containers or external services (see `DuckDbExecutionTest`).
- **Local development** — run the full alternate-backend code path (table export, qualified
  source reads, dialect divergence) on a laptop without standing up a cluster.
- **Small / single-node deployments** — demos, training environments, and pilots where a
  columnar engine helps but ClickHouse/Doris would be over-provisioned.

It is explicitly **not** intended for large or horizontally-scaled production analytics — use
ClickHouse or Doris for that.

## Scope

What the branch adds (all analytics-side; the transaction database remains PostgreSQL):

- `Database.DUCKDB` enum value, selected with `analytics.database = DUCKDB` in `dhis.conf`,
  plus a file-backed JDBC URL such as
  `analytics.connection.url = jdbc:duckdb:/path/to/analytics.duckdb`. In-memory URLs are
  rejected at startup: each in-memory JDBC connection owns a private database, so pooled
  connections would each see a different, unrelated database. (In-memory remains useful for
  single-connection unit tests, which do not go through the pool.)
- `DuckDbSqlBuilder` — the general SQL dialect builder. DuckDB's SQL is largely
  PostgreSQL-compatible, so it extends `PostgreSqlBuilder` and overrides only what differs:
  regex matching (`regexp_matches(...)` instead of `~`), JSON extraction
  (`json_extract_string(...)` instead of `->>`/`#>>`), table qualification, index/vacuum/
  analyze/unlogged capabilities, and geometry types.
- `DuckDbAnalyticsSqlBuilder` — the analytics-specific builder, including a DuckDB rewrite of
  the event data values JSON aggregation (`json_group_object` / `json_keys` /
  `json_extract_string` instead of `json_object_agg` / `jsonb_object_keys`).
- **Source access via `ATTACH`**: like Doris (JDBC catalog) and ClickHouse (named
  collection), DuckDB reads the DHIS2 PostgreSQL transaction database directly. It uses the
  DuckDB `postgres` extension to attach it read-only as `pg`; generated `analytics*` tables
  are owned by DuckDB itself, everything else resolves to `pg.public."..."`.
- **Per-connection initialization**: DuckDB's `ATTACH` and session settings are
  connection/instance state, not persisted to the `.duckdb` file, so the init SQL (install/
  load extension, attach, memory settings) runs on every pooled connection via Hikari
  `connectionInitSql` (`DbPoolConfig.connectionInitSql`, built in
  `AnalyticsDataSourceConfig`).
- **Embedded memory tuning**: since DuckDB shares host RAM with the JVM, the memory limit is
  capped at 75% of (physical RAM − JVM max heap), floored at 512 MB, with a disk spill
  directory derived from the database file path.
- **Portability fixes in shared code**: `JdbcAnalyticsTableManager`'s outlier statistics
  sub-query now uses `sqlBuilder.qualifyTable(...)` and `sqlBuilder.regexpMatch(...)` instead
  of hard-coded PostgreSQL syntax, benefiting all non-PostgreSQL backends.
- Tests: `DuckDbSqlBuilderTest` and `DuckDbAnalyticsSqlBuilderTest` (SQL generation), and
  `DuckDbExecutionTest` (live execution against in-memory and file-backed DuckDB instances
  through the real JDBC driver).

Out of scope (for now):

- **Geospatial**: `supportsGeospatialData()` returns false, matching ClickHouse/Doris.
  Geometry columns are stored as `VARCHAR`; the DuckDB `spatial` extension is deliberately
  deferred.
- Any change to the transaction database — PostgreSQL remains the system of record.

## Standing vs. the other backends

| | PostgreSQL | ClickHouse | Doris | DuckDB (this branch) |
|---|---|---|---|---|
| Maturity in DHIS2 | Default, fully supported | Supported alternate | Supported alternate | **Experimental — validated E2E against the demo DB** |
| Deployment model | Server (usually shared with transaction DB) | Separate server/cluster | Separate cluster (FE+BE) | **Embedded in the DHIS2 JVM** |
| Storage model | Row-oriented | Columnar | Columnar (MPP) | Columnar |
| Source-DB access | Same database | Named collection + `postgresql()` table function | JDBC catalog | `postgres` extension, read-only `ATTACH` as `pg` |
| Horizontal scaling | Read replicas | Yes | Yes | **No (single process, single writer)** |
| Geospatial analytics | Yes (PostGIS) | No | No | No |
| Extra infrastructure | None | ClickHouse server | Doris cluster + JDBC driver jar | **None (one Maven dependency)** |
| Declarative partitioning | Inheritance-based tables | Handled by engine | Handled by engine | No-op (columnar zonemaps; see TODO below) |

Current standing of this backend, honestly stated:

- **Validated end-to-end** against a real instance (Sierra Leone dev demo database, full
  `dhis.conf` setup, repeated full analytics exports): all 11 table types build, populate,
  and swap; the aggregate analytics API **exactly matches** sums computed directly on the
  source PostgreSQL data; event aggregate/query, enrollment query, org-unit-level
  breakdowns, program indicators, and analytics-backed outlier detection all return correct
  data. All 50 DuckDB unit and execution tests pass.
- **Continuous analytics ("latest" partial update) validated live**: a data value changed
  via the API flows into DuckDB analytics through the incremental path (delete stale rows,
  populate only the update window, append into the main table) — verified by exact
  arithmetic on a facility-level value across full and latest runs. Getting here required
  fixing three latent bugs in shared code that affect every non-PostgreSQL backend (the
  continuous job was previously blocked entirely by one of them); see git history.
- **Validation-result analytics verified**: with seeded validation results, the
  `analytics_validationresult` table builds on DuckDB and validation-rule queries aggregate
  exactly (18 seeded violations → correct per-rule/per-month counts).
- **Relationship-based program indicators verified for parity**: the relationship-traversal
  SQL (`RelationshipTypeJoinGenerator`) executes on DuckDB against the attached source
  tables, and a relationship-scoped PI query returns results identical to the PostgreSQL
  backend on the same data.
- **Partitioning verified** (previously a TODO): `supportsDeclarativePartitioning() = true`
  yields single unpartitioned tables per analytics table, populated without partition
  filters, swapped via multi-statement drop + rename, and queried through the main table —
  the same behavior as ClickHouse and Doris.
- **Bugs found and fixed during the E2E run** (both invisible to unit tests; open issues
  and operational caveats are tracked in [DUCKDB_BUGS.md](DUCKDB_BUGS.md)):
  1. `qualifyTable` originally kept `analytics*` names local, which silently broke resource
     table replication (`insert into local select from qualifyTable(name)` copied the empty
     local table into itself, leaving period-structure lookups empty and aborting the
     DATA_VALUE stage with "nothing to update"). `qualifyTable` now unconditionally targets
     the attached `pg` source — the same contract as Doris/ClickHouse — and the
     `removeUpdatedData` delete targets in the shared table managers use `quote()` (local)
     instead, which is identical SQL on PostgreSQL.
  2. `tableExists` inherited the PostgreSQL check (`table_schema = 'public'`), but local
     DuckDB tables live in schema `main` — the "master table exists" decision during swaps
     was always false (and could match same-named tables in the attached source instead).
     DuckDB now checks `table_schema = 'main'` scoped to `current_database()`.
- **Tracked-entity analytics stays on PostgreSQL by upstream design**: the TE table managers
  are wired with `analyticsPostgresJdbcTemplate` and `postgresSqlBuilder` regardless of the
  configured analytics database (this applies equally to ClickHouse/Doris), so TE tables and
  queries do not exercise DuckDB at all.
- **Known duplication**: `DuckDbAnalyticsSqlBuilder` re-applies the base dialect overrides
  from `DuckDbSqlBuilder` because Java single inheritance prevents extending both
  `PostgreSqlAnalyticsSqlBuilder` and `DuckDbSqlBuilder`; a base-class refactor would remove
  this.
- **Caveats observed while testing** (not DuckDB-specific, but worth knowing):
  - An upstream bug in `AnalyticsCache` breaks *cached* event analytics responses on any
    backend: grids holding a non-serializable `Pager` fail the serialization-based deep
    clone, and the API degrades to an empty result. With the demo database (which enables
    caching in system settings), set `keyCacheStrategy` to `NO_CACHE` or apply the upstream
    fix before judging query results.
  - Analytics table swap errors are swallowed by `executeSilently` (upstream pattern). On an
    embedded engine with transactional catalog semantics, a swap racing a concurrent query
    could silently leave a stale table; watch the logs when diagnosing unexpected query
    results during exports.
  - The `.duckdb` file alone is not the full database state — un-checkpointed changes live in
    the `.duckdb.wal` file next to it. When inspecting a live instance's file with an
    external tool, copy both files or you will see a stale catalog (e.g. staging tables that
    were already renamed).

## Pros and cons

### Pros

- **Zero infrastructure**: no server to install or operate; the whole backend is the
  `org.duckdb:duckdb_jdbc` dependency (plus the `postgres` extension, auto-downloaded on
  first use). Removes the biggest barrier to using a columnar engine for small deployments
  and development.
- **Real columnar performance on one node**: vectorized execution, automatic statistics
  (no `ANALYZE`), zonemap pruning (no secondary indexes to build — analytics table
  generation skips the entire indexing phase).
- **Testability**: analytics SQL can be executed, not just string-compared, in plain unit
  tests. This is something none of the server-based backends can offer without containers.
- **PostgreSQL-compatible dialect**: the builder inherits most of `PostgreSqlBuilder`
  unchanged, so the surface area of DuckDB-specific code (and therefore of dialect bugs) is
  small compared to Doris (MySQL-ish) or ClickHouse.
- **Direct Postgres reads**: the `postgres` extension scans the transaction database
  in-place — same architectural pattern as Doris's JDBC catalog and ClickHouse's named
  collections, with no ETL step.

### Cons

- **Does not scale out**: a `.duckdb` file is single-writer and cannot be shared read-write
  across multiple DHIS2 app nodes. Clustered or high-volume deployments need
  ClickHouse/Doris.
- **Shares resources with the JVM**: the engine runs in-process, so analytics table
  generation competes with the web application for RAM and CPU on the same host. The memory
  cap mitigates but cannot eliminate this; a heavy export can still pressure the app server.
- **Per-connection session state**: `ATTACH` and settings must be re-run on every pooled
  connection (`connectionInitSql`). This is easy to get wrong when creating connections
  outside the pool, and was the source of a real bug fixed on this branch.
- **No geospatial analytics** (currently): maps/geo features fall back accordingly, same as
  ClickHouse and Doris, whereas PostgreSQL+PostGIS supports them fully.
- **Experimental**: no production track record in DHIS2 yet (though validated end-to-end
  against the demo database, see standing above), and the JDBC driver + extension ecosystem
  moves fast (pinned to `duckdb_jdbc` 1.5.4.0 — the latest release, engine v1.5.4, as of
  2026-06-17).

Engine features verified available through the pinned driver but not yet used (follow-up
candidates): `MERGE INTO` on regular tables (could replace the latest-update delete+append
with a single upsert, at the cost of diverging from the shared cross-backend flow), and
database-file encryption (`ATTACH ... (ENCRYPTION_KEY ...)`) — at-rest encryption of the
analytics file via an optional `dhis.conf` key would be trivial to wire into the
per-connection initializer.
- **Crash isolation**: an engine fault in an embedded database takes down the JVM with it,
  unlike a separate server process.

## Benchmark (Sierra Leone demo database)

Same host, same demo database, analytics cache disabled, identical query suite (medians of
7 runs after warmup); PostgreSQL 16 in Docker with default configuration (untuned), DuckDB
embedded in the DHIS2 JVM. Row counts were identical across backends for every query.

| Measure | DuckDB | PostgreSQL |
|---|---|---|
| Full analytics export (first run) | 93 s | 661 s |
| Full analytics export (repeat) | 78 s | 138 s |
| Aggregate, national, 12 months (24 rows) | 0.19 s | 0.20 s |
| Aggregate, chiefdom level, 4 quarters (597 rows) | 0.19 s | 0.21 s |
| Event aggregate, district level, 12 months (156 rows) | 0.10 s | 0.13 s |
| Event query, page of 100 | 0.10 s | 0.13 s |
| Enrollment query, page of 100 | 0.10 s | 0.10 s |
| Program indicators, district level (312 rows) | 0.10 s | 0.18 s |

Take with the usual caveats (demo-scale data, untuned PostgreSQL, HTTP overhead dominating
sub-second queries), but directionally: DuckDB's export is substantially faster — largely
because columnar storage needs no index-building phase — and query latency is equal or
better across the suite.

## Near-real-time analytics (continuous analytics)

The continuous analytics job gives near-real-time freshness without any architectural
change: it performs incremental "latest" updates (delete rows updated since the last full
update, re-populate only that window, append into the main tables) on a fixed-delay
schedule, plus one full rebuild per day. On this branch the path is verified live on DuckDB
(it was previously non-functional on every non-PostgreSQL backend — see git history): a
latest run took ~19 s against the demo database versus ~74 s for a full rebuild.

Setup — there is no default job; create one in the Scheduler app (job type "Continuous
analytics table") or via the API:

```json
POST /api/jobConfigurations
{
  "name": "Continuous analytics",
  "jobType": "CONTINUOUS_ANALYTICS_TABLE",
  "delay": 300,
  "jobParameters": { "fullUpdateHourOfDay": 3 }
}
```

- `delay` — seconds between the *completion* of one run and the start of the next
  (fixed-delay semantics: runs never overlap, a slow run self-throttles). With ~19 s runs
  at demo scale, a 60–300 s delay is realistic.
- `fullUpdateHourOfDay` — the daily hour at which the job performs a full rebuild instead
  of a latest update, resetting the incremental window. The first-ever run is always full.
- `skipOutliers` must match how the tables were built — the job checks that the `analytics`
  table's outlier columns align with its parameters and refuses to run otherwise (this
  check is dialect-aware as of this branch).
- Disable the regular scheduled "Analytics table" job while the continuous job is active;
  they operate on the same tables.

Caching — two layers decide whether users actually *see* the fresh data:

- The server-side analytics cache is invalidated at the end of every table update (full and
  latest alike), so it self-corrects. Note the upstream `AnalyticsCache`/`Pager`
  serialization bug (see `DUCKDB_BUGS.md`) before enabling server caching at all.
- HTTP `Cache-Control` headers (driven by `keyCacheStrategy`) are honored by browsers and
  any proxy/CDN independently of server-side invalidation — a `CACHE_1_HOUR` strategy
  silently defeats a 1-minute refresh loop. Use `NO_CACHE`, or preferably **progressive
  caching**, whose TTL scales with the age of the queried period: current-period queries
  stay fresh while historical queries remain cheap.

Caveat: consecutive latest runs between fulls are idempotent by construction (each run
deletes and re-appends the whole since-last-full window) but only a single latest run per
full cycle has been observed live so far.

## Configuration example

```properties
# dhis.conf
analytics.database = DUCKDB
analytics.connection.url = jdbc:duckdb:/var/lib/dhis2/analytics.duckdb
```

The URL must be file-backed — in-memory URLs (`jdbc:duckdb:` with no path) are rejected at
startup because each pooled connection would get its own private database. The PostgreSQL
connection settings (`connection.url`, `connection.username`, `connection.password`) are
reused automatically to attach the transaction database read-only.

## Running the tests

```sh
cd dhis-2
mvn test -pl dhis-support/dhis-support-sql -Dtest='DuckDb*'
```

Requires JDK 17 (the project's target); JaCoCo 0.8.13 cannot instrument newer class files, so
running with a JDK ≥ 25 fails during agent instrumentation.
