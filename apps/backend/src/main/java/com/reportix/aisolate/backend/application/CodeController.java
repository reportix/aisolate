package com.reportix.aisolate.backend.application;

import com.reportix.aisolate.backend.domain.airlock.Airlock;
import com.reportix.aisolate.backend.domain.airlock.AirlockRepository;
import com.reportix.aisolate.backend.infrastructure.audit.AuditIngestPort;
import com.reportix.aisolate.backend.infrastructure.audit.AuditProperties;
import com.reportix.aisolate.config.AuditIngestToken;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * Where an airlock's pods get its code at start: the artifact by its digest, for the airlock's code
 * token alone (docs/configuration.md, "The code"). No user calls it, so it has no AIsolate UI role;
 * served on the ingest port alone ({@link AuditIngestPort}). The pod checks the digest of what it
 * got.
 */
@Hidden
@RestController
@RequiredArgsConstructor
public class CodeController {

  private static final String BEARER = "Bearer ";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final AirlockRepository airlocks;
  private final AuditProperties properties;

  // Written to the response itself: an async body would end at the container's async timeout.
  @GetMapping(AuditIngestPort.CODE_PATH + "/{space}/{airlock}/{digest}")
  public void fetch(
      @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @PathVariable String space,
      @PathVariable String airlock,
      @PathVariable String digest,
      HttpServletResponse response)
      throws IOException {
    String token =
        authorization != null && authorization.startsWith(BEARER)
            ? authorization.substring(BEARER.length()).strip()
            : null;
    if (token == null || token.isBlank()) {
      refuse(response, HttpStatus.UNAUTHORIZED, "a code token is required");
      return;
    }
    if (!AuditIngestToken.matchesCode(properties.ingestKey(), token, space, airlock)) {
      refuse(response, HttpStatus.FORBIDDEN, "forbidden");
      return;
    }
    var code =
        airlocks
            .find(airlock)
            .filter(row -> row.spaceId().equals(space))
            .map(Airlock::jar)
            .filter(Objects::nonNull)
            .filter(jar -> jar.digest().equalsIgnoreCase(digest));
    if (code.isEmpty()) {
      refuse(response, HttpStatus.NOT_FOUND, "no such code: the airlock holds other code or none");
      return;
    }
    response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
    response.setContentLengthLong(code.get().size());
    airlocks.streamCode(airlock, response.getOutputStream());
  }

  private static void refuse(HttpServletResponse response, HttpStatus status, String detail)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response
        .getWriter()
        .write(JSON.writeValueAsString(ProblemDetail.forStatusAndDetail(status, detail)));
  }
}
