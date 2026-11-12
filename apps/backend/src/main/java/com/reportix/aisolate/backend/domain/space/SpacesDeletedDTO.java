package com.reportix.aisolate.backend.domain.space;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * What deleting every space removed, counted per table, what the cluster did after, and what went
 * from Monitoring's stores last.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SpacesDeletedDTO(
    int spaces, int airlocks, int versions, Cluster cluster, Monitoring monitoring) {

  /**
   * Null when no cluster is configured (the laptop).
   *
   * @param podsGone no airlock or tool-server pod left when the backend stopped waiting
   * @param problem why the cluster could not be cleared; null when it was
   */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Cluster(boolean podsGone, String problem) {}

  /**
   * @param auditLines how many audit lines went: Monitoring's Tracing list and the Audit page
   * @param metricsCleared the product's series deleted in the metrics store; null without one
   * @param problem why the metrics store kept them; null when it did not
   */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Monitoring(int auditLines, Boolean metricsCleared, String problem) {}

  public SpacesDeletedDTO withCluster(Cluster cluster) {
    return new SpacesDeletedDTO(spaces, airlocks, versions, cluster, monitoring);
  }

  public SpacesDeletedDTO withMonitoring(Monitoring monitoring) {
    return new SpacesDeletedDTO(spaces, airlocks, versions, cluster, monitoring);
  }
}
