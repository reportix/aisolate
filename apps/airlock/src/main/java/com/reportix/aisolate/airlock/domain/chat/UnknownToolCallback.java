package com.reportix.aisolate.airlock.domain.chat;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * What a tool name the airlock does not own resolves to. The model may name any tool it likes,
 * including one it remembers from training or from another airlock, and the framework would
 * otherwise abort the exchange on a name it cannot resolve. This answers the model instead, in a
 * way it can recover from.
 *
 * <p>It is only ever reached through the resolver, which the tool-calling manager consults after
 * failing to match the tools attached to the request, so it can never shadow a tool the airlock
 * really has. It is not part of any tool schema the model is shown.
 *
 * <p>Its definition is named {@code unknown}, whatever the model asked for: the name is a label of
 * the tool-call meters, and the model, steered by the user, could otherwise add series at will.
 */
record UnknownToolCallback(String toolName) implements ToolCallback {

  @Override
  public ToolDefinition getToolDefinition() {
    return ToolDefinition.builder()
        .name("unknown")
        .description("Not available to this airlock")
        .inputSchema("{\"type\":\"object\",\"properties\":{}}")
        .build();
  }

  @Override
  public String call(String toolInput) {
    return "Tool not available: " + toolName;
  }
}
