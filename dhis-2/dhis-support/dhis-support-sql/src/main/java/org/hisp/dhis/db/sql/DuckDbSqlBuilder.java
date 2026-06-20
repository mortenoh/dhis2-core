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
 * <p>SKELETON: capability flags and types are best-effort first-cut values; verify the {@link
 * #supportsDeclarativePartitioning()} / table-inheritance interaction with the analytics table
 * managers, and keep geospatial disabled for the first cut.
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

  /** Source tables resolve to the attached Postgres database (postgres_scanner). */
  @Override
  public String qualifyTable(String name) {
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
}
