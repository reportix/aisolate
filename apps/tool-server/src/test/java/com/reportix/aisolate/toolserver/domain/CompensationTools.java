package com.reportix.aisolate.toolserver.domain;

import com.reportix.aisolate.sdk.Column;
import com.reportix.aisolate.sdk.Entity;
import com.reportix.aisolate.sdk.Tool;
import com.reportix.aisolate.sdk.ToolPlugin;
import java.util.List;
import java.util.Map;

/**
 * A plugin declaring {@code hr.compensation} and publishing {@code read_records} with a description
 * of its own, so a test can tell which plugin was published or found.
 */
public class CompensationTools implements ToolPlugin {

  public static final Entity COMPENSATION =
      new Entity(
          "hr.compensation",
          "hr.compensation",
          "employee_id",
          new Column("employee_id", "employee_id", Column.Type.NUMBER));

  @Override
  public String name() {
    return "compensation-tools";
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
  public List<Tool> tools() {
    return List.of(new Tool("read_records", "compensation", Map.of(), (q, a) -> "compensation"));
  }
}
