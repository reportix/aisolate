package com.reportix.aisolate.airlock.domain.chat;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * One tool of the airlock, as the framework's loop may call it. The spec is what its tool server
 * advertised; running it is the exchange's business, because that is where the evidence and the
 * browser's event stream live.
 *
 * <p>Only tools the airlock owns get one of these, which is what keeps the model to its own
 * toolbox: any other name the model invents resolves to {@link UnknownToolCallback} instead.
 */
record GovernedToolCallback(McpToolClient.ToolSpec toolSpec, ChatExchange exchange)
    implements ToolCallback {

  @Override
  public ToolDefinition getToolDefinition() {
    return ToolDefinition.builder()
        .name(toolSpec.name())
        .description(toolSpec.description())
        .inputSchema(toolSpec.inputSchema())
        .build();
  }

  @Override
  public String call(String toolInput) {
    return exchange.runTool(toolSpec.name(), toolInput);
  }
}
