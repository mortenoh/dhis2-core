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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.hisp.dhis.db.model.Database;
import org.hisp.dhis.period.PeriodTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Unit tests for {@link DuckDbAnalyticsSqlBuilder}. */
class DuckDbAnalyticsSqlBuilderTest {
  private final DuckDbAnalyticsSqlBuilder sqlBuilder = new DuckDbAnalyticsSqlBuilder();

  private final PostgreSqlAnalyticsSqlBuilder postgresBuilder = new PostgreSqlAnalyticsSqlBuilder();

  @Test
  void testGetDatabase() {
    assertEquals(Database.DUCKDB, sqlBuilder.getDatabase());
  }

  @Test
  void testJsonExtractKeyAndProperty() {
    assertEquals(
        "json_extract_string(eventdatavalues, '$.GieVkTxp4HH.value')",
        sqlBuilder.jsonExtract("eventdatavalues", "GieVkTxp4HH", "value"));
  }

  @Test
  void testQualifyTableSourceVsOwned() {
    // Source tables -> attached read-only pg; generated analytics tables -> local DuckDB.
    assertEquals("pg.public.\"trackerevent\"", sqlBuilder.qualifyTable("trackerevent"));
    assertEquals("\"analytics_event_2020\"", sqlBuilder.qualifyTable("analytics_event_2020"));
  }

  @Test
  void testDoesNotSupportUnloggedTables() {
    assertFalse(sqlBuilder.supportsUnloggedTables());
  }

  @Test
  void testGetEventDataValuesUsesDuckDbJson() {
    String sql = sqlBuilder.getEventDataValues();

    // DuckDB JSON functions and attached-Postgres qualified source tables.
    assertTrue(sql.contains("json_group_object"), sql);
    assertTrue(sql.contains("json_extract_string"), sql);
    assertTrue(sql.contains("unnest(json_keys"), sql);
    assertTrue(sql.contains("pg.public.\"trackerevent\""), sql);
    assertTrue(sql.contains("pg.public.\"organisationunit\""), sql);

    // None of the Postgres jsonb constructs that DuckDB rejects.
    assertFalse(sql.contains("#>>"), sql);
    assertFalse(sql.contains("json_object_agg"), sql);
    assertFalse(sql.contains("jsonb_object_keys"), sql);
  }

  /**
   * The period-bucket SQL is inherited verbatim from {@link PostgreSqlAnalyticsSqlBuilder}; assert
   * DuckDB produces byte-identical output for every period type.
   */
  @ParameterizedTest
  @EnumSource(PeriodTypeEnum.class)
  void testPeriodBucketsInheritedFromPostgres(PeriodTypeEnum periodType) {
    String field = "ax.\"eventdate\"";
    assertEquals(
        postgresBuilder.renderDateFieldPeriodBucketDate(field, periodType),
        sqlBuilder.renderDateFieldPeriodBucketDate(field, periodType));
  }
}
