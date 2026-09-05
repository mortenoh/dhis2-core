/*
 * Copyright (c) 2004-2026, University of Oslo
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

import java.util.Optional;
import org.hisp.dhis.period.PeriodTypeEnum;

/**
 * Implementation of {@link AnalyticsSqlBuilder} for DuckDB.
 *
 * <p>Extends {@link DuckDbSqlBuilder} so the dialect divergences (source qualification, regex, JSON
 * extraction, capability flags) are inherited rather than restated, matching how {@link
 * ClickHouseAnalyticsSqlBuilder} and {@link DorisAnalyticsSqlBuilder} are built.
 *
 * <p>The PostgreSQL period-bucket SQL is reached through a delegate instead of a superclass: DuckDB
 * accepts all of it except the {@code BI_MONTHLY} bucket (overridden below), and the block is pure
 * string formatting with no state, so delegating is equivalent to inheriting it. Java single
 * inheritance allows only one of the two parents, and inheriting the dialect is what keeps this
 * class small.
 *
 * <p>See {@link DuckDbSqlBuilder} for this backend's intended use — testing/CI, local development,
 * and small/single-node databases rather than large or clustered production analytics.
 */
public class DuckDbAnalyticsSqlBuilder extends DuckDbSqlBuilder implements AnalyticsSqlBuilder {

  /** Supplies the period-bucket expressions DuckDB shares with PostgreSQL. See class javadoc. */
  private final PostgreSqlAnalyticsSqlBuilder postgres = new PostgreSqlAnalyticsSqlBuilder();

  /** DuckDB renders timestamps exactly as PostgreSQL does — the literal is passed through. */
  @Override
  public String renderTimestamp(String timestampAsString) {
    return timestampAsString;
  }

  /**
   * PostgreSQL's {@code /} on integers truncates, so its BI_MONTHLY expression relies on {@code
   * (month - 1) / 2} being integral. DuckDB's {@code /} always yields DOUBLE, which {@code
   * make_date(int, ..., int)} rejects with a binder error; DuckDB's integer division operator is
   * {@code //}. Every other period-bucket expression executes unchanged on DuckDB and is taken from
   * the PostgreSQL builder (covered by {@code DuckDbExecutionTest}).
   */
  @Override
  public Optional<String> renderDateFieldPeriodBucketDate(
      String dateColumn, PeriodTypeEnum periodType) {
    if (periodType == PeriodTypeEnum.BI_MONTHLY) {
      return Optional.of(
          "make_date( extract(year from %1$s)::int, ((extract(month from %1$s)::int - 1) // 2) * 2 + 1, 1 )"
              .formatted(dateColumn));
    }
    return postgres.renderDateFieldPeriodBucketDate(dateColumn, periodType);
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
