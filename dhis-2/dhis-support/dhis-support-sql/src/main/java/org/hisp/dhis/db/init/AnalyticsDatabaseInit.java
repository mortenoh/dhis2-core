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
package org.hisp.dhis.db.init;

import static org.hisp.dhis.db.sql.ClickHouseSqlBuilder.NAMED_COLLECTION;

import java.util.Map;
import javax.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.hisp.dhis.db.SqlBuilderProvider;
import org.hisp.dhis.db.model.Database;
import org.hisp.dhis.db.setting.SqlBuilderSettings;
import org.hisp.dhis.db.sql.ClickHouseSqlBuilder;
import org.hisp.dhis.db.sql.DorisSqlBuilder;
import org.hisp.dhis.db.sql.SqlBuilder;
import org.hisp.dhis.db.util.JdbcUtils;
import org.hisp.dhis.external.conf.ConfigurationKey;
import org.hisp.dhis.external.conf.DhisConfigurationProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Class responsible for performing work for initialization of an analytics database.
 *
 * <p>The following steps are required to introduce a new analytics database platform.
 *
 * <ul>
 *   <li>Add value to enum {@link Database}
 *   <li>Add implementation of interface {@link SqlBuilder}
 *   <li>Add entry to switch statement in {@link SqlBuilderProvider}
 *   <li>Add method to {@link AnalyticsDatabaseInit} if necessary
 * </ul>
 *
 * @author Lars Helge Overland
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalyticsDatabaseInit {
  private final DhisConfigurationProvider config;

  private final SqlBuilderSettings settings;

  @Qualifier("analyticsJdbcTemplate")
  private final JdbcTemplate jdbcTemplate;

  private final SqlBuilder sqlBuilder;

  @PostConstruct
  public void init() {
    if (!config.isAnalyticsDatabaseConfigured()) {
      return;
    }

    Database database = settings.getAnalyticsDatabase();

    switch (database) {
      case POSTGRESQL -> initPostgreSql();
      case DORIS -> initDoris();
      case CLICKHOUSE -> initClickHouse();
      case DUCKDB -> initDuckDb();
    }

    String url = settings.getAnalyticsConnectionUrl();

    log.info("Analytics database initialized: '{}', connection URL: '{}'", database, url);
  }

  /** Work for initializing a PostgreSQL analytics database. */
  private void initPostgreSql() {
    // No work yet
  }

  /** Work for initializing a Doris analytics database. */
  private void initDoris() {
    createDorisJdbcCatalog();
  }

  /** Work for initializing a ClickHouse analytics database. */
  private void initClickHouse() {
    createClickHouseNamedCollection();
  }

  /**
   * Work for initializing a DuckDB analytics database. Loads the {@code postgres} extension and
   * attaches the source DHIS2 PostgreSQL database read-only as {@code pg}, so analytics generation
   * can read the source tables (qualified as {@code pg.public.<table>}).
   */
  private void initDuckDb() {
    String jdbcUrl = config.getConnectionUrl();
    String host = JdbcUtils.getHostFromUrl(jdbcUrl);
    int port = JdbcUtils.getPortFromUrl(jdbcUrl, JdbcUtils.POSTGRESQL_PORT);
    String database = JdbcUtils.getDatabaseFromUrl(jdbcUrl);
    String username = config.getProperty(ConfigurationKey.CONNECTION_USERNAME);
    String password = config.getProperty(ConfigurationKey.CONNECTION_PASSWORD);

    jdbcTemplate.execute("install postgres;");
    jdbcTemplate.execute("load postgres;");
    jdbcTemplate.execute("detach database if exists pg;");

    String dsn =
        String.format(
            "host=%s port=%d dbname=%s user=%s password=%s",
            host, port, database, username, password);
    jdbcTemplate.execute(String.format("attach '%s' as pg (type postgres, read_only);", dsn));

    // DuckDB runs in-process: by default it sizes its memory limit to ~80% of host RAM,
    // which collides with the JVM heap and OOMs when several large (event) tables build
    // concurrently. Bound it to a share of (physical RAM - JVM max heap) and enable disk
    // spilling so builds complete under memory pressure.
    setDuckDbMemoryLimit();
    // Analytics fact tables are unordered, so don't buffer whole result sets to preserve
    // insertion order; this lets large inserts stream and spill instead of OOMing.
    jdbcTemplate.execute("set preserve_insertion_order = false;");
    String analyticsUrl = settings.getAnalyticsConnectionUrl();
    String prefix = "jdbc:duckdb:";
    if (analyticsUrl != null && analyticsUrl.startsWith(prefix)) {
      String file = analyticsUrl.substring(prefix.length());
      if (!file.isBlank() && !file.startsWith(":")) { // skip in-memory (:memory:)
        jdbcTemplate.execute(String.format("set temp_directory = '%s.tmp';", file));
      }
    }

    log.info("DuckDB attached source PostgreSQL database '{}' at {}:{}", database, host, port);
  }

  /**
   * Caps the embedded DuckDB memory limit so it coexists with the JVM heap. Uses 60% of (physical
   * RAM - JVM max heap), floored at 512 MB. Leaves the DuckDB default in place if RAM can't be
   * determined.
   */
  private void setDuckDbMemoryLimit() {
    try {
      com.sun.management.OperatingSystemMXBean os =
          (com.sun.management.OperatingSystemMXBean)
              java.lang.management.ManagementFactory.getOperatingSystemMXBean();
      long available = os.getTotalMemorySize() - Runtime.getRuntime().maxMemory();
      long limitMb = Math.max((long) (available * 0.75) / (1024 * 1024), 512);
      jdbcTemplate.execute(String.format("set memory_limit = '%dMB';", limitMb));
      log.info("DuckDB memory limit set to {} MB", limitMb);
    } catch (Exception ex) {
      log.warn("Could not determine host memory; leaving DuckDB memory limit at default", ex);
    }
  }

  /**
   * Creates a Doris JDBC catalog which is used to connect to and read from the PostgreSQL
   * transaction database as an external data source.
   */
  private void createDorisJdbcCatalog() {
    String connectionUrl =
        StringUtils.firstNonBlank(
            config.getProperty(ConfigurationKey.DORIS_CATALOG_CONNECTION_URL),
            config.getProperty(ConfigurationKey.CONNECTION_URL));
    String username = config.getProperty(ConfigurationKey.CONNECTION_USERNAME);
    String password = config.getProperty(ConfigurationKey.CONNECTION_PASSWORD);

    DorisSqlBuilder dorisSqlBuilder = (DorisSqlBuilder) sqlBuilder;

    jdbcTemplate.execute(dorisSqlBuilder.dropCatalogIfExists());
    jdbcTemplate.execute(dorisSqlBuilder.createCatalog(connectionUrl, username, password));

    log.info("Doris JDBC catalog created");
  }

  /**
   * Creates a ClickHouse named collection with connection information for the DHIS2 PostgreSQL
   * database.
   */
  private void createClickHouseNamedCollection() {
    String jdbcUrl = config.getConnectionUrl();
    String host = JdbcUtils.getHostFromUrl(jdbcUrl);
    int port = JdbcUtils.getPortFromUrl(jdbcUrl, JdbcUtils.POSTGRESQL_PORT);
    String database = JdbcUtils.getDatabaseFromUrl(jdbcUrl);

    Map<String, Object> keyValues =
        Map.of(
            "host", host,
            "port", port,
            "database", database,
            "username", config.getProperty(ConfigurationKey.CONNECTION_USERNAME),
            "password", config.getProperty(ConfigurationKey.CONNECTION_PASSWORD));

    ClickHouseSqlBuilder clickHouseSqlBuilder = (ClickHouseSqlBuilder) sqlBuilder;

    jdbcTemplate.execute(clickHouseSqlBuilder.dropNamedCollectionIfExists(NAMED_COLLECTION));
    jdbcTemplate.execute(clickHouseSqlBuilder.createNamedCollection(NAMED_COLLECTION, keyValues));

    log.info(
        "ClickHouse named collection created using host: '{}', port: {}, database: '{}'",
        host,
        port,
        database);
  }
}
