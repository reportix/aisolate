package com.reportix.aisolate.toolserver.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.reportix.aisolate.sdk.Result;
import com.reportix.aisolate.sdk.Tool;
import com.reportix.aisolate.sdk.ToolPlugin;
import com.reportix.aisolate.toolserver.domain.GovernedQueryService;
import com.reportix.aisolate.toolserver.domain.PluginCatalog;
import com.reportix.aisolate.toolserver.domain.PolicySource;
import com.reportix.aisolate.toolserver.domain.UserToken;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Publishes the tools the served airlocks offer, one per name as the plugin publishing it describes
 * it, each behind the same gates: identity comes from the validated token on this request, never
 * from the arguments; the airlock and the revision come from the call's {@code _meta}; the handler
 * reaches the data only through the governed query bound to that user. AIsolate itself defines no
 * tool.
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class GovernedToolsConfig {

  private final GovernedQueryService governedQuery;
  private final PluginCatalog catalog;
  private final PolicySource policies;

  /**
   * A tool result as the airlock reads it: rowCount, ruleVersion and sqlHash travel as row_count,
   * rule_version, sql_hash; the labels' facts only when a fact column is readable, so a result
   * without one is written as before.
   */
  public static final JsonMapper RESULT_JSON =
      JsonMapper.builder()
          .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
          .addMixIn(Result.Labels.class, LabelsJson.class)
          .build();

  private interface LabelsJson {
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    List<String> facts();
  }

  @Bean
  List<SyncToolSpecification> toolSpecifications() {
    return catalog.listPublishedTools(policies.listPolicies()).stream()
        .map(this::buildSpecification)
        .toList();
  }

  /** The MCP tool for a name: the published schema; the implementation is resolved on call. */
  private SyncToolSpecification buildSpecification(Tool published) {
    String name = published.name();
    McpSchema.Tool listed =
        McpSchema.Tool.builder(name, published.inputSchema())
            .description(published.description())
            .build();
    return new SyncToolSpecification(
        listed,
        (context, request) ->
            call(
                name,
                () ->
                    governedQuery.call(
                        resolveUserToken(),
                        readPin(request.meta(), "airlock"),
                        readPin(request.meta(), "revision"),
                        name,
                        request.arguments() == null ? Map.of() : request.arguments())));
  }

  private CallToolResult call(String tool, Supplier<Result> handler) {
    try {
      return CallToolResult.builder()
          .addTextContent(RESULT_JSON.writeValueAsString(addDataSource(tool, handler.get())))
          .build();
    } catch (IllegalArgumentException invalidArguments) {
      return CallToolResult.builder()
          .isError(true)
          .addTextContent("Invalid arguments: " + invalidArguments.getMessage())
          .build();
    } catch (RuntimeException failure) {
      // Never let a driver message (which embeds the statement) reach the model: a fixed sentence,
      // and for the airlock what kind of failure it was and on which data source.
      ToolError error = ToolError.of(failure);
      log.error("tool {} failed: {}", tool, error.code(), failure);
      Map<String, Object> meta = new HashMap<>();
      meta.put("error", error.code());
      meta.put("data_source", dataSourceOf(tool));
      return CallToolResult.builder().isError(true).addTextContent(error.text()).meta(meta).build();
    }
  }

  private String dataSourceOf(String tool) {
    return catalog.findTool(tool).map(ToolPlugin::dataSource).orElse(null);
  }

  /**
   * The evidence names the data source its tool's plugin reads, for the airlock's audit line: the
   * airlock pod never loads a plugin.
   */
  private ObjectNode addDataSource(String tool, Result result) {
    ObjectNode evidence = RESULT_JSON.valueToTree(result);
    evidence.put("data_source", dataSourceOf(tool));
    return evidence;
  }

  /** A JSON string; anything else a caller sends pins nothing. */
  private static String readPin(Map<?, ?> meta, String key) {
    return meta != null && meta.get(key) instanceof String value ? value : null;
  }

  /** The token that authenticated this very request: its claims decide the rules. */
  private static UserToken resolveUserToken() {
    if (SecurityContextHolder.getContext().getAuthentication() instanceof Authentication user
        && user.getPrincipal() instanceof Jwt jwt) {
      return new UserToken(jwt.getClaims());
    }
    throw new IllegalStateException("tool called without an authenticated user token");
  }
}
