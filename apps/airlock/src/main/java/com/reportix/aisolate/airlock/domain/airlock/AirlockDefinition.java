package com.reportix.aisolate.airlock.domain.airlock;

import com.reportix.aisolate.config.AirlockDocument;
import com.reportix.aisolate.config.Configuration;
import com.reportix.aisolate.config.DataSource;
import com.reportix.aisolate.config.SpaceDocument;
import java.util.List;
import java.util.Objects;

/**
 * One airlock as the runtime needs it: who may use it, its model and prompt, the tools its model
 * may call, the data sources its code reads and the handlers it lists. {@code revision} names the
 * release it came from. Built by {@link #from}, whichever catalog serves it. Rules are not part of
 * it: they ship in the airlock's jar and only the tool server applies them.
 *
 * @param description empty when the airlock has none
 * @param access who may ask: its space's users and its own, case ignored, its callers by {@code
 *     azp}, {@code *} for any, and its machine clients by {@code azp}, exact
 * @param model the provider id its model runs on: its own, its space's or a global one
 * @param dataSources the ids of the data sources its code reads: its space's and its own
 */
public record AirlockDefinition(
    String id,
    String description,
    String revision,
    String spaceId,
    String name,
    AirlockDocument.Access access,
    String model,
    List<String> tools,
    List<String> dataSources,
    String systemPrompt,
    HandlerSelection handlers) {

  /**
   * The handlers the airlock lists per stage, by class simple name, and the digest of the jar they
   * come from, null for an airlock without code. A missing list is empty.
   */
  public record HandlerSelection(
      String jarDigest,
      List<String> contextPreparators,
      List<String> memories,
      List<String> verifiers) {

    public HandlerSelection {
      contextPreparators = contextPreparators == null ? List.of() : List.copyOf(contextPreparators);
      memories = memories == null ? List.of() : List.copyOf(memories);
      verifiers = verifiers == null ? List.of() : List.copyOf(verifiers);
    }
  }

  /** The one mapping of an airlock from its document and its space's. */
  public static AirlockDefinition from(Configuration configuration, AirlockDocument airlock) {
    SpaceDocument space = configuration.spaceOf(airlock.name());
    AirlockDocument.Spec spec = airlock.spec();
    return new AirlockDefinition(
        airlock.name(),
        Objects.requireNonNullElse(spec.description(), ""),
        configuration.revision(),
        space.name(),
        airlock.displayName(),
        configuration.accessOf(airlock.name()),
        configuration.mainLlmOf(airlock.name()).provider(),
        airlock.tools(),
        configuration.dataSourcesOf(airlock.name()).stream().map(DataSource::id).toList(),
        spec.prompt(),
        new HandlerSelection(
            spec.jar() == null ? null : spec.jar().digest(),
            airlock.contextPreparatorHandlers(),
            airlock.memoryHandlers(),
            airlock.verifierHandlers()));
  }
}
