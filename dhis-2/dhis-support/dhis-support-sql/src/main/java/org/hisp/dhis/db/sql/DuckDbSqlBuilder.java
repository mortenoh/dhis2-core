/*
 * Copyright (c) 2004-2024, University of Oslo
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors 
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.hisp.dhis.db.sql;

import org.hisp.dhis.db.model.Database;
import org.hisp.dhis.db.model.Index;

/**
 * Implementation of {@link SqlBuilder} for DuckDB.
 *
 * <p>DuckDB's SQL dialect is largely PostgreSQL-compatible, so this extends {@link
 * PostgreSqlBuilder} and overrides only what differs. Source tables are read from the PostgreSQL
 * transaction database attached read-only as {@code pg} via the DuckDB {@code postgres} extension
 * (see {@code AnalyticsDatabaseInit.initDuckDb}).
 *
 * <p>DuckDB is an embedded, in-process columnar engine (one file or {@code :memory:}, no server).
 * This backend targets <b>simplicity, not scale</b>: testing/CI (execute analytics SQL against a
 * real engine with no infrastructure), local development (the full alternate-backend path without a
 * cluster), and small/single-node databases (demos, training, pilots). It is not intended for large
 * or horizontally-scaled production analytics — a {@code .duckdb} file is single-writer (so it
 * cannot be shared read-write across app nodes), and the embedded engine shares host RAM with the
 * JVM. Use ClickHouse/Doris when scale or clustering is required.
 *
 * <p>Geospatial support is intentionally disabled for this backend ({@link
 * #supportsGeospatialData()} returns false), matching Doris/ClickHouse.
 */
public class DuckDbSqlBuilder extends PostgreSqlBuilder {

  /** Alias of the attached source PostgreSQL database. */
  public static final String SOURCE_ALIAS = "pg";

  @Override
  public Database getDatabase() {
    return Database.DUCKDB;
  }

  @Override
  public boolean isHighPerformance() {
    return true;
  }

  /**
   * Resolves a table reference. Generated analytics and resource tables (all prefixed {@code
   * analytics}) are owned by the local DuckDB database and must be referenced unqualified — they
   * are created and written there. Every other name is a DHIS2 source table read from the attached,
   * read-only PostgreSQL database {@code pg} (postgres_scanner). Qualifying an owned table to
   * {@code pg} would target the read-only source (e.g. the partial-update delete in {@code
   * removeUpdatedData}) and fail.
   */
  @Override
  public String qualifyTable(String name) {
    if (name.startsWith("analytics")) {
      return quote(name);
    }
    return String.format("%s.public.%s", SOURCE_ALIAS, quote(name));
  }

  // Capabilities that differ from Postgres

  @Override
  public boolean supportsAnalyze() {
    return false; // DuckDB maintains statistics automatically
  }

  @Override
  public boolean supportsVacuum() {
    return false;
  }

  /** DuckDB has no logged/unlogged distinction; emit plain {@code create table}. */
  @Override
  public boolean supportsUnloggedTables() {
    return false;
  }

  @Override
  public boolean requiresIndexesForAnalytics() {
    return false; // columnar storage + zonemaps; no secondary indexes needed
  }

  @Override
  public boolean supportsGeospatialData() {
    return false; // first cut: defer the spatial extension
  }

  /**
   * DuckDB has no table inheritance; returning true avoids the Postgres {@code inherits(...)} path
   * in {@code createTable}. TODO verify against the analytics table managers' partition handling.
   */
  @Override
  public boolean supportsDeclarativePartitioning() {
    return true;
  }

  // Data types that differ from Postgres

  @Override
  public String dataTypeJson() {
    return "JSON";
  }

  @Override
  public String dataTypeGeometry() {
    return "VARCHAR"; // geometry stored as text while geospatial support is off
  }

  @Override
  public String dataTypeGeometryPoint() {
    return "VARCHAR";
  }

  /** DuckDB rejects Postgres's {@code using btree(...)}; analytics does not require indexes. */
  @Override
  public String createIndex(Index index) {
    return notSupported();
  }

  /**
   * DuckDB has no {@code ~} / {@code ~*} regex operators; it uses the {@code regexp_matches}
   * function (returns boolean). Pattern matching is case-sensitive like Postgres {@code ~}.
   */
  @Override
  public String regexpMatch(String value, String pattern) {
    return String.format("regexp_matches(%s, %s)", value, pattern);
  }

  /**
   * DuckDB has no {@code ->>} text operator with a bare key nor the {@code #>>} jsonb path; it uses
   * {@code json_extract_string(json, '$.path')} (returns VARCHAR, parsing VARCHAR input as JSON).
   */
  @Override
  public String jsonExtract(String json, String property) {
    return String.format("json_extract_string(%s, '$.%s')", json, property);
  }

  @Override
  public String jsonExtract(String json, String key, String property) {
    return String.format("json_extract_string(%s, '$.%s.%s')", json, key, property);
  }
}
