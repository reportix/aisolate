package com.reportix.aisolate.airlock.domain.airlock;

import java.util.List;

/**
 * What a chat client may see of an airlock: enough to pick a version and post to it.
 *
 * @param space the id of its space, which its endpoints name
 * @param versions in version order; none before its first deploy
 */
public record AirlockSummaryDTO(
    String id, String space, String name, String description, List<Version> versions) {

  /**
   * @param endpoint where a chat client posts its messages
   * @param tools the tools its model may call
   */
  public record Version(String version, String description, String endpoint, List<String> tools) {}
}
