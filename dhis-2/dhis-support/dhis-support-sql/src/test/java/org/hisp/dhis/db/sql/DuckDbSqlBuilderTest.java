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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.hisp.dhis.db.model.Database;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DuckDbSqlBuilder}, covering the DuckDB-divergent overrides. */
class DuckDbSqlBuilderTest {
  private final DuckDbSqlBuilder sqlBuilder = new DuckDbSqlBuilder();

  @Test
  void testGetDatabase() {
    assertEquals(Database.DUCKDB, sqlBuilder.getDatabase());
  }

  @Test
  void testIsHighPerformance() {
    assertTrue(sqlBuilder.isHighPerformance());
  }

  @Test
  void testQualifyTableReferencesAttachedPostgres() {
    assertEquals("pg.public.\"dataelement\"", sqlBuilder.qualifyTable("dataelement"));
  }

  @Test
  void testCapabilities() {
    assertFalse(sqlBuilder.supportsAnalyze());
    assertFalse(sqlBuilder.supportsVacuum());
    assertFalse(sqlBuilder.requiresIndexesForAnalytics());
    assertFalse(sqlBuilder.supportsGeospatialData());
    assertTrue(sqlBuilder.supportsDeclarativePartitioning());
  }

  @Test
  void testDataTypes() {
    assertEquals("JSON", sqlBuilder.dataTypeJson());
    assertEquals("VARCHAR", sqlBuilder.dataTypeGeometry());
    assertEquals("VARCHAR", sqlBuilder.dataTypeGeometryPoint());
  }

  @Test
  void testRegexpMatch() {
    assertEquals(
        "regexp_matches(ax.\"value\", '^[0-9]+$')",
        sqlBuilder.regexpMatch("ax.\"value\"", "'^[0-9]+$'"));
  }

  @Test
  void testJsonExtractProperty() {
    assertEquals(
        "json_extract_string(ev.eventdatavalues, '$.value')",
        sqlBuilder.jsonExtract("ev.eventdatavalues", "value"));
  }

  @Test
  void testJsonExtractKeyAndProperty() {
    assertEquals(
        "json_extract_string(eventdatavalues, '$.GieVkTxp4HH.value')",
        sqlBuilder.jsonExtract("eventdatavalues", "GieVkTxp4HH", "value"));
  }

  @Test
  void testCreateIndexNotSupported() {
    assertThrows(UnsupportedOperationException.class, () -> sqlBuilder.createIndex(null));
  }
}
