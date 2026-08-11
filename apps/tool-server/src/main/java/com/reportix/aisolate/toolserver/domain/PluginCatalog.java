package com.reportix.aisolate.toolserver.domain;

import com.reportix.aisolate.config.rules.RuleSet;
import com.reportix.aisolate.plugins.CatalogCheck;
import com.reportix.aisolate.sdk.Tool;
import com.reportix.aisolate.sdk.ToolPlugin;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The tool plugins of the airlock's code and the rule sets it carries, refused at start when they
 * do not make one catalog ({@link CatalogCheck}, the check the deploy ran).
 */
public record PluginCatalog(List<ToolPlugin> plugins, List<RuleSet> ruleSets) {

  public PluginCatalog {
    plugins = List.copyOf(plugins);
    ruleSets = List.copyOf(ruleSets);
    CatalogCheck.check(plugins, ruleSets);
  }

  /**
   * What {@code tools/list} advertises: every tool a served airlock lists and a plugin publishes,
   * once, sorted by name. A listed name no plugin publishes is left out.
   */
  public List<Tool> listPublishedTools(List<Policy> served) {
    Set<String> listed =
        served.stream().flatMap(policy -> policy.tools().stream()).collect(Collectors.toSet());
    return plugins.stream()
        .flatMap(plugin -> plugin.tools().stream())
        .filter(tool -> listed.contains(tool.name()))
        .sorted(Comparator.comparing(Tool::name))
        .toList();
  }

  /** The plugin that publishes this tool. */
  public Optional<ToolPlugin> findTool(String name) {
    return plugins.stream()
        .filter(plugin -> plugin.tools().stream().anyMatch(tool -> tool.name().equals(name)))
        .findFirst();
  }

  /** The rule set that governs this entity; none means deny. */
  public Optional<RuleSet> findRuleSet(String entity) {
    return ruleSets.stream().filter(ruleSet -> ruleSet.entity().equals(entity)).findFirst();
  }
}
