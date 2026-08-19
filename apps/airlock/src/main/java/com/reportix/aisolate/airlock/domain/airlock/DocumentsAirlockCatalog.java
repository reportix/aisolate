package com.reportix.aisolate.airlock.domain.airlock;

import com.reportix.aisolate.config.AirlockDocument;
import com.reportix.aisolate.config.Configuration;
import com.reportix.aisolate.config.DiscoveredAirlock;
import java.util.List;
import java.util.Optional;

/**
 * Every airlock of one release that has something to run, mapped once from the documents, each
 * under the release revision; an airlock before its first deploy (no llm, no prompt) is listed but
 * serves nothing. A pin narrows what this process serves to one airlock while the list stays whole,
 * so discovery still shows the release and the chat endpoint answers for one id only.
 */
public final class DocumentsAirlockCatalog implements AirlockCatalog {

  private final List<AirlockDefinition> airlocks;
  private final List<DiscoveredAirlock> listed;
  private final String revision;
  private final String pinned;

  /**
   * @param pinned the one airlock this process serves; null or blank serves every airlock
   */
  public DocumentsAirlockCatalog(Configuration configuration, String pinned) {
    this.revision = configuration.revision();
    this.pinned = pinned == null || pinned.isBlank() ? null : pinned;
    this.airlocks =
        configuration.airlocks().values().stream()
            .filter(DocumentsAirlockCatalog::runs)
            .map(airlock -> AirlockDefinition.from(configuration, airlock))
            .toList();
    this.listed = DiscoveredAirlock.of(configuration);
    if (this.pinned != null && !configuration.airlocks().containsKey(this.pinned)) {
      throw new IllegalStateException(
          "AISOLATE_AIRLOCK names "
              + this.pinned
              + " but the documents define "
              + configuration.airlocks().keySet());
    }
  }

  /** Blank llm or prompt: nothing to run until a deploy brings them. */
  private static boolean runs(AirlockDocument airlock) {
    return !blank(airlock.spec().llm()) && !blank(airlock.spec().prompt());
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  @Override
  public Optional<AirlockDefinition> findAirlock(String id) {
    if (pinned != null && !pinned.equals(id)) {
      return Optional.empty();
    }
    return airlocks.stream().filter(airlock -> airlock.id().equals(id)).findFirst();
  }

  @Override
  public List<DiscoveredAirlock> listAirlocks() {
    return listed;
  }

  @Override
  public List<AirlockDefinition> served() {
    return airlocks.stream().filter(a -> pinned == null || pinned.equals(a.id())).toList();
  }

  @Override
  public String revision() {
    return revision;
  }
}
