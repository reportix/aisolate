package com.reportix.aisolate.airlock.application;

import com.reportix.aisolate.airlock.domain.airlock.AirlockAdmissionService;
import com.reportix.aisolate.airlock.domain.airlock.AirlockDefinition;
import com.reportix.aisolate.airlock.domain.chat.AgentLoopService;
import com.reportix.aisolate.airlock.domain.chat.ChatAnswer;
import com.reportix.aisolate.airlock.domain.chat.ExchangeTracing;
import com.reportix.aisolate.airlock.infrastructure.plugins.ExchangeManagerFactory;
import com.reportix.aisolate.airlock.infrastructure.stream.SseStream;
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

  // Both handlers match an Accept of "*/*" or none, and Spring then ranks consumes before produces:
  // the one declaring it wins, so the stream stays the default and JSON is only for who asks.
  @PostMapping(
      value = "/v1/spaces/{spaceId}/airlocks/{airlockId}/chat",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public void chat(
      @PathVariable String spaceId,
      @PathVariable String airlockId,
      @AuthenticationPrincipal Jwt jwt,
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

  @PostMapping(
      value = "/v1/spaces/{spaceId}/airlocks/{airlockId}/chat",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ChatAnswer chatAsJson(
      @PathVariable String spaceId,
      @PathVariable String airlockId,
      @AuthenticationPrincipal Jwt jwt,
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
