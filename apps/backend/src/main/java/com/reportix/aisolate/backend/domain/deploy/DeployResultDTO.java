package com.reportix.aisolate.backend.domain.deploy;

import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * What one deploy did: the airlock as saved with its endpoint, the code as stored, and the warnings
 * the deploy went ahead with. Nested records do not inherit the naming, so each carries its own.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DeployResultDTO(
    AirlockOutcome airlock, Jar jar, List<String> warnings, Cluster cluster) {

  public DeployResultDTO {
    warnings = warnings == null ? List.of() : List.copyOf(warnings);
  }

  /**
   * Whether the space reached the cluster, and why not; the cluster part of a deploy is null when
   * no cluster is configured (the laptop).
   */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Cluster(boolean applied, String problem) {
    public static Cluster ok() {
      return new Cluster(true, null);
    }

    public static Cluster failed(String problem) {
      return new Cluster(false, problem);
    }
  }

  public DeployResultDTO withCluster(Cluster cluster) {
    return new DeployResultDTO(airlock, jar, warnings, cluster);
  }

  /**
   * @param created made by this deploy from the file's Airlock, else saved in place
   */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record AirlockOutcome(
      String id, String name, int revision, String endpoint, boolean created) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Jar(String digest, List<String> tools, List<String> handlers) {}
}
