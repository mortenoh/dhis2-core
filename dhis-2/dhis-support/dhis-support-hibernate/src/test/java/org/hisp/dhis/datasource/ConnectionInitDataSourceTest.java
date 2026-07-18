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
package org.hisp.dhis.datasource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link ConnectionInitDataSource} against embedded DuckDB, including through a real HikariCP
 * pool — the production wiring for the DuckDB analytics backend, where the init script (which
 * embeds source database credentials) must run on every physical connection without ever appearing
 * in pool configuration.
 */
class ConnectionInitDataSourceTest {

  @Test
  void testInitSqlRunsOnPooledConnections(@TempDir Path dir) throws Exception {
    String url = "jdbc:duckdb:" + dir.resolve("test.duckdb");
    ConnectionInitDataSource dataSource =
        new ConnectionInitDataSource(
            org.duckdb.DuckDBDriver.class.getName(),
            url,
            null,
            null,
            // Multi-statement, like the DuckDB attach + settings script
            "create table if not exists init_marker (i integer); "
                + "set preserve_insertion_order = false;");

    HikariConfig hc = new HikariConfig();
    hc.setPoolName("test-init");
    hc.setDataSource(dataSource);
    hc.setMaximumPoolSize(2);

    try (HikariDataSource pool = new HikariDataSource(hc);
        Connection connection = pool.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "select count(*) from information_schema.tables "
                    + "where table_name = 'init_marker'")) {
      assertTrue(rs.next());
      assertEquals(1, rs.getInt(1), "init SQL must have run on the pooled connection");
    }
  }

  @Test
  void testFailingInitSqlClosesAndPropagates(@TempDir Path dir) {
    String url = "jdbc:duckdb:" + dir.resolve("test.duckdb");
    ConnectionInitDataSource dataSource =
        new ConnectionInitDataSource(null, url, null, null, "select * from does_not_exist;");

    assertThrows(SQLException.class, dataSource::getConnection);
  }

  @Test
  void testToStringExcludesInitSqlAndCredentials(@TempDir Path dir) {
    String url = "jdbc:duckdb:" + dir.resolve("test.duckdb");
    ConnectionInitDataSource dataSource =
        new ConnectionInitDataSource(
            null, url, "user", "s3cret-password", "attach 'password=s3cret-password' as pg;");

    String repr = dataSource.toString();
    assertFalse(repr.contains("s3cret-password"), repr);
    assertFalse(repr.contains("attach"), repr);
  }
}
