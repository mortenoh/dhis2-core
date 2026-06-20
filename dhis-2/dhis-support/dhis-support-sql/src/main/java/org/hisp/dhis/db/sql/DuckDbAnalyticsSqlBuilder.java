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
 * Implementation of {@link AnalyticsSqlBuilder} for DuckDB.
 *
 * <p>Extends {@link PostgreSqlAnalyticsSqlBuilder} (not {@link DuckDbSqlBuilder}) so the large
 * PostgreSQL period-bucket block in {@code renderDateFieldPeriodBucketDate(...)} plus {@code
 * renderTimestamp} / {@code castAsDate} / {@code nullIfEmpty} are inherited verbatim — DuckDB
 * accepts that SQL. The handful of DuckDB-divergent base methods are re-applied here (they mirror
 * {@link DuckDbSqlBuilder}; Java single inheritance makes the duplication hard to avoid without
 * refactoring the base classes).
 */
public class DuckDbAnalyticsSqlBuilder extends PostgreSqlAnalyticsSqlBuilder {

  public static final String SOURCE_ALIAS = "pg";

  // --- same DuckDB base overrides as DuckDbSqlBuilder (single-inheritance duplication) ---

  @Override
  public Database getDatabase() {
    return Database.DUCKDB;
  }

  @Override
  public boolean isHighPerformance() {
    return true;
  }

  @Override
  public boolean supportsAnalyze() {
    return false;
  }

  @Override
  public boolean supportsVacuum() {
    return false;
  }

  @Override
  public boolean requiresIndexesForAnalytics() {
    return false;
  }

  @Override
  public boolean supportsGeospatialData() {
    return false;
  }

  @Override
  public boolean supportsDeclarativePartitioning() {
    return true;
  }

  @Override
  public String dataTypeJson() {
    return "JSON";
  }

  @Override
  public String dataTypeGeometry() {
    return "VARCHAR";
  }

  @Override
  public String dataTypeGeometryPoint() {
    return "VARCHAR";
  }

  @Override
  public String createIndex(Index index) {
    return notSupported();
  }

  @Override
  public String qualifyTable(String name) {
    return String.format("%s.public.%s", SOURCE_ALIAS, quote(name));
  }

  /**
   * The one genuinely bespoke analytics method. Postgres builds the event-datavalue blob with
   * {@code json_object_agg} / {@code jsonb_object_keys} / {@code ->>}; DuckDB's JSON functions
   * differ ({@code json_group_object}, {@code json_extract_string}). Rewrite this with DuckDB JSON,
   * or run the sub-read on the attached Postgres side via {@code postgres_query(pg, '...')}.
   *
   * <p>TODO: implement the DuckDB JSON rewrite. Aggregate analytics generation works without it;
   * event/tracker analytics will fail here until implemented.
   *
   * <p>{@code renderTimestamp}, {@code renderDateFieldPeriodBucketDate} (all period buckets),
   * {@code castAsDate}, {@code nullIfEmpty}, {@code useJoinForDatePeriodStructureLookup()} are
   * inherited from {@link PostgreSqlAnalyticsSqlBuilder} unchanged.
   */
  @Override
  public String getEventDataValues() {
    return notSupported();
  }
}
