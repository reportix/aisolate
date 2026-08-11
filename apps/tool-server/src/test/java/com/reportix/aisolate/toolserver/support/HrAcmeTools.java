package com.reportix.aisolate.toolserver.support;

import com.reportix.aisolate.sdk.Arguments;
import com.reportix.aisolate.sdk.Column;
import com.reportix.aisolate.sdk.Entity;
import com.reportix.aisolate.sdk.Tool;
import com.reportix.aisolate.sdk.ToolPlugin;
import java.util.List;
import java.util.Map;

/**
 * A compensation entity, as an HR plugin declares it: the entity behind the seeded demo database in
 * {@code docker/postgres/acme} and the two tools, so the tests need no other repository on the
 * classpath. Found through {@code META-INF/services}.
 */
public final class HrAcmeTools implements ToolPlugin {

  public static final Entity COMPENSATION =
      new Entity(
              "hr.compensation",
              "hr.compensation c JOIN hr.employees e ON e.id = c.employee_id",
              "employee_id",
              new Column("employee_id", "c.employee_id", Column.Type.NUMBER),
              new Column("full_name", "e.full_name", Column.Type.TEXT),
              new Column("department", "e.department", Column.Type.TEXT),
              new Column("title", "e.title", Column.Type.TEXT),
              new Column("gross_salary", "c.gross_salary", Column.Type.NUMBER)
                  .withUnitFrom("currency"),
              new Column("currency", "c.currency", Column.Type.TEXT),
              new Column("target_bonus", "c.target_bonus", Column.Type.NUMBER),
              new Column("iban", "c.iban", Column.Type.TEXT),
              new Column("internal_notes", "c.internal_notes", Column.Type.TEXT))
          .withSubjectName("full_name");

  private static final String ENTITY =
      COMPENSATION.name() + " (" + String.join(", ", COMPENSATION.columns().keySet()) + ")";

  @Override
  public String name() {
    return "hr-acme-tools";
  }

  @Override
  public List<Entity> entities() {
    return List.of(COMPENSATION);
  }

  @Override
  public String dataSource() {
    return "acme_hr";
  }

  @Override
  public String identityJoin() {
    return "SELECT id FROM hr.employees WHERE email = ?";
  }

  /** Rules compare with it as {@code principal.employee_id}. */
  @Override
  public String identityAttribute() {
    return "employee_id";
  }

  @Override
  public List<Tool> tools() {
    return List.of(
        new Tool(
            "read_records",
            "Read records of "
                + ENTITY
                + ". Returns only the rows and columns the asking user may see; the row with"
                + " self=true is the asking user's own record. An empty result means there is"
                + " nothing this user may see.",
            Arguments.schema(
                Map.of("filters", Arguments.FILTERS_SCHEMA, "limit", Arguments.LIMIT_SCHEMA),
                List.of()),
            (query, args) ->
                query.read(
                    COMPENSATION.name(),
                    Arguments.filters(args.get("filters")),
                    Arguments.limit(args.get("limit"), 100))),
        new Tool(
            "aggregate_records",
            "Aggregate records of "
                + ENTITY
                + ": function sum, avg, count, min or max over a numeric column, optionally"
                + " grouped by a column. Groups smaller than the allowed minimum are not returned.",
            Arguments.schema(
                Map.of(
                    "function",
                    Arguments.FUNCTION_SCHEMA,
                    "column",
                    Map.of("type", "string", "description", "Column to aggregate"),
                    "group_by",
                    Map.of("type", "string", "description", "Optional column to group by"),
                    "filters",
                    Arguments.FILTERS_SCHEMA),
                List.of("function", "column")),
            (query, args) ->
                query.aggregate(
                    COMPENSATION.name(),
                    Arguments.function(args.get("function")),
                    Arguments.string(args.get("column"), "column"),
                    args.get("group_by") == null
                        ? null
                        : Arguments.string(args.get("group_by"), "group_by"),
                    Arguments.filters(args.get("filters")))));
  }
}
