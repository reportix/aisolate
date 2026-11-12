package com.reportix.aisolate.airlock.domain.airlock;

import com.reportix.aisolate.config.Configuration;
import com.reportix.aisolate.config.DiscoveredAirlock;
import java.util.List;
import java.util.Optional;

/**
 * Every airlock of one release with its versions, mapped once from the documents, each under the
 * release revision; an airlock without versions is listed with none. A pin narrows what this
 * process serves to one version of one airlock while the list stays whole, so discovery still shows
 * the release and the chat endpoint answers for one path only.
 */
public final class DocumentsAirlockCatalog implements AirlockCatalog {

  private final List<AirlockDefinition> versions;
  private final List<DiscoveredAirlock> listed;
  private final String revision;
  private final String pinnedAirlock;
  private final String pinnedVersion;

  /**
   * @param pinnedAirlock the one airlock this process serves; null or blank serves every airlock
   * @param pinnedVersion the one version of it; null or blank serves every version of the airlock
   */
  public DocumentsAirlockCatalog(
      Configuration configuration, String pinnedAirlock, String pinnedVersion) {
    this.revision = configuration.revision();
    this.pinnedAirlock = blankAsNull(pinnedAirlock);
    this.pinnedVersion = blankAsNull(pinnedVersion);
    this.versions =
        configuration.airlockVersions().values().stream()
            .map(version -> AirlockDefinition.from(configuration, version))
            .toList();
    this.listed = DiscoveredAirlock.of(configuration);
    if (this.pinnedAirlock != null && !configuration.airlocks().containsKey(this.pinnedAirlock)) {
      throw new IllegalStateException(
          "AISOLATE_AIRLOCK names "
              + this.pinnedAirlock
              + " but the documents define "
              + configuration.airlocks().keySet());
    }
    if (this.pinnedVersion != null && served().isEmpty()) {
      throw new IllegalStateException(
          "AISOLATE_VERSION names "
              + this.pinnedAirlock
              + "/"
              + this.pinnedVersion
              + " but the documents define "
              + versions.stream().map(v -> v.id() + "/" + v.version()).toList());
    }
  }

  private static String blankAsNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  @Override
  public Optional<AirlockDefinition> findAirlock(String id, String version) {
    return served().stream()
        .filter(definition -> definition.id().equals(id) && definition.version().equals(version))
        .findFirst();
  }

  @Override
  public List<DiscoveredAirlock> listAirlocks() {
    return listed;
  }

  @Override
  public List<AirlockDefinition> served() {
    return versions.stream()
        .filter(v -> pinnedAirlock == null || pinnedAirlock.equals(v.id()))
        .filter(v -> pinnedVersion == null || pinnedVersion.equals(v.version()))
        .toList();
  }

  @Override
  public String revision() {
    return revision;
  }
}
