package com.reportix.aisolate.toolserver.support;

import com.reportix.aisolate.identity.testing.Http;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Minimal MCP-over-streamable-HTTP client for tests: one JSON-RPC request per HTTP POST, as our
 * backend will do it. Talks the wire protocol directly so the test does not depend on client SDK
 * behaviour.
 */
public final class McpTestClient {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final String endpoint;

  public McpTestClient(int port) {
    this.endpoint = "http://localhost:" + port + "/mcp";
  }

  public HttpResponse<String> callTool(String bearer, String tool, Map<String, Object> args) {
    return callTool(
        bearer,
        tool,
        args,
        Map.of("airlock", "hr-assistant", "revision", ToolServerTestConfig.REVISION));
  }

  /** {@code meta} is the MCP {@code _meta}; the airlock pod puts the airlock and revision there. */
  public HttpResponse<String> callTool(
      String bearer, String tool, Map<String, Object> args, Map<String, Object> meta) {
    return callTool(bearer, tool, args, meta, Map.of());
  }

  /** {@code headers} go on the HTTP request, as the airlock's {@code traceparent} does. */
  public HttpResponse<String> callTool(
      String bearer,
      String tool,
      Map<String, Object> args,
      Map<String, Object> meta,
      Map<String, String> headers) {
    return post(
        bearer,
        headers,
        Map.of(
            "jsonrpc",
            "2.0",
            "id",
            1,
            "method",
            "tools/call",
            "params",
            Map.of("name", tool, "arguments", args, "_meta", meta)));
  }

  public HttpResponse<String> listTools(String bearer) {
    return post(
        bearer,
        Map.of(),
        Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list", "params", Map.of()));
  }

  /** Extracts the JSON payload of the first text content of a tools/call result. */
  public JsonNode resultJson(HttpResponse<String> response) {
    JsonNode body = parseBody(response.body());
    JsonNode content = body.path("result").path("content").get(0);
    if (content == null) {
      throw new AssertionError(
          "tools/call returned no content. HTTP "
              + response.statusCode()
              + " body: "
              + response.body());
    }
    return JSON.readTree(content.path("text").asString());
  }

  public JsonNode parseBody(String body) {
    // Streamable HTTP may answer with plain JSON or with one SSE frame; accept both.
    String json = body.strip();
    if (json.startsWith("event:") || json.startsWith("data:")) {
      json =
          json.lines()
              .filter(l -> l.startsWith("data:"))
              .map(l -> l.substring(5).strip())
              .findFirst()
              .orElseThrow();
    }
    return JSON.readTree(json);
  }

  private HttpResponse<String> post(
      String bearer, Map<String, String> headers, Map<String, Object> rpc) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(endpoint))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(rpc)));
    headers.forEach(request::header);
    return Http.send(Http.bearer(request, bearer).build());
  }
}
