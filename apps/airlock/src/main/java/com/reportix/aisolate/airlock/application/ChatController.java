package com.reportix.aisolate.airlock.application;

import com.reportix.aisolate.airlock.domain.airlock.AirlockAdmissionService;
import com.reportix.aisolate.airlock.domain.airlock.AirlockDefinition;
import com.reportix.aisolate.airlock.domain.chat.AgentLoopService;
import com.reportix.aisolate.airlock.domain.chat.ChatAnswer;
import com.reportix.aisolate.airlock.domain.chat.ExchangeTracing;
import com.reportix.aisolate.airlock.infrastructure.config.SwaggerApiConfig;
import com.reportix.aisolate.airlock.infrastructure.plugins.ExchangeManagerFactory;
import com.reportix.aisolate.airlock.infrastructure.stream.SseStream;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * The endpoint of an airlock: one message in, a stream of events out, or the whole exchange as one
 * JSON document for the caller that asks for it.
 */
@Tag(
    name = "Chat",
    description =
        "The endpoint of an airlock: one user message in, a stream of exchange events out.")
@RestController
@RequiredArgsConstructor
public class ChatController {

  /**
   * One question, not a document: the platform's bound keeps a single request off the model for
   * minutes.
   */
  public static final int MAX_QUESTION_LENGTH = 4000;

  public record ChatRequest(@NotBlank @Size(max = MAX_QUESTION_LENGTH) String message) {}

  private final AirlockAdmissionService airlockAdmission;
  private final ExchangeManagerFactory managers;
  private final AgentLoopService agentLoop;
  private final ExchangeTracing tracing;
  private final JsonMapper jsonMapper;

  @Operation(
      summary = "Ask an airlock one question",
      description =
          "One chat exchange under the caller's token, streamed as server-sent events, or with"
              + " `Accept: application/json` collected into one document. The token decides what"
              + " the answer may contain; a denied question yields \"I don't know\". The events"
              + " and the document: docs/api.md, SSE events and The chat endpoint.",
      security = @SecurityRequirement(name = SwaggerApiConfig.BEARER_SCHEME))
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "200",
            description =
                "Server-sent events by default; with Accept: application/json, one document",
            content = {
              @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE),
              @Content(
                  mediaType = MediaType.APPLICATION_JSON_VALUE,
                  schema = @Schema(implementation = ChatAnswer.class))
            }),
        @ApiResponse(
            responseCode = "400",
            description = "message is blank or longer than " + MAX_QUESTION_LENGTH + " characters",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE)),
        @ApiResponse(
            responseCode = "401",
            description =
                "No valid token; WWW-Authenticate names the protected-resource metadata (RFC 9728)",
            content = @Content),
        @ApiResponse(
            responseCode = "403",
            description =
                "Token valid, but the airlock does not list the user or the calling client",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE)),
        @ApiResponse(
            responseCode = "404",
            description = "No airlock with this id in this space",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE))
      })
  // Both handlers match an Accept of "*/*" or none, and Spring then ranks consumes before produces:
  // the one declaring it wins, so the stream stays the default and JSON is only for who asks.
  @PostMapping(
      value = "/v1/spaces/{spaceId}/airlocks/{airlockId}/chat",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public void chat(
      @Parameter(description = "Id of a space", example = "hr-acme") @PathVariable String spaceId,
      @Parameter(description = "Id of an airlock in it", example = "hr") @PathVariable
          String airlockId,
      @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody ChatRequest request,
      HttpServletResponse response)
      throws IOException {
    tracing.exchange(
        spaceId,
        airlockId,
        () -> {
          // Admission first: while nothing is written, a failure can still become a status code.
          AirlockDefinition airlock =
              airlockAdmission.admit(spaceId, airlockId, jwt, request.message());
          agentLoop.answerUserMessage(
              airlock,
              request.message(),
              managers.createFor(airlock, jwt),
              jwt.getClaimAsString("azp"),
              new SseStream(response, jsonMapper).open());
          return null;
        });
  }

  @Operation(
      summary = "Ask an airlock one question and get the answer as one document",
      security = @SecurityRequirement(name = SwaggerApiConfig.BEARER_SCHEME))
  @ApiResponses(
      value = {
        @ApiResponse(
            responseCode = "500",
            description = "The exchange could not be completed",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE)),
        @ApiResponse(
            responseCode = "503",
            description = "The model is not available",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE))
      })
  @PostMapping(
      value = "/v1/spaces/{spaceId}/airlocks/{airlockId}/chat",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ChatAnswer chatAsJson(
      @Parameter(description = "Id of a space", example = "hr-acme") @PathVariable String spaceId,
      @Parameter(description = "Id of an airlock in it", example = "hr") @PathVariable
          String airlockId,
      @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody ChatRequest request) {
    return tracing.exchange(
        spaceId,
        airlockId,
        () -> {
          AirlockDefinition airlock =
              airlockAdmission.admit(spaceId, airlockId, jwt, request.message());
          return agentLoop.collectAnswer(
              airlock,
              request.message(),
              managers.createFor(airlock, jwt),
              jwt.getClaimAsString("azp"));
        });
  }
}
