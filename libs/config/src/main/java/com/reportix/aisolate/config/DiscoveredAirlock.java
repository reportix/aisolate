package com.reportix.aisolate.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * One airlock as discovery lists it, the projection the discovery pods mount for every space
 * ({@code aisolate-discovery}): no prompt, model, data source or Secret name. Its roles are names
 * the identity provider defines, not people, so they travel as they are. No version's pod mounts
 * it.
 *
 * @param space the id of its space
 * @param description its description, {@code ""} without one
 * @param roles its space's roles and its own, each once
 * @param callers its callers by {@code azp}, {@code *} for any
 * @param clients its machine clients by {@code azp}, exact; none when absent
 * @param versions in version order; none before its first deploy
 */
public record DiscoveredAirlock(
    String id,
    String space,
    String name,
    String description,
    List<String> roles,
    List<String> callers,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> clients,
    List<Version> versions) {

  /**
   * @param version its label within the airlock, the chat path's last segment
   * @param tools the tools its model may call
   */
  public record Version(String version, String description, List<String> tools) {}

  public DiscoveredAirlock {
    clients = clients == null ? List.of() : List.copyOf(clients);
  }

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** Every airlock of a release. */
  public static List<DiscoveredAirlock> of(Configuration release) {
    return release.airlocks().values().stream()
        .map(airlock -> {
          AirlockDocument.Access access = release.accessOf(airlock.name());
          return new DiscoveredAirlock(
              airlock.name(),
              release.spaceOf(airlock.name()).name(),
              airlock.displayName(),
              Objects.requireNonNullElse(airlock.spec().description(), ""),
              access.roles(),
              access.callers(),
              access.clients(),
              release.versionsOf(airlock.name()).stream()
                  .sorted(
                      Comparator.comparing(
                          v -> v.spec().version(), AirlockVersionDocument.VERSION_ORDER))
                  .map(v ->
                      new Version(
                          v.spec().version(),
                          Objects.requireNonNullElse(v.spec().description(), ""),
                          v.tools()))
                  .toList());
        })
        .toList();
  }

  public static String toJson(List<DiscoveredAirlock> airlocks) {
    return JSON.writeValueAsString(airlocks);
  }

  public static List<DiscoveredAirlock> fromJson(String json) {
    return JSON.readValue(json, new TypeReference<List<DiscoveredAirlock>>() {});
  }
}
