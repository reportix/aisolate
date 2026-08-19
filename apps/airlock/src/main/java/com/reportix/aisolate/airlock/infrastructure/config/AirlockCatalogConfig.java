package com.reportix.aisolate.airlock.infrastructure.config;

import com.reportix.aisolate.airlock.domain.airlock.AirlockCatalog;
import com.reportix.aisolate.airlock.domain.airlock.AirlockDefinition;
import com.reportix.aisolate.airlock.domain.airlock.DocumentsAirlockCatalog;
import com.reportix.aisolate.config.ConfigurationLoader;
import com.reportix.aisolate.config.DiscoveredAirlock;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The airlocks come from the documents in {@code aisolate.config.dir}, read once at start. An
 * invalid document, a dangling reference or a pin the documents do not define refuses the start,
 * naming it; nothing is served from a configuration that did not load whole. Without documents but
 * with a discovery mount, the pod is a discovery pod: it lists, and every chat path is 404.
 */
@Configuration
@Slf4j
public class AirlockCatalogConfig {

  /** A discovery pod's own release: none, so no airlock's code, data source or model. */
  private static final AirlockCatalog NONE =
      new AirlockCatalog() {
        @Override
        public Optional<AirlockDefinition> findAirlock(String id) {
          return Optional.empty();
        }

        @Override
        public List<DiscoveredAirlock> listAirlocks() {
          return List.of();
        }

        @Override
        public List<AirlockDefinition> served() {
          return List.of();
        }

        @Override
        public String revision() {
          return null;
        }
      };

  @Bean
  AirlockCatalog documentsAirlockCatalog(AirlockProperties properties) {
    String discovery = properties.config().discoveryDir();
    boolean discovers = discovery != null && !discovery.isBlank();
    if (properties.config().discoveryOnly()) {
      log.info("discovery_only discovery_dir={}", discovery);
      return new DiscoveringAirlockCatalog(NONE, Path.of(discovery));
    }
    var loaded =
        ConfigurationLoader.load(properties.config().dir(), properties.config().revision());
    DocumentsAirlockCatalog catalog = new DocumentsAirlockCatalog(loaded, properties.airlock());
    // No audit store: the pod's log records which release it loaded.
    log.info(
        "config_loaded revision={} airlock={} spaces={} airlocks={}",
        loaded.revision(),
        properties.airlock(),
        loaded.spaces().keySet(),
        loaded.airlocks().keySet());
    return discovers ? new DiscoveringAirlockCatalog(catalog, Path.of(discovery)) : catalog;
  }
}
