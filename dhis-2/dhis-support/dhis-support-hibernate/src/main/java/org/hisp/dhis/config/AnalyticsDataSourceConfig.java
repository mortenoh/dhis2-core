/*
 * Copyright (c) 2004-2023, University of Oslo
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
package org.hisp.dhis.config;

import static org.hisp.dhis.config.DataSourceConfig.createProxyDataSource;
import static org.hisp.dhis.datasource.DatabasePoolUtils.ConfigKeyMapper.ANALYTICS;
import static org.hisp.dhis.external.conf.ConfigurationKey.ANALYTICS_CONNECTION_URL;
import static org.hisp.dhis.external.conf.ConfigurationKey.ANALYTICS_DATABASE;

import com.google.common.base.MoreObjects;
import io.micrometer.core.instrument.MeterRegistry;
import java.beans.PropertyVetoException;
import java.sql.SQLException;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hisp.dhis.commons.util.DebugUtils;
import org.hisp.dhis.commons.util.TextUtils;
import org.hisp.dhis.datasource.DatabasePoolUtils;
import org.hisp.dhis.datasource.ReadOnlyDataSourceManager;
import org.hisp.dhis.datasource.model.DbPoolConfig;
import org.hisp.dhis.db.model.Database;
import org.hisp.dhis.db.setting.SqlBuilderSettings;
import org.hisp.dhis.db.sql.DuckDbSqlBuilder;
import org.hisp.dhis.db.util.JdbcUtils;
import org.hisp.dhis.external.conf.ConfigurationKey;
import org.hisp.dhis.external.conf.DhisConfigurationProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class AnalyticsDataSourceConfig {

  private static final int FETCH_SIZE = 1000;

  private final DhisConfigurationProvider config;

  private final SqlBuilderSettings sqlBuilderSettings;

  private final MeterRegistry meterRegistry;

  @Bean("analyticsDataSource")
  @DependsOn("analyticsActualDataSource")
  public DataSource jdbcDataSource(
      @Qualifier("analyticsActualDataSource") DataSource actualDataSource) {
    return createProxyDataSource(config, actualDataSource);
  }

  /**
   * Creates a DataSource for the analytics database. If the analytics database is not configured,
   * the actualDataSource is returned. If the analytics database is configured, a new DataSource is
   * created based on the configuration.
   *
   * @param actualDataSource the actual DataSource
   * @return a DataSource
   */
  @Bean("analyticsActualDataSource")
  public DataSource jdbcActualDataSource(
      @Qualifier("actualDataSource") DataSource actualDataSource) {
    if (config.isAnalyticsDatabaseConfigured()) {
      log.info(
          "Analytics database detected: '{}', connection URL: '{}'",
          config.getProperty(ANALYTICS_DATABASE),
          config.getProperty(ANALYTICS_CONNECTION_URL));

      return getAnalyticsDataSource();
    } else {
      log.info(
          "Analytics database connection URL not specified with key: '{}'",
          ANALYTICS_CONNECTION_URL.getKey());

      return actualDataSource;
    }
  }

  @Bean("analyticsNamedParameterJdbcTemplate")
  @DependsOn("analyticsDataSource")
  public NamedParameterJdbcTemplate namedParameterJdbcTemplate(
      @Qualifier("analyticsDataSource") DataSource dataSource) {
    return new NamedParameterJdbcTemplate(dataSource);
  }

  @Bean("executionPlanJdbcTemplate")
  @DependsOn("analyticsDataSource")
  public JdbcTemplate executionPlanJdbcTemplate(
      @Qualifier("analyticsDataSource") DataSource dataSource) {
    return getJdbcTemplate(dataSource);
  }

  @Bean("analyticsReadOnlyJdbcTemplate")
  @DependsOn("analyticsDataSource")
  public JdbcTemplate readOnlyJdbcTemplate(
      @Qualifier("analyticsDataSource") DataSource dataSource) {
    ReadOnlyDataSourceManager manager = new ReadOnlyDataSourceManager(config, meterRegistry);
    DataSource ds = MoreObjects.firstNonNull(manager.getReadOnlyDataSource(), dataSource);
    return getJdbcTemplate(ds);
  }

  @Bean("analyticsJdbcTemplate")
  @DependsOn("analyticsDataSource")
  public JdbcTemplate jdbcTemplate(@Qualifier("analyticsDataSource") DataSource dataSource) {
    return getJdbcTemplate(dataSource);
  }

  @Bean("analyticsPostgresJdbcTemplate")
  public JdbcTemplate analyticsPostgresJdbcTemplate(
      @Qualifier("actualDataSource") DataSource dataSource) {
    return getJdbcTemplate(dataSource);
  }

  /**
   * Creates a Postgres-specific read-only JdbcTemplate for the analytics database. This is required
   * for analytics operations that can't be performed against the configured analytics database,
   * such as ClickHouse or Doris.
   *
   * @param dataSource the actual data source
   * @return a JdbcTemplate for the analytics database
   */
  @Bean("analyticsPostgresReadOnlyJdbcTemplate")
  public JdbcTemplate readOnlyPostgresJdbcTemplate(
      @Qualifier("actualDataSource") DataSource dataSource) {
    ReadOnlyDataSourceManager manager = new ReadOnlyDataSourceManager(config, meterRegistry);
    DataSource ds = MoreObjects.firstNonNull(manager.getReadOnlyDataSource(), dataSource);
    return getJdbcTemplate(ds);
  }

  // -------------------------------------------------------------------------
  // Supportive methods
  // -------------------------------------------------------------------------

  /**
   * Returns a data source for the analytics database.
   *
   * @return a {@link DataSource}.
   */
  private DataSource getAnalyticsDataSource() {
    final Database database = sqlBuilderSettings.getAnalyticsDatabase();
    final String jdbcUrl =
        withClickHouseConnectionSettings(database, config.getProperty(ANALYTICS_CONNECTION_URL));
    final String driverClassName = getDriverClassName();
    final String dbPoolType = config.getProperty(ConfigurationKey.DB_POOL_TYPE);

    DbPoolConfig.DbPoolConfigBuilder poolConfigBuilder =
        DbPoolConfig.builder("analytics")
            .driverClassName(driverClassName)
            .jdbcUrl(jdbcUrl)
            .dhisConfig(config)
            .mapper(ANALYTICS)
            .dbPoolType(dbPoolType);

    // Embedded DuckDB: ATTACH + session settings are per-connection/instance state, so they must
    // run on every physical pool connection (not once at startup), or queries against the attached
    // source ('pg') fail on connections that never ran the init.
    if (database == Database.DUCKDB) {
      validateDuckDbUrl(jdbcUrl);
      poolConfigBuilder.connectionInitSql(buildDuckDbConnectionInitSql());
    }

    DbPoolConfig poolConfig = poolConfigBuilder.build();

    try {
      return DatabasePoolUtils.createDbPool(poolConfig, meterRegistry);
    } catch (SQLException | PropertyVetoException ex) {
      String message =
          TextUtils.format(
              "Connection test failed for analytics database pool, JDBC URL: '{}'", jdbcUrl);

      log.error(message);
      log.error(DebugUtils.getStackTrace(ex));

      throw new IllegalStateException(message, ex);
    }
  }

  /**
   * Returns a {@link JdbcTemplate}.
   *
   * @param dataSource the {@link DataSource}.
   * @return a {@link JdbcTemplate}.
   */
  private JdbcTemplate getJdbcTemplate(DataSource dataSource) {
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    jdbcTemplate.setFetchSize(FETCH_SIZE);
    return jdbcTemplate;
  }

  /**
   * Appends ClickHouse-specific connection settings to the analytics JDBC URL. Sets the {@code
   * join_use_nulls} server setting to {@code 1} so that unmatched LEFT JOIN cells yield {@code
   * NULL} instead of the column type default (e.g. {@code 0} for numbers), matching PostgreSQL
   * semantics. The {@code clickhouse_setting_} prefix is required by the ClickHouse JDBC driver to
   * forward an arbitrary server setting; an unprefixed key is rejected as an unknown config
   * property. Returns the URL unchanged for other databases or when the setting is already present.
   *
   * @param database the analytics {@link Database}.
   * @param jdbcUrl the configured analytics JDBC URL.
   * @return the JDBC URL with ClickHouse settings applied when applicable.
   */
  static String withClickHouseConnectionSettings(Database database, String jdbcUrl) {
    String setting = "clickhouse_setting_join_use_nulls=1";

    if (database != Database.CLICKHOUSE
        || jdbcUrl == null
        || jdbcUrl.contains("clickhouse_setting_join_use_nulls=")) {
      return jdbcUrl;
    }

    // A trailing '?' or '&' already starts the query string, so appending another separator would
    // create an empty parameter that the driver rejects.
    if (jdbcUrl.endsWith("?") || jdbcUrl.endsWith("&")) {
      return jdbcUrl + setting;
    }

    String separator = jdbcUrl.contains("?") ? "&" : "?";
    return jdbcUrl + separator + setting;
  }

  /**
   * Returns a driver class name based on the specified analytics database.
   *
   * @return a driver class name.
   */
  private String getDriverClassName() {
    final Database database = sqlBuilderSettings.getAnalyticsDatabase();
    return switch (database) {
      case POSTGRESQL -> org.postgresql.Driver.class.getName();
      case DORIS -> com.mysql.cj.jdbc.Driver.class.getName();
      case CLICKHOUSE -> com.clickhouse.jdbc.ClickHouseDriver.class.getName();
      case DUCKDB -> org.duckdb.DuckDBDriver.class.getName();
    };
  }

  /**
   * Builds the per-connection init SQL for an embedded DuckDB analytics datasource: load the {@code
   * postgres} extension, attach the source DHIS2 PostgreSQL database read-only as {@code pg}, and
   * apply the embedded-engine memory/spill settings. Run on every physical connection via Hikari
   * {@code connectionInitSql} because none of this is persisted to the {@code .duckdb} file.
   */
  private String buildDuckDbConnectionInitSql() {
    String pgUrl = config.getProperty(ConfigurationKey.CONNECTION_URL);
    String host = JdbcUtils.getHostFromUrl(pgUrl);
    int port = JdbcUtils.getPortFromUrl(pgUrl, JdbcUtils.POSTGRESQL_PORT);
    String database = JdbcUtils.getDatabaseFromUrl(pgUrl);
    String username = config.getProperty(ConfigurationKey.CONNECTION_USERNAME);
    String password = config.getProperty(ConfigurationKey.CONNECTION_PASSWORD);

    return DuckDbSqlBuilder.connectionInitSql(
        host, port, database, username, password, duckDbMemoryLimitMb(), duckDbTempDirectory());
  }

  /**
   * Caps the embedded DuckDB memory limit so it coexists with the JVM heap: 75% of (physical RAM -
   * JVM max heap), floored at 512 MB. Returns null (engine default) if host memory can't be read.
   */
  private Long duckDbMemoryLimitMb() {
    try {
      com.sun.management.OperatingSystemMXBean os =
          (com.sun.management.OperatingSystemMXBean)
              java.lang.management.ManagementFactory.getOperatingSystemMXBean();
      long available = os.getTotalMemorySize() - Runtime.getRuntime().maxMemory();
      return Math.max((long) (available * 0.75) / (1024 * 1024), 512);
    } catch (Exception ex) {
      log.warn("Could not determine host memory; leaving DuckDB memory limit at default", ex);
      return null;
    }
  }

  /** Derives a disk spill directory from a file-backed DuckDB URL; null for in-memory. */
  private String duckDbTempDirectory() {
    String analyticsUrl = config.getProperty(ANALYTICS_CONNECTION_URL);
    String prefix = "jdbc:duckdb:";
    if (analyticsUrl != null && analyticsUrl.startsWith(prefix)) {
      String file = analyticsUrl.substring(prefix.length());
      if (!file.isBlank() && !file.startsWith(":")) { // skip in-memory (:memory:)
        return file + ".tmp";
      }
    }
    return null;
  }

  /**
   * Rejects in-memory DuckDB URLs for the analytics datasource. Each in-memory JDBC connection owns
   * its own private database, so behind a connection pool every pooled connection would see a
   * different, unrelated database: tables created and populated through one connection are
   * invisible to queries running on another. Only a file-backed database gives all pooled
   * connections a shared view. (In-memory remains fine for single-connection use such as unit
   * tests, which do not go through this datasource.)
   *
   * @param jdbcUrl the analytics JDBC URL.
   * @throws IllegalStateException if the URL denotes an in-memory DuckDB database.
   */
  private void validateDuckDbUrl(String jdbcUrl) {
    String prefix = "jdbc:duckdb:";
    boolean fileBacked = false;
    if (jdbcUrl != null && jdbcUrl.startsWith(prefix)) {
      String file = jdbcUrl.substring(prefix.length());
      fileBacked = !file.isBlank() && !file.startsWith(":");
    }
    if (!fileBacked) {
      throw new IllegalStateException(
          TextUtils.format(
              "DuckDB analytics requires a file-backed database URL "
                  + "(e.g. 'jdbc:duckdb:/path/to/analytics.duckdb'). In-memory databases are "
                  + "private to a single connection and cannot be shared across the connection "
                  + "pool. Configured URL: '{}'",
              jdbcUrl));
    }
  }
}
