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

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.Properties;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * A {@link DataSource} that creates driver-based connections and runs an initialization SQL script
 * on every new connection before handing it out.
 *
 * <p>Exists so per-connection session state (e.g. embedded DuckDB's {@code ATTACH} of the source
 * PostgreSQL database, which embeds credentials) can be established without passing the SQL to the
 * connection pool as configuration: pool implementations log their configuration properties
 * verbatim at DEBUG (HikariCP masks only properties whose names contain {@code password} or {@code
 * jdbcUrl}), which would leak the embedded credentials. Wrapping connection creation keeps the
 * script out of pool configuration entirely — pools log this object only via {@link #toString()},
 * which never includes the script. It also makes initialization pool-agnostic: any pool that
 * accepts a {@link DataSource} for physical connection creation gets initialized connections.
 */
public class ConnectionInitDataSource implements DataSource {

  private final String jdbcUrl;

  private final Properties connectionProperties;

  private final String initSql;

  private PrintWriter logWriter;

  private int loginTimeout;

  /**
   * @param driverClassName JDBC driver class to load, or null to rely on driver auto-registration.
   * @param jdbcUrl the JDBC connection URL.
   * @param username the username, or null/blank for none.
   * @param password the password, or null/blank for none.
   * @param initSql SQL executed on every new connection (may contain multiple {@code ;}-delimited
   *     statements if the driver supports it). Never logged by this class.
   */
  public ConnectionInitDataSource(
      String driverClassName, String jdbcUrl, String username, String password, String initSql) {
    this.jdbcUrl = jdbcUrl;
    this.connectionProperties = new Properties();
    if (username != null && !username.isBlank()) {
      connectionProperties.setProperty("user", username);
    }
    if (password != null && !password.isBlank()) {
      connectionProperties.setProperty("password", password);
    }
    this.initSql = initSql;

    if (driverClassName != null && !driverClassName.isBlank()) {
      try {
        Class.forName(driverClassName);
      } catch (ClassNotFoundException ex) {
        throw new IllegalArgumentException("Could not load JDBC driver: " + driverClassName, ex);
      }
    }
  }

  @Override
  public Connection getConnection() throws SQLException {
    Connection connection = DriverManager.getConnection(jdbcUrl, connectionProperties);
    try (Statement statement = connection.createStatement()) {
      statement.execute(initSql);
    } catch (SQLException ex) {
      try {
        connection.close();
      } catch (SQLException suppressed) {
        ex.addSuppressed(suppressed);
      }
      throw ex;
    }
    return connection;
  }

  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    return getConnection();
  }

  @Override
  public PrintWriter getLogWriter() {
    return logWriter;
  }

  @Override
  public void setLogWriter(PrintWriter out) {
    this.logWriter = out;
  }

  @Override
  public void setLoginTimeout(int seconds) {
    this.loginTimeout = seconds;
  }

  @Override
  public int getLoginTimeout() {
    return loginTimeout;
  }

  @Override
  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    throw new SQLFeatureNotSupportedException();
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    if (iface.isInstance(this)) {
      return iface.cast(this);
    }
    throw new SQLException("Cannot unwrap to " + iface.getName());
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) {
    return iface.isInstance(this);
  }

  /** Deliberately excludes the init script and connection properties (may hold credentials). */
  @Override
  public String toString() {
    return getClass().getSimpleName() + "{jdbcUrl=" + jdbcUrl + "}";
  }
}
