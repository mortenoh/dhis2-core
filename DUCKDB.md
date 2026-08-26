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
- **Bugs found and fixed during the E2E run** (both invisible to unit tests):
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
- **Builder hierarchy**: `DuckDbAnalyticsSqlBuilder` extends `DuckDbSqlBuilder` and implements
  `AnalyticsSqlBuilder`, the same shape as the ClickHouse and Doris analytics builders, so the
  dialect divergences are inherited rather than restated. Java single inheritance means the
  PostgreSQL period-bucket SQL cannot also be inherited; it is reached through a
  `PostgreSqlAnalyticsSqlBuilder` delegate, which is equivalent because that block is stateless
  string formatting. DuckDB overrides only the `BI_MONTHLY` bucket (PostgreSQL's `/` truncates
  on integers, DuckDB's yields a DOUBLE that `make_date` rejects; `//` is its integer division).

  Follow-up (deliberately not done here): moving that period-bucket block out of
  `PostgreSqlAnalyticsSqlBuilder` into a helper both analytics builders call removes the
  delegate entirely. It was implemented and verified as pure movement — the SQL unchanged,
  `PostgreSqlAnalyticsSqlBuilder` 109 lines lighter, its method reduced to a one-line call,
  all 238 `dhis-support-sql` and 2103 `dhis-service-analytics` tests green — then dropped,
  because it is the only change that would edit shared PostgreSQL code for a reason unrelated
  to adding a backend. Worth doing as a standalone upstream cleanup rather than inside this
  branch.
- **Caveats observed while testing** (not DuckDB-specific, but worth knowing):
  - An upstream bug in `AnalyticsCache` breaks *cached* event analytics responses on any
    backend, PostgreSQL included. Symptom: `/api/analytics/events/aggregate/...` returns
    HTTP 200 with an empty grid while the identical SQL returns rows when run directly
    against the analytics database, and the log carries
    `SerializationException: java.io.NotSerializableException: org.hisp.dhis.common.Pager`
    from `AnalyticsCache.getGridClone`.

    `AnalyticsCache` deep-clones grids with Java serialization
    (`AnalyticsCache.getGridClone` → `SerializationUtils.clone`). `Grid` is `Serializable`
    and its metadata map serializes with it, but event analytics puts a `Pager`/`SlimPager`
    into that map whenever paging applies (`ResponseHelper`, `MetadataParamsHandler`), and
    `org.hisp.dhis.common.Pager` implements nothing — so the clone throws and the response
    degrades to an empty grid. Triggered whenever the analytics cache is enabled; the demo
    database enables it via system settings.

    Workaround: set `keyCacheStrategy` to `NO_CACHE` before judging query results. A fix
    belongs upstream — make `Pager` serializable, or stop cloning grids through Java
    serialization (preferable: serialization makes every object that ever lands in grid
    metadata part of the cache's contract).
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
- **Concurrency headroom**: DuckDB parallelizes a single query across cores well, but many
  simultaneous dashboard users contend inside one process with one memory budget.
  ClickHouse in particular is built for high concurrent query volume.
- **Experimental**: no production track record in DHIS2 yet (though validated end-to-end
  against the demo database, see standing above), and the JDBC driver + extension ecosystem
  moves fast (pinned to `duckdb_jdbc` 1.5.5.1 — the latest release, engine v1.5.5, as of
  2026-08-26).
- **Crash isolation**: an engine fault in an embedded database takes down the JVM with it,
  unlike a separate server process.

Engine features verified available through the pinned driver but not yet used (follow-up
candidates): `MERGE INTO` on regular tables (could replace the latest-update delete+append
with a single upsert, at the cost of diverging from the shared cross-backend flow), and
database-file encryption (`ATTACH ... (ENCRYPTION_KEY ...)`) — at-rest encryption of the
analytics file via an optional `dhis.conf` key would be trivial to wire into the
per-connection initializer.

Worth tracking: the **Quack remote protocol** (announced 2026-05, nightly extension;
first production release planned with DuckDB v2.0, fall 2026) turns a DuckDB instance into
an HTTP server that other DuckDB instances attach to, with multiple concurrent writers
across processes. It targets exactly this backend's structural cons — host contention,
crash isolation, and the single-writer/single-node constraint — at the cost of the
zero-infrastructure pitch and the in-process latency win. If it stabilizes, it would enable
a deployment tier between embedded DuckDB and ClickHouse/Doris: several DHIS2 app nodes
sharing one lightweight DuckDB analytics server, reusing this backend's dialect and
qualification machinery (`ATTACH 'quack:host'` + `remote.`-qualified tables is structurally
the same pattern as the existing `pg` attach). Not viable before the protocol is declared
stable. See https://duckdb.org/2026/05/12/quack-remote-protocol.

## Benchmark (Sierra Leone demo database)

Run through the repo's own Gatling harness (`dhis-2/dhis-test-performance`) so the numbers
are reproducible rather than hand-timed. Both sides use the same image built from this
branch, the same Sierra Leone dump, and the same container limits; the backend is switched
with `DHIS_CONF_FILE`:

```sh
cd dhis-2 && ./build-dev.sh                       # dhis2/core-dev:local from this branch
cd dhis-test-performance

# PostgreSQL analytics
WEB_MEM=20gb WEB_HEAP=4000 DHIS2_IMAGE=dhis2/core-dev:local DB_TYPE=sierra-leone \
COMPOSE_EXTRA_FILE=docker/compose.pg-baseline.yml ANALYTICS_GENERATE=true \
SIMULATION_CLASS=org.hisp.dhis.test.analytics.SierraLeoneSimulationsRunner ./run-simulation.sh

# DuckDB analytics
WEB_MEM=20gb WEB_HEAP=4000 DHIS2_IMAGE=dhis2/core-dev:local DB_TYPE=sierra-leone \
DHIS_CONF_FILE=dhis-duckdb.conf COMPOSE_EXTRA_FILE=docker/compose.duckdb.yml \
ANALYTICS_GENERATE=true \
SIMULATION_CLASS=org.hisp.dhis.test.analytics.SierraLeoneSimulationsRunner ./run-simulation.sh
```

### Analytics table export

Matched at a 20 GB container with a 4 GB JVM heap, leaving DuckDB a 12 GiB engine budget:

| Backend | Export |
|---|---|
| PostgreSQL | 3 min 19.7 s |
| DuckDB | **48.2 s** |

Most of the gap is work DuckDB never does: the PostgreSQL run built **748 indexes**, while
the columnar backend builds none. Index count scales with programs and data elements rather
than with row count, so this gap is not a function of dataset size.

### Query latency: unresolved

The suite's 26 Sierra Leone simulations include 19 tracked-entity queries, and tracked-entity
analytics runs on PostgreSQL regardless of the configured backend — so those 19 are a control
group doing byte-identical work on both sides. In a single run per backend (5 users ramped
over 40 s):

| Group | Mean p95 change, DuckDB vs PostgreSQL |
|---|---|
| 7 backend-relevant queries | +29.0% |
| 19 tracked-entity control | +35.2% |

The control moved further than the treatment, so the difference is run-to-run variance, not
the backend. **No query-latency conclusion can be drawn from single runs on this harness**;
the measured noise floor (~35%) swamps any plausible backend effect. Resolving it needs
repeated alternating runs with the control used as the noise estimate. Query latency matters
more than export time for users, so this is the gap worth closing before the backend is
judged on performance.

### Memory floor: DuckDB needs a large engine budget even on the demo database

DuckDB caps itself at `(container limit - JVM max heap) * 0.75`. Sierra Leone exports fail
outright below roughly 12 GiB:

| Container | Heap | Engine budget | Export |
|---|---|---|---|
| 16 GB | 10 GB | 4.3 GiB | Out of Memory, whole export aborts |
| 11 GB | 3 GB | 5.7 GiB | Out of Memory, whole export aborts |
| 11 GB | 3 GB | 5.7 GiB | Out of Memory with export parallelism forced to 1 |
| 20 GB | 4 GB | 12 GiB | completes in 48 s |

PostgreSQL completed the same export at both 16 GB/10 GB (3 min 21 s) and 11 GB/3 GB
(3 min 29 s), so the container was not simply too small for any backend - its analytics work
happens in the database container, which was untouched.

The third row is the important one. With `keyParallelJobsInAnalyticsTableExport = 1`, a
**single** `insert ... select` exhausts 5.7 GiB: the populate of a 171-column event analytics
table carrying 176 `json_extract_string` calls, on a dataset with trivial row counts.
`preserve_insertion_order = false` is already set. So the constraint is **table width, not
data volume** - a vectorised engine materialises columns x vector size x threads, and width
is driven by data elements per program stage, category columns, and org-unit hierarchy
depth. Width is uncorrelated with instance size: a small pilot with one richly instrumented
program can hit this while a large aggregate-only instance never does.

This also explains why the earlier laptop validation saw all 11 table types build - outside a
container the cap resolves to `(host RAM - heap) * 0.75`, tens of GB. The practical
consequence is that **the memory does not disappear, it moves into the DHIS2 container**:
DuckDB removes the analytics workload from the PostgreSQL server, but the app container must
then be provisioned for it.

The floor is not intrinsic to DuckDB - it follows from how this branch populates tables.
`DefaultAnalyticsTableService.getTablePartitions` returns one fake partition covering the
whole master table when `supportsDeclarativePartitioning()` is true, and
`getPartitionClause` drops the year-range filter for the same reason
(`emptyIfTrue(partitionFilter, sqlBuilder.supportsDeclarativePartitioning())`). So DuckDB
runs a **single** `insert ... select` spanning every year of data per table type, where
PostgreSQL runs one bounded statement per year partition. Peak memory is therefore
proportional to the entire dataset rather than to one year. Populating per year window while
keeping the single physical table would bound it; see the follow-ups below.

Spilling is not the problem: DuckDB creates `temp_directory` lazily on first use and a large
aggregation under a 200 MB limit completes by spilling, verified directly against the pinned
driver. The engine also defaults to one thread per core (12 on the benchmark host), and peak
memory scales with thread count - `SET threads` is not currently part of the per-connection
init.

Earlier hand-timed figures on this branch (93 s vs 661 s export, query latency "equal or
better") came from an unconstrained laptop run and are superseded by the table above; the
query half of that claim does not survive having a control group.

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
  serialization bug (see the caveats under "Standing vs. the other backends") before
  enabling server caching at all.
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
