package com.reportix.aisolate.airlock.domain.airlock;

import com.reportix.aisolate.config.AirlockDocument;
import com.reportix.aisolate.config.AirlockVersionDocument;
import com.reportix.aisolate.config.Configuration;
import com.reportix.aisolate.config.DataSource;
import com.reportix.aisolate.config.SpaceDocument;
import java.util.List;
import java.util.Objects;

/**
 * One airlock version as the runtime needs it: who may use it, its model and prompt, the tools its
 * model may call, the data sources its code reads and the handlers it lists. {@code revision} names
 * the release it came from. Built by {@link #from}, whichever catalog serves it. Rules are not part
 * of it: they ship in the version's jar and only the tool server applies them.
 *
 * @param version the version's label within its airlock, for example {@code v1}
 * @param description empty when the version has none
 * @param access who may ask, its airlock's: its space's roles and the airlock's own, exact, its
 *     callers by {@code azp}, {@code *} for any, and its machine clients by {@code azp}, exact
 * @param model the provider id its model runs on: its own, its airlock's, its space's or a global
 *     one
 * @param dataSources the ids of the data sources its code reads: its space's and its airlock's
 */
public record AirlockDefinition(
    String id,
    String version,
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
   * The handlers the version lists per stage, by class simple name, and the digest of the jar they
   * come from, null for a version without code. A missing list is empty.
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

  /** The one mapping of a version from its document, its airlock's and its space's. */
  public static AirlockDefinition from(
      Configuration configuration, AirlockVersionDocument version) {
    AirlockDocument airlock = configuration.airlockOf(version.name());
    SpaceDocument space = configuration.spaceOf(airlock.name());
    AirlockVersionDocument.Spec spec = version.spec();
    return new AirlockDefinition(
        airlock.name(),
        spec.version(),
        Objects.requireNonNullElse(spec.description(), ""),
        configuration.revision(),
        space.name(),
        airlock.displayName(),
        configuration.accessOf(airlock.name()),
        configuration.mainLlmOf(version.name()).provider(),
        version.tools(),
        configuration.dataSourcesOf(version.name()).stream().map(DataSource::id).toList(),
        spec.prompt(),
        new HandlerSelection(
            spec.jar() == null ? null : spec.jar().digest(),
            version.contextPreparatorHandlers(),
            version.memoryHandlers(),
            version.verifierHandlers()));
  }
}
