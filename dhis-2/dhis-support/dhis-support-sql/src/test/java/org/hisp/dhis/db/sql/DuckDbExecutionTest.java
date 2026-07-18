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

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Live-execution tests: the SQL produced by the DuckDB builders is run against a real embedded
 * DuckDB in both in-memory and file-backed modes. This is uniquely cheap for DuckDB (no container),
 * and validates that the generated dialect actually executes — not just that the strings look
 * right.
 */
class DuckDbExecutionTest {
  private final DuckDbAnalyticsSqlBuilder sqlBuilder = new DuckDbAnalyticsSqlBuilder();

  @Test
  void testGeneratedSqlExecutesInMemory() throws Exception {
    try (Connection connection = DriverManager.getConnection("jdbc:duckdb::memory:")) {
      assertGeneratedSqlExecutes(connection);
    }
  }

  @Test
  void testGeneratedSqlExecutesOnFile(@TempDir Path dir) throws Exception {
    String url = "jdbc:duckdb:" + dir.resolve("analytics.duckdb");
    try (Connection connection = DriverManager.getConnection(url)) {
      assertGeneratedSqlExecutes(connection);
    }
  }

  /**
   * The partial-update path ({@code removeUpdatedData}) deletes from a generated analytics table,
   * referenced with {@code quote} (local DuckDB table), never {@code qualifyTable} (which always
   * targets the attached read-only {@code pg} source — table replication depends on that).
   * Reproduce that delete shape in-process and assert source resolution stays on {@code pg}.
   */
  @Test
  void testOwnedAnalyticsTableDeleteExecutesLocally() throws Exception {
    try (Connection connection = DriverManager.getConnection("jdbc:duckdb::memory:");
        Statement statement = connection.createStatement()) {
      String owned = sqlBuilder.quote("analytics_event_2020");
      assertEquals("\"analytics_event_2020\"", owned);

      statement.execute("create table " + owned + " (event varchar, lastupdated date)");
      statement.execute(
          "insert into " + owned + " values ('e1', '2020-01-01'), ('e2', '2020-02-01')");
      statement.execute("delete from " + owned + " ax where ax.event = 'e1'");

      try (ResultSet rs = statement.executeQuery("select count(*) as c from " + owned)) {
        assertTrue(rs.next());
        assertEquals(1, rs.getInt("c"));
      }

      // qualifyTable always targets the attached pg source database, even for analytics names —
      // table replication copies pg-side analytics_rs_* tables into local tables of the same name.
      assertTrue(sqlBuilder.qualifyTable("datavalue").startsWith("pg."));
      assertEquals(
          "pg.public.\"analytics_rs_periodstructure\"",
          sqlBuilder.qualifyTable("analytics_rs_periodstructure"));
    }
  }

  /**
   * The swap path decides "master table exists" through {@code tableExists}. Local DuckDB tables
   * live in schema {@code main}, so the inherited PostgreSQL check ({@code table_schema =
   * 'public'}) always came back empty. Assert the generated check finds a local table, and the
   * multi-statement swap (drop + rename) leaves exactly the main table.
   */
  @Test
  void testTableExistsAndSwapExecuteLocally() throws Exception {
    try (Connection connection = DriverManager.getConnection("jdbc:duckdb::memory:");
        Statement statement = connection.createStatement()) {
      statement.execute("create table \"analytics_2024_temp\" (i integer)");

      try (ResultSet rs = statement.executeQuery(sqlBuilder.tableExists("analytics_2024_temp"))) {
        assertTrue(rs.next(), "tableExists must find a local table");
      }
      try (ResultSet rs = statement.executeQuery(sqlBuilder.tableExists("analytics_2024"))) {
        assertFalse(rs.next(), "tableExists must not find a missing table");
      }

      // The exact multi-statement swap shape from AbstractSqlBuilder#swapTable.
      statement.execute(
          "drop table if exists \"analytics_2024\" cascade; "
              + "alter table \"analytics_2024_temp\" rename to \"analytics_2024\";");

      try (ResultSet rs = statement.executeQuery(sqlBuilder.tableExists("analytics_2024"))) {
        assertTrue(rs.next(), "swap must produce the main table");
      }
      try (ResultSet rs = statement.executeQuery(sqlBuilder.tableExists("analytics_2024_temp"))) {
        assertFalse(rs.next(), "swap must remove the staging table");
      }
    }
  }

  /**
   * Runs the builder-generated jsonExtract and regexpMatch expressions against {@code connection}.
   */
  private void assertGeneratedSqlExecutes(Connection connection) throws Exception {
    try (Statement statement = connection.createStatement()) {
      statement.execute("create table event (uid varchar, eventdatavalues json)");
      statement.execute(
          "insert into event values "
              + "('e1', '{\"GieVkTxp4HH\": {\"value\": \"42\", \"created\": \"2020-01-01\"}}')");

      // jsonExtract(json, key, property) -> json_extract_string(json, '$.key.property')
      String extract = sqlBuilder.jsonExtract("eventdatavalues", "GieVkTxp4HH", "value");
      try (ResultSet rs = statement.executeQuery("select " + extract + " as v from event")) {
        assertTrue(rs.next());
        assertEquals("42", rs.getString("v"));
      }

      // jsonExtract(json, property) on a nested object extracted as text
      String extractTop = sqlBuilder.jsonExtract("eventdatavalues", "GieVkTxp4HH");
      try (ResultSet rs =
          statement.executeQuery("select " + extractTop + " is not null as v from event")) {
        assertTrue(rs.next());
        assertTrue(rs.getBoolean("v"));
      }

      // regexpMatch -> regexp_matches(value, pattern); numeric value matches, non-numeric does not
      String numeric = sqlBuilder.regexpMatch("'42'", "'^[0-9]+$'");
      try (ResultSet rs = statement.executeQuery("select " + numeric + " as m")) {
        assertTrue(rs.next());
        assertTrue(rs.getBoolean("m"));
      }
      String nonNumeric = sqlBuilder.regexpMatch("'4a2'", "'^[0-9]+$'");
      try (ResultSet rs = statement.executeQuery("select " + nonNumeric + " as m")) {
        assertTrue(rs.next());
        assertFalse(rs.getBoolean("m"));
      }
    }
  }
}
