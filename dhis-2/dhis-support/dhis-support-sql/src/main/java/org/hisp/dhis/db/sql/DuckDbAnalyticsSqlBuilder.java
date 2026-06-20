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

  /** DuckDB uses regexp_matches(...) rather than the Postgres {@code ~} / {@code ~*} operators. */
  @Override
  public String regexpMatch(String value, String pattern) {
    return String.format("regexp_matches(%s, %s)", value, pattern);
  }

  /** DuckDB JSON extraction: {@code json_extract_string(json, '$.path')} instead of ->> / #>>. */
  @Override
  public String jsonExtract(String json, String property) {
    return String.format("json_extract_string(%s, '$.%s')", json, property);
  }

  @Override
  public String jsonExtract(String json, String key, String property) {
    return String.format("json_extract_string(%s, '$.%s.%s')", json, key, property);
  }

  /**
   * DuckDB rewrite of the Postgres event-datavalue blob. Postgres uses {@code json_object_agg} /
   * {@code jsonb_object_keys} / {@code -> ->>}; the DuckDB equivalents are {@code
   * json_group_object} / {@code unnest(json_keys(...))} / {@code json_extract_string(json,
   * '$.key.prop')}. Source tables are qualified to the attached {@code pg} database; the shape
   * matches the Postgres column so downstream queries are unaffected.
   */
  @Override
  public String getEventDataValues() {
    return """
        (select json_group_object(l2.keys, l2.datavalue) as value
        from (
            select l1.uid,
            l1.keys,
            json_object(
            'value', json_extract_string(l1.eventdatavalues, '$.' || l1.keys || '.value'),
            'created', json_extract_string(l1.eventdatavalues, '$.' || l1.keys || '.created'),
            'lastUpdated', json_extract_string(l1.eventdatavalues, '$.' || l1.keys || '.lastUpdated'),
            'providedElsewhere', json_extract(l1.eventdatavalues, '$.' || l1.keys || '.providedElsewhere'),
            'value_name', (select ou.name
                from %1$s ou
                where ou.uid = json_extract_string(l1.eventdatavalues, '$.' || l1.keys || '.value')),
            'value_code', (select ou.code
                from %1$s ou
                where ou.uid = json_extract_string(l1.eventdatavalues, '$.' || l1.keys || '.value'))) as datavalue
            from (select inner_evt.*, unnest(json_keys(inner_evt.eventdatavalues)) keys
            from %2$s inner_evt) as l1) as l2
        where l2.uid = ev.uid
        group by l2.uid)::JSON"""
        .formatted(qualifyTable("organisationunit"), qualifyTable("trackerevent"));
  }
}
