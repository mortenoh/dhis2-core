# DuckDB Backend — Known Issues and Caveats

Open issues, workarounds, and documented constraints for the DuckDB analytics backend.
Fixed bugs are not tracked here — see git history and `DUCKDB.md` for what was found and
fixed during validation. See `DUCKDB.md` for the backend overview.

## Upstream: `AnalyticsCache` breaks cached event analytics responses (workaround)

**Severity**: high for affected deployments — backend-independent and pre-existing upstream;
not introduced or fixable by this branch.

**Symptom**: `/api/analytics/events/aggregate/...` returns HTTP 200 with an empty grid while
the identical SQL returns rows when run directly against the analytics database. The log
shows `SerializationException: java.io.NotSerializableException: org.hisp.dhis.common.Pager`
from `AnalyticsCache.getGridClone`.

**Root cause**: `AnalyticsCache` deep-clones grids with `SerializationUtils.clone` (Java
serialization). Event analytics grids carry a `Pager`/`SlimPager` in their metadata when
paging applies, and `Pager` does not implement `Serializable` — the clone throws, and the
response degrades to an empty grid. Triggered on any backend (PostgreSQL included) whenever
the analytics cache is enabled (the demo database enables it via system settings).

**Workaround**: set the `keyCacheStrategy` system setting to `NO_CACHE`.

**Status**: needs a fix in upstream dhis2-core (make `Pager` serializable, or clone without
Java serialization); kept out of this branch since it is unrelated to the DuckDB backend.

## First startup downloads the `postgres` extension (documented constraint)

The `postgres` extension used to attach the transaction database is not bundled with the
DuckDB JDBC jar; `install postgres` downloads it from the DuckDB extension repository on
first use. A fresh DuckDB home with restricted outbound network fails at attach time.

- Requires (once): outbound network access and a writable extension directory (default
  `~/.duckdb`).
- Air-gapped deployments: pre-install the extension (e.g. run `INSTALL postgres` via the
  DuckDB CLI on a machine with access and ship the extension directory).
- `AnalyticsDatabaseInit.initDuckDb` runs a probe query through the pool so this failure
  surfaces at startup with a clear cause instead of at the first analytics export.

## Swap errors are swallowed (upstream pattern, watch item)

Analytics table swap errors are swallowed by `executeSilently` in
`AbstractJdbcTableManager` (upstream pattern, all backends). On an embedded engine with
transactional catalog semantics, a swap racing a concurrent query could silently leave a
stale table. Watch the logs when diagnosing unexpected query results during exports.

## Geospatial event clustering emits PostGIS SQL without a capability guard (latent)

`JdbcEventAnalyticsManager.getEventClusters(...)` generates PostGIS-only SQL (`ST_Extent`,
`ST_SnapToGrid`, `ST_Transform`, ...) without checking `supportsGeospatialData()`. On DuckDB
this is effectively unreachable — geospatial support is disabled, so analytics tables carry
no geometry columns and geometry-bearing queries cannot be composed — but the method itself
is unguarded. If map clustering ever becomes reachable on a non-PostGIS backend, guard the
endpoint on `supportsGeospatialData()` and return an unsupported-feature error. (Same
exposure exists for ClickHouse/Doris upstream.)

## Gotchas that look like bugs but aren't

- **"Leftover" `_temp` staging tables when inspecting the `.duckdb` file externally**: the
  file alone is not the full database state — un-checkpointed catalog changes (e.g. the
  final rename of the last-processed table type) live in `analytics.duckdb.wal`. Copy both
  files when inspecting a live instance, or the catalog appears stale.
- **Tracked-entity analytics ignoring DuckDB entirely**: the three TE table managers are
  wired with `analyticsPostgresJdbcTemplate` and `postgresSqlBuilder` by upstream design —
  TE analytics tables always live in PostgreSQL regardless of the configured analytics
  database (also true for ClickHouse/Doris). Explains Postgres-style index creation and
  `Master table exists: 'true'` for TE tables during exports.
- **Enrollment queries returning 0 rows for `LAST_5_YEARS`** on the demo database: correct
  behavior — the demo dataset's enrollments are dated 2026–2027.
