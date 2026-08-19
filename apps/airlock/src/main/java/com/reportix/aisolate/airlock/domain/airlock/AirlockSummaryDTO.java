package com.reportix.aisolate.airlock.domain.airlock;

import java.util.List;

/**
 * What a chat client may see of an airlock: enough to pick it and post to it.
 *
 * @param space the id of its space, which its endpoint names
 * @param endpoint where a chat client posts its messages
 */
public record AirlockSummaryDTO(
    String id,
    String space,
    String name,
    String description,
    String endpoint,
    List<String> tools) {}
