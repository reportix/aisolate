package com.reportix.aisolate.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * One airlock as discovery lists it, the projection the discovery pods mount for every space
 * ({@code aisolate-discovery}): no prompt, model, data source or Secret name, and its users only as
 * {@link #userKey keys}. No airlock's pod mounts it.
 *
 * @param space the id of its space
 * @param description its description, {@code ""} without one
 * @param userKeys its space's users and its own, each as {@link #userKey}
 * @param callers its callers by {@code azp}, {@code *} for any
 * @param clients its machine clients by {@code azp}, exact; none when absent
 * @param tools the tools its model may call; none before its first deploy
 */
public record DiscoveredAirlock(
    String id,
    String space,
    String name,
    String description,
    List<String> userKeys,
    List<String> callers,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> clients,
    List<String> tools) {

  public DiscoveredAirlock {
    clients = clients == null ? List.of() : List.copyOf(clients);
  }

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** SHA-256 of the lower-cased e-mail, hex: who is listed, without the list. */
  public static String userKey(String email) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(email.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Whether {@code email} is one of its users; case ignored, as admission ignores it. */
  public boolean lists(String email) {
    return email != null && userKeys.contains(userKey(email));
  }

  /** Every airlock of a release. */
  public static List<DiscoveredAirlock> of(Configuration release) {
    return release.airlocks().values().stream()
        .map(
            airlock ->
                new DiscoveredAirlock(
                    airlock.name(),
                    release.spaceOf(airlock.name()).name(),
                    airlock.displayName(),
                    Objects.requireNonNullElse(airlock.spec().description(), ""),
                    release.accessOf(airlock.name()).users().stream()
                        .map(DiscoveredAirlock::userKey)
                        .distinct()
                        .toList(),
                    release.accessOf(airlock.name()).callers(),
                    release.accessOf(airlock.name()).clients(),
                    airlock.tools()))
        .toList();
  }

  public static String toJson(List<DiscoveredAirlock> airlocks) {
    return JSON.writeValueAsString(airlocks);
  }

  public static List<DiscoveredAirlock> fromJson(String json) {
    return JSON.readValue(json, new TypeReference<List<DiscoveredAirlock>>() {});
  }
}
