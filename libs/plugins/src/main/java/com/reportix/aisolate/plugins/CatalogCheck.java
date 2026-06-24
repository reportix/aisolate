package com.reportix.aisolate.plugins;

import com.reportix.aisolate.config.rules.RowFilter;
import com.reportix.aisolate.config.rules.Rule;
import com.reportix.aisolate.config.rules.RuleSet;
import com.reportix.aisolate.sdk.Entity;
import com.reportix.aisolate.sdk.Tool;
import com.reportix.aisolate.sdk.ToolPlugin;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Whether an airlock's tool plugins and rule sets make one catalog: the backend checks it at deploy
 * and the tool server at start, with this one method, so a deploy never accepts code the pod
 * refuses. A tool published twice, an entity declared twice, two rule sets for one entity, a plugin
 * with entities and no data source, a blank name, a column name SQL would fold or that is no
 * identifier, an entity whose row key or subject is not one of its columns, and a rule naming a
 * column its entity does not declare are developer errors: the catalog refuses to exist rather than
 * pick one. The allow-list of tables and columns lives in the plugins; a rule can narrow what is
 * visible but never invent a column.
 */
public final class CatalogCheck {

  /**
   * @throws IllegalStateException naming the first problem
   */
  public static void check(List<ToolPlugin> plugins, List<RuleSet> ruleSets) {
    Set<String> tools = new HashSet<>();
    Set<String> entities = new HashSet<>();
    for (ToolPlugin plugin : plugins) {
      requireName("plugin", plugin.name());
      plugin.tools().forEach(tool -> requireName("tool of plugin " + plugin.name(), tool.name()));
      plugin.entities().forEach(e -> requireName("entity of plugin " + plugin.name(), e.name()));
      String dataSource = plugin.dataSource();
      if (!plugin.entities().isEmpty() && (dataSource == null || dataSource.isBlank())) {
        throw new IllegalStateException(
            "plugin " + plugin.name() + " declares entities but names no data source");
      }
      for (Tool tool : plugin.tools()) {
        if (!tools.add(tool.name())) {
          throw new IllegalStateException("tool " + tool.name() + " is published twice");
        }
      }
      for (Entity entity : plugin.entities()) {
        if (!entities.add(entity.name())) {
          throw new IllegalStateException("entity " + entity.name() + " is declared twice");
        }
        if (!entity.columns().containsKey(entity.rowKey())) {
          throw new IllegalStateException(
              "entity " + entity.name() + ": row key " + entity.rowKey() + " is not a column");
        }
        if (!entity.columns().containsKey(entity.subject())) {
          throw new IllegalStateException(
              "entity " + entity.name() + ": subject " + entity.subject() + " is not a column");
        }
        for (String column : entity.columns().keySet()) {
          // Results carry the name unquoted, and SQL folds it to lower case.
          if (!COLUMN_NAME.matcher(column).matches()) {
            throw new IllegalStateException(
                "entity "
                    + entity.name()
                    + ": column "
                    + column
                    + " must be lowercase letters, digits and _, starting with a letter or _");
          }
        }
      }
    }
    Set<String> governed = new HashSet<>();
    for (RuleSet ruleSet : ruleSets) {
      if (!governed.add(ruleSet.entity())) {
        throw new IllegalStateException("two rule sets for entity " + ruleSet.entity());
      }
      checkColumns(ruleSet, plugins);
    }
  }

  private static final Pattern COLUMN_NAME = Pattern.compile("[a-z_][a-z0-9_]*");

  private static void requireName(String what, String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalStateException("a " + what + " has no name");
    }
  }

  /**
   * A misspelt {@code columns} key would leave the column it meant in the clear, and a row filter
   * on an unknown column would fail every call. An entity no plugin declares is skipped, since no
   * call ever reaches its rule set.
   */
  private static void checkColumns(RuleSet ruleSet, List<ToolPlugin> plugins) {
    Entity declared =
        plugins.stream()
            .flatMap(plugin -> plugin.entity(ruleSet.entity()).stream())
            .findFirst()
            .orElse(null);
    if (declared == null) {
      return;
    }
    for (Rule rule : ruleSet.rules()) {
      for (String column : rule.columns().keySet()) {
        requireColumn(declared, ruleSet, rule, column);
      }
      if (rule.rowFilter() != null) {
        RowFilter filter = RowFilter.parse(rule.rowFilter());
        if (!filter.matchesAll()) {
          requireColumn(declared, ruleSet, rule, filter.column());
        }
      }
    }
  }

  private static void requireColumn(Entity declared, RuleSet ruleSet, Rule rule, String column) {
    if (!declared.columns().containsKey(column)) {
      throw new IllegalStateException(
          "rule set "
              + ruleSet.entity()
              + ": rule "
              + rule.name()
              + " names column "
              + column
              + " no plugin declares");
    }
  }

  private CatalogCheck() {}
}
