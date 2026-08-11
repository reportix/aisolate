package com.reportix.aisolate.toolserver.support;

import com.reportix.aisolate.sdk.Arguments;
import com.reportix.aisolate.sdk.Column;
import com.reportix.aisolate.sdk.Entity;
import com.reportix.aisolate.sdk.Tool;
import com.reportix.aisolate.sdk.ToolPlugin;
import java.util.List;
import java.util.Map;

/**
 * A plugin whose data source nothing answers ({@link ToolServerTestConfig#UNREACHABLE_URL}): {@code
 * read_archive} over {@code hr.archive}, which a rule set governs, so a call reaches the database
 * and fails there: at its identity join first. Found through {@code META-INF/services}.
 */
public final class ArchiveTools implements ToolPlugin {

  private static final Entity ARCHIVE =
      new Entity(
          "hr.archive",
          "hr.archive",
          "id",
          new Column("id", "id", Column.Type.NUMBER),
          new Column("note", "note", Column.Type.TEXT));

  @Override
  public String name() {
    return "archive-tools";
  }

  @Override
  public List<Entity> entities() {
    return List.of(ARCHIVE);
  }

  @Override
  public String dataSource() {
    return "hr_archive";
  }

  @Override
  public String identityJoin() {
    return "SELECT id FROM hr.employees WHERE email = ?";
  }

  @Override
  public List<Tool> tools() {
    return List.of(
        new Tool(
            "read_archive",
            "Read the archived records of " + ARCHIVE.name() + ".",
            Arguments.schema(Map.of(), List.of()),
            (query, args) -> query.read(ARCHIVE.name(), List.of(), 100)));
  }
}
