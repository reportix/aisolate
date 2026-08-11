package com.reportix.aisolate.toolserver.support;

import com.reportix.aisolate.sdk.Arguments;
import com.reportix.aisolate.sdk.Column;
import com.reportix.aisolate.sdk.Entity;
import com.reportix.aisolate.sdk.Tool;
import com.reportix.aisolate.sdk.ToolPlugin;
import java.util.List;
import java.util.Map;

/**
 * A second plugin of the same code: {@code export_payslips} over {@code hr.payroll}, which no rule
 * set governs (the table does not exist; a denied query never runs). Its {@code entity} argument
 * names another entity to read instead, so a test can ask it for one it does not declare; {@code
 * "as": "count"} makes it return a plain map, which is no {@code Result}. No identity join. Found
 * through {@code META-INF/services}, next to {@link HrAcmeTools}.
 */
public final class PayrollTools implements ToolPlugin {

  private static final Entity PAYROLL =
      new Entity(
          "hr.payroll",
          "hr.payroll",
          "id",
          new Column("id", "id", Column.Type.NUMBER),
          new Column("note", "note", Column.Type.TEXT));

  @Override
  public String name() {
    return "payroll-tools";
  }

  @Override
  public List<Entity> entities() {
    return List.of(PAYROLL);
  }

  @Override
  public String dataSource() {
    return "acme_hr";
  }

  @Override
  public List<Tool> tools() {
    return List.of(
        new Tool(
            "export_payslips",
            "Export the payslips of " + PAYROLL.name() + ".",
            Arguments.schema(
                Map.of(
                    "entity",
                    Map.of("type", "string", "description", "Another entity"),
                    "as",
                    Map.of("type", "string", "description", "count: a total, not rows")),
                List.of()),
            (query, args) ->
                "count".equals(args.get("as"))
                    ? Map.of("total", 42)
                    : query.read(
                        args.get("entity") == null
                            ? PAYROLL.name()
                            : Arguments.string(args.get("entity"), "entity"),
                        List.of(),
                        100)));
  }
}
