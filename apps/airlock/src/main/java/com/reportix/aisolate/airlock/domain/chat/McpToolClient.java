package com.reportix.aisolate.airlock.domain.chat;

import com.reportix.aisolate.airlock.domain.airlock.AirlockDefinition;
import java.util.List;
import java.util.Map;

/**
 * One tool server as the chat runtime sees it: list the tools, call a tool. The implementation
 * authorises every call as the user of the current request; the chat runtime never sees, carries or
 * names a credential.
 */
public interface McpToolClient {

  /** One tool as the tool server advertises it: name, description and JSON input schema. */
  record ToolSpec(String name, String description, String inputSchema) {}

  /**
   * MCP {@code tools/list} with the airlock pin in {@code _meta}. The list is fixed for the pod's
   * life and the same for any admitted token; the airlock decides which names the model sees.
   *
   * @throws ToolCallFailedException the server could not be reached or rejected the request
   */
  List<ToolSpec> listTools(AirlockDefinition airlock);

  /**
   * MCP {@code tools/call}. Returns the text content of the result, the evidence JSON. The
   * airlock's id travels in {@code _meta.airlock}: the tool server loads its rules at the revision
   * in {@code _meta.revision} and applies them to the user named in the token. The airlock is
   * passed whole, never as a string, so its id cannot be confused with or derived from a
   * model-supplied argument.
   *
   * @throws ToolCallFailedException when the tool server flags the result as an error; its {@code
   *     _meta} {@code error} and {@code data_source} say why and where
   */
  String callTool(String toolName, Map<String, Object> arguments, AirlockDefinition airlock);
}
