package com.reportix.aisolate.backend.domain.space;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** What deleting every space removed, counted per table, and what the cluster did after. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SpacesDeletedDTO(int spaces, int airlocks, Cluster cluster) {

  /**
   * Null when no cluster is configured (the laptop).
   *
   * @param podsGone no airlock or tool-server pod left when the backend stopped waiting
   * @param problem why the cluster could not be cleared; null when it was
   */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Cluster(boolean podsGone, String problem) {}

  public SpacesDeletedDTO withCluster(Cluster cluster) {
    return new SpacesDeletedDTO(spaces, airlocks, cluster);
  }
}
