package com.reportix.aisolate.airlock.infrastructure.mcp;

import com.reportix.aisolate.airlock.domain.chat.McpToolClient;
import com.reportix.aisolate.airlock.infrastructure.config.AirlockProperties;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * The MCP client to the one tool server the controller rendered for this airlock, over stateless
 * streamable HTTP. Every request carries the token of the user whose exchange is running, read from
 * the request's security context and attached per call by {@link McpSyncToolClient}; there is no
 * service credential to fall back to. The client is asynchronous because a per-call Reactor context
 * is how the token reaches the SDK's own threads. Building it does no I/O: the handshake runs
 * inside the first call and carries that caller's bearer.
 */
@Configuration
public class McpClientConfig {

  @Bean
  McpToolClient mcpToolClient(AirlockProperties properties, JsonMapper json) {
    URI uri = URI.create(properties.toolServer().url().strip());
    McpAsyncClient client =
        McpClient.async(
                HttpClientStreamableHttpTransport.builder(
                        uri.getScheme() + "://" + uri.getRawAuthority())
                    .endpoint(endpointPath(uri))
                    .httpRequestCustomizer(McpSyncToolClient.USER_TOKEN_AND_TRACE)
                    .build())
            .clientInfo(new McpSchema.Implementation("aisolate-airlock", "0.1.0"))
            .requestTimeout(Duration.ofSeconds(20))
            .build();
    return new McpSyncToolClient(client, json);
  }

  /** A bare origin means the conventional {@code /mcp}; a path given is taken as it is. */
  private static String endpointPath(URI uri) {
    String path = uri.getRawPath();
    return path == null || path.isEmpty() || "/".equals(path) ? "/mcp" : path;
  }
}
