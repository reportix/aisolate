package com.reportix.aisolate.airlock.infrastructure.mcp;

import com.reportix.aisolate.airlock.domain.airlock.AirlockDefinition;
import com.reportix.aisolate.airlock.domain.chat.McpToolClient;
import com.reportix.aisolate.airlock.domain.chat.ToolCallFailedException;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * One tool server over MCP, streamable HTTP, one request per call and no protocol session. Every
 * call is authorised as the user of the current request: the token is read from the request's
 * security context on the calling thread and attached to this one call through the MCP transport
 * context, which the customizer below turns into the {@code Authorization} header on whatever
 * thread the SDK sends from. The airlock id and its revision travel in the {@code _meta} of every
 * call and list, so the tool server knows exactly whose rules to apply.
 *
 * <p>This is the only class that reads the token. It reads it on the calling thread and not inside
 * the customizer on purpose: the SDK sends from its own threads, where the security context is
 * empty. That assumes the call is made on the request thread, which holds: an exchange runs
 * synchronously inside the controller method, and on the {@code ChatClient.call()} path Spring AI
 * 2.0.1 executes tool callbacks inline. The {@code stream()} path does not: there the tool-calling
 * advisor runs tools on {@code Schedulers.boundedElastic()}, where no security context exists, so a
 * move to streaming needs a different hand-off first. A call from any other thread finds no user
 * and fails closed before anything is sent. The wrong fix for that would be {@code
 * MODE_INHERITABLETHREADLOCAL}: on pooled threads it is the classic cross-user leak.
 *
 * <p>The SDK's lazy {@code initialize} handshake runs inside the first caller's chain and carries
 * that caller's bearer. Should the server ever become session-based, the SDK's re-initialisation
 * after a lost session sends no bearer and fails with 401, never as another user.
 *
 * <p>It is also where every transport failure is turned into a fixed sentence. The SDK's exceptions
 * quote the request (headers included) and the response body, and the message of whatever escapes
 * here is logged, so neither the user's token nor a row of customer data may ride out on one.
 */
@RequiredArgsConstructor
@Slf4j
final class McpSyncToolClient implements McpToolClient, AutoCloseable {

  static final String BEARER = "aisolate.bearer";
  static final String TRACE = "aisolate.trace";

  /**
   * Applied to every HTTP request the transport sends, whatever thread it runs on: the user's
   * bearer and the W3C trace headers of the {@code tool <name>} span, both read on the calling
   * thread.
   */
  static final McpSyncHttpClientRequestCustomizer USER_TOKEN_AND_TRACE =
      (request, method, uri, body, context) -> {
        if (context == null) {
          return;
        }
        if (context.get(BEARER) instanceof String token) {
          request.header("Authorization", "Bearer " + token);
        }
        if (context.get(TRACE) instanceof Map<?, ?> headers) {
          headers.forEach((name, value) -> request.header((String) name, (String) value));
        }
      };

  private final McpAsyncClient mcpClient;
  private final JsonMapper jsonMapper;

  /** The pod's tool list once a listing succeeded; a failed one leaves it null for the next try. */
  private volatile List<ToolSpec> tools;

  /**
   * Asks the server once: its list is fixed for the pod's life. Two exchanges racing the first
   * listing may both ask; either answer is the same list.
   */
  @Override
  public List<ToolSpec> listTools(AirlockDefinition airlock) {
    List<ToolSpec> listed = tools;
    if (listed == null) {
      // First page only, all a tool server sends (the SDK's stateless server sets no
      // cursor); follow nextCursor with the pin if a server ever pages.
      listed =
          asCurrentUser(() -> mcpClient.listTools(null, pin(airlock))).tools().stream()
              .map(
                  mcpTool ->
                      new ToolSpec(
                          mcpTool.name(),
                          mcpTool.description(),
                          jsonMapper.writeValueAsString(mcpTool.inputSchema())))
              .toList();
      tools = listed;
    }
    return listed;
  }

  @Override
  public String callTool(
      String toolName, Map<String, Object> arguments, AirlockDefinition airlock) {
    McpSchema.CallToolRequest request =
        new McpSchema.CallToolRequest(toolName, arguments, pin(airlock));
    McpSchema.CallToolResult result = asCurrentUser(() -> mcpClient.callTool(request));
    String resultText =
        result.content().stream()
            .filter(McpSchema.TextContent.class::isInstance)
            .map(content -> ((McpSchema.TextContent) content).text())
            .findFirst()
            .orElse("");
    if (Boolean.TRUE.equals(result.isError())) {
      // The tool server's category and data source travel in _meta; its text is for the model.
      Map<String, Object> meta = result.meta() == null ? Map.of() : result.meta();
      throw new ToolCallFailedException(resultText, meta.get("error"), meta.get("data_source"));
    }
    return resultText;
  }

  /**
   * The {@code _meta} of a call and a list. Both from the admitted airlock, never from model
   * output: the server pins its policy to exactly this airlock at this revision.
   */
  private static Map<String, Object> pin(AirlockDefinition airlock) {
    return Map.of("airlock", airlock.id(), "revision", airlock.revision());
  }

  /** Spring calls this at shutdown. */
  @Override
  public void close() {
    mcpClient.close();
  }

  /**
   * Reads the current user's token on this thread, then writes it into the Reactor context the SDK
   * reads when it builds the request. The context travels with this call only, so concurrent
   * exchanges cannot pick up each other's token. The token is read first and outside the try: a
   * missing user is a programming error, not a tool-server failure, and nothing is sent without
   * one.
   */
  private static <T> T asCurrentUser(Supplier<Mono<T>> call) {
    McpTransportContext transportContext =
        McpTransportContext.create(Map.of(BEARER, currentUserToken(), TRACE, currentTrace()));
    try {
      return call.get()
          .contextWrite(context -> context.put(McpTransportContext.KEY, transportContext))
          .block();
    } catch (RuntimeException failed) {
      // Only the type is logged: the exception quotes the request it was building, headers
      // included.
      log.warn("call to the tool server failed with {}", failed.getClass().getSimpleName());
      throw asFixedMessage(failed);
    }
  }

  /**
   * The token of the request running on this thread. Nothing there means a call from a thread that
   * is not the request's, so it is not turned into a tool failure the model would be told to
   * recover from.
   */
  private static String currentUserToken() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
      return jwt.getTokenValue();
    }
    throw new IllegalStateException("MCP call without an authenticated user on this thread");
  }

  /** {@code traceparent} (and {@code tracestate}) of the current span; empty when none is. */
  private static Map<String, String> currentTrace() {
    Map<String, String> headers = new HashMap<>();
    W3CTraceContextPropagator.getInstance()
        .inject(Context.current(), headers, (carrier, key, value) -> carrier.put(key, value));
    return headers;
  }

  /**
   * The message of whatever is thrown here is logged, so it says only which of two things went
   * wrong. The SDK wraps failures during the handshake, hence the walk.
   */
  private static ToolCallFailedException asFixedMessage(RuntimeException failed) {
    for (Throwable cause = failed; cause != null; cause = cause.getCause()) {
      if (cause instanceof McpHttpClientTransportAuthorizationException) {
        return new ToolCallFailedException("the tool server rejected the request");
      }
    }
    return new ToolCallFailedException("the tool server could not be reached");
  }
}
