package com.reportix.aisolate.toolserver.infrastructure;

import com.reportix.aisolate.config.rules.RuleSet;
import com.reportix.aisolate.plugins.CodeFetch;
import com.reportix.aisolate.sdk.Entity;
import com.reportix.aisolate.sdk.Tool;
import com.reportix.aisolate.sdk.ToolPlugin;
import com.reportix.aisolate.toolserver.domain.CustomerDataSources;
import com.reportix.aisolate.toolserver.domain.PluginCatalog;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The tool plugins this server runs, with the rule sets their jar carries: every plugin on the
 * classpath plus the airlock's jar, fetched from the backend ({@link CodeFetch}), loaded once at
 * start into the registry every call resolves against. A jar nobody pinned by digest fails the
 * start, as on the airlock pod, and so does a plugin reading a data source this pod does not have.
 */
@Configuration
@Slf4j
public class PluginsConfig {

  @Bean
  PluginCatalog pluginCatalog(
      @Value("${aisolate.plugins.dir:}") String pluginsDir,
      @Value("${aisolate.plugins.digests:}") String digests,
      @Value("${aisolate.code.url:}") String codeUrl,
      @Value("${aisolate.code.digest:}") String codeDigest,
      @Value("${aisolate.code.token-file:}") String codeTokenFile,
      CustomerDataSources dataSources) {
    CodeFetch.Code code =
        CodeFetch.resolve(codeUrl, codeDigest, codeTokenFile, pluginsDir, digests);
    PluginLoader.Loaded loaded = PluginLoader.load(code.dir(), code.digests());
    PluginCatalog catalog = new PluginCatalog(loaded.plugins(), loaded.ruleSets());
    checkPlugins(catalog, code.dir());
    checkDataSources(catalog, dataSources.byId().keySet());
    for (ToolPlugin plugin : catalog.plugins()) {
      List<String> tools = plugin.tools().stream().map(Tool::name).toList();
      if (plugin.entities().isEmpty()) {
        log.info("loaded tool plugin {} publishing {}", plugin.name(), tools);
      } else {
        log.info(
            "loaded tool plugin {} reading {} declaring {} and publishing {}",
            plugin.name(),
            plugin.dataSource(),
            plugin.entities().stream().map(Entity::name).toList(),
            tools);
      }
    }
    log.info(
        "loaded {} rule set(s): {}",
        catalog.ruleSets().size(),
        catalog.ruleSets().stream().map(RuleSet::entity).toList());
    return catalog;
  }

  /** A server without a plugin has nothing to govern and does not start. */
  static void checkPlugins(PluginCatalog catalog, String pluginsDir) {
    if (!catalog.plugins().isEmpty()) {
      return;
    }
    throw new IllegalStateException(
        "no tool plugin on the classpath"
            + (pluginsDir.isBlank() ? "" : " or in " + pluginsDir)
            + "; set AISOLATE_PLUGINS_DIR to a directory of plugin jars");
  }

  /**
   * Every plugin with entities reads them from a data source of this airlock (the catalog already
   * refused one naming none): refused at the start rather than at the first call, as a tool error
   * the model would see.
   *
   * @param defined the ids of the data sources this pod was given
   */
  static void checkDataSources(PluginCatalog catalog, Set<String> defined) {
    for (ToolPlugin plugin : catalog.plugins()) {
      String dataSource = plugin.dataSource();
      if (!plugin.entities().isEmpty() && !defined.contains(dataSource)) {
        throw new IllegalStateException(
            "plugin "
                + plugin.name()
                + " reads data source "
                + dataSource
                + ", which this airlock does not define");
      }
    }
  }
}
