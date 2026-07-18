/*
 * Copyright (c) 2004-2022, University of Oslo
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
package org.hisp.dhis.analytics.event.data.programindicator;

import java.util.Map;
import org.apache.commons.text.StringSubstitutor;
import org.hisp.dhis.db.sql.SqlBuilder;
import org.hisp.dhis.program.AnalyticsType;
import org.hisp.dhis.relationship.RelationshipEntity;
import org.hisp.dhis.relationship.RelationshipType;

/**
 * Generates a SQL JOIN to join an enrollment or event with one or more related entities, based on
 * the specified relationship type
 *
 * <p>The joined tables are DHIS2 source tables, not generated analytics tables, so they are
 * resolved through {@link SqlBuilder#qualifyTable(String)} — on PostgreSQL that is a plain quoted
 * name, while alternate analytics databases (DuckDB, Doris, ClickHouse) qualify to the source
 * database, where these tables actually live.
 *
 * @author Luciano Fiandesio
 */
public class RelationshipTypeJoinGenerator {
  static final String RELATIONSHIP_JOIN = " WHERE rty.relationshiptypeid = ${relationshipid}";

  /**
   * Generate a sub query that joins an incoming Event/Enrollment/TE UID to one or more related
   * entities, based on the selected relationship type
   *
   * @param alias the table alias to use for the main analytics table
   * @param relationshipType the type of relationship to fetch data for
   * @param programIndicatorType the type or Program Indicator that is used for this join
   *     (Enrollment or Event)
   * @param sqlBuilder the {@link SqlBuilder} used to qualify source tables.
   * @return a SQL string containing the JOIN between analytics table and relationship
   */
  public static String generate(
      String alias,
      RelationshipType relationshipType,
      AnalyticsType programIndicatorType,
      SqlBuilder sqlBuilder) {
    String sql =
        getFromRelationshipEntity(
            alias,
            relationshipType.getFromConstraint().getRelationshipEntity(),
            programIndicatorType,
            sqlBuilder);

    sql +=
        " left join "
            + sqlBuilder.qualifyTable("relationship")
            + " r on r.from_relationshipitemid = ri.relationshipitemid "
            + "left join "
            + sqlBuilder.qualifyTable("relationshipitem")
            + " ri2 on r.to_relationshipitemid = ri2.relationshipitemid "
            + "left join "
            + sqlBuilder.qualifyTable("relationshiptype")
            + " rty on rty.relationshiptypeid = r.relationshiptypeid ";

    sql += getToJoin(relationshipType.getToConstraint().getRelationshipEntity(), sqlBuilder);

    sql +=
        addRelationshipWhereClause(
            relationshipType.getId(), relationshipType.getToConstraint().getRelationshipEntity());

    sql += ")";
    return sql;
  }

  private static String getToJoin(RelationshipEntity relationshipEntity, SqlBuilder sqlBuilder) {
    final String sql = "left join ";
    return switch (relationshipEntity) {
      case TRACKED_ENTITY_INSTANCE ->
          sql
              + sqlBuilder.qualifyTable("trackedentity")
              + " te2 on te2.trackedentityid = ri2.trackedentityid";
      case PROGRAM_STAGE_INSTANCE ->
          sql + sqlBuilder.qualifyTable("event") + " ev2 on ev2.eventid = ri2.eventid";
      case PROGRAM_INSTANCE ->
          sql
              + sqlBuilder.qualifyTable("enrollment")
              + " en2 on en2.enrollmentid = ri2.enrollmentid";
    };
  }

  private static String getFromRelationshipEntity(
      String alias,
      RelationshipEntity relationshipEntity,
      AnalyticsType programIndicatorType,
      SqlBuilder sqlBuilder) {
    return switch (relationshipEntity) {
      case TRACKED_ENTITY_INSTANCE -> getTei(alias, sqlBuilder);
      case PROGRAM_STAGE_INSTANCE, PROGRAM_INSTANCE ->
          programIndicatorType.equals(AnalyticsType.EVENT)
              ? getEvent(alias, sqlBuilder)
              : getEnrollment(alias, sqlBuilder);
    };
  }

  private static String getTei(String alias, SqlBuilder sqlBuilder) {
    return " "
        + alias
        + ".trackedentity in (select te.uid from "
        + sqlBuilder.qualifyTable("trackedentity")
        + " te left join "
        + sqlBuilder.qualifyTable("relationshipitem")
        + " ri on te.trackedentityid = ri.trackedentityid ";
  }

  private static String getEnrollment(String alias, SqlBuilder sqlBuilder) {
    return " "
        + alias
        + ".enrollment in (select en.uid from "
        + sqlBuilder.qualifyTable("enrollment")
        + " en left join "
        + sqlBuilder.qualifyTable("relationshipitem")
        + " ri on en.enrollmentid = ri.enrollmentid ";
  }

  private static String getEvent(String alias, SqlBuilder sqlBuilder) {
    return " "
        + alias
        + ".event in (select ev.uid from "
        + sqlBuilder.qualifyTable("event")
        + " ev left join "
        + sqlBuilder.qualifyTable("relationshipitem")
        + " ri on ev.eventid = ri.eventid ";
  }

  private static String addRelationshipWhereClause(
      Long relationshipTypeId, RelationshipEntity relationshipEntity) {
    String sql =
        new StringSubstitutor(Map.of("relationshipid", relationshipTypeId))
            .replace(RELATIONSHIP_JOIN);

    sql += " and ";

    return switch (relationshipEntity) {
      case TRACKED_ENTITY_INSTANCE -> sql + "te2.uid = ax.trackedentity ";
      case PROGRAM_STAGE_INSTANCE -> sql + "ev2.uid = ax.event ";
      case PROGRAM_INSTANCE -> sql + "en2.uid = ax.enrollment ";
    };
  }
}
