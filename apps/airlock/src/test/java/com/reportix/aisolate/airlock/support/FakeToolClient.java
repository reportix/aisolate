package com.reportix.aisolate.airlock.support;

import com.reportix.aisolate.airlock.domain.airlock.AirlockDefinition;
import com.reportix.aisolate.airlock.domain.chat.McpToolClient;
import com.reportix.aisolate.airlock.domain.chat.ToolCallFailedException;
import com.reportix.aisolate.airlock.domain.chat.ToolEvidence;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stands in for the pod's MCP server: declared tools plus canned evidence per tool call.
 * Thread-safe: the saturation tests drive two exchanges through one instance at the same time.
 */
public final class FakeToolClient implements McpToolClient {

  /** One tools/call as the tool server would see it: the arguments and the {@code _meta} pins. */
  public record Call(String tool, Map<String, Object> args, String airlock, String revision) {}

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final List<ToolSpec> tools = new CopyOnWriteArrayList<>();
  private final Map<String, Function<Map<String, Object>, String>> handlers =
      new ConcurrentHashMap<>();
  private final List<Call> calls = new CopyOnWriteArrayList<>();
  private volatile boolean unreachable;

  /**
   * What the calling thread's security context held at each call, null when nothing: exactly what
   * the real MCP client reads there. Recorded, never checked here, so unit tests without a security
   * context are unaffected.
   */
  private final List<Authentication> callerAuthentications = new CopyOnWriteArrayList<>();

  public FakeToolClient tool(String name, Function<Map<String, Object>, String> handler) {
    return tool(name, "{\"type\":\"object\",\"properties\":{}}", handler);
  }

  public FakeToolClient tool(
      String name, String inputSchema, Function<Map<String, Object>, String> handler) {
    tools.add(new ToolSpec(name, name + " description", inputSchema));
    handlers.put(name, handler);
    return this;
  }

  /** Every {@code tools/list} fails the way the real client reports a server it cannot reach. */
  public FakeToolClient unreachable() {
    unreachable = true;
    return this;
  }

  public List<Call> calls() {
    return calls;
  }

  public List<Authentication> callerAuthentications() {
    return callerAuthentications;
  }

  @Override
  public List<ToolSpec> listTools(AirlockDefinition airlock) {
    callerAuthentications.add(SecurityContextHolder.getContext().getAuthentication());
    if (unreachable) {
      throw new ToolCallFailedException("the tool server could not be reached");
    }
    return List.copyOf(tools);
  }

  @Override
  public String callTool(String name, Map<String, Object> arguments, AirlockDefinition airlock) {
    callerAuthentications.add(SecurityContextHolder.getContext().getAuthentication());
    calls.add(new Call(name, arguments, airlock.id(), airlock.revision()));
    Function<Map<String, Object>, String> handler = handlers.get(name);
    if (handler == null) {
      throw new ToolCallFailedException("unknown tool " + name);
    }
    return handler.apply(arguments);
  }

  /** Evidence JSON as a tool server produces it. */
  public static String evidence(int rowCount, String... rules) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("full_name", "Eric Lindholm");
    row.put("gross_salary", 78000);
    return JSON.writeValueAsString(
        new ToolEvidence(
            "hr.compensation",
            rowCount,
            List.of("full_name", "gross_salary"),
            rowCount == 0 ? List.of() : List.of(row),
            "1",
            List.of(rules),
            "abc123def456",
            "acme_hr",
            new ToolEvidence.Labels(
                null,
                "full_name",
                null,
                Map.of("gross_salary", "EUR"),
                Map.of(),
                Map.of(),
                List.of(),
                null,
                List.of())));
  }
}
