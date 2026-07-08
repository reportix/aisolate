package com.reportix.aisolate.backend.application;

import com.reportix.aisolate.backend.domain.audit.AuditService;
import com.reportix.aisolate.backend.infrastructure.audit.AuditIngestPort;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one endpoint no user calls, and so the one without an AIsolate UI role: an airlock pod hands
 * over its audit lines with its ingest token, which {@link AuditService#ingest} checks against the
 * airlock each line names. Served on the ingest port alone ({@link AuditIngestPort}).
 */
@Hidden
@RestController
@RequiredArgsConstructor
public class AuditIngestController {

  private static final String BEARER = "Bearer ";

  private final AuditService audit;

  @PostMapping(path = AuditIngestPort.PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, Long>> ingest(
      @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
      @RequestBody String line) {
    String token =
        authorization != null && authorization.startsWith(BEARER)
            ? authorization.substring(BEARER.length()).strip()
            : null;
    AuditService.Appended appended = audit.ingest(token, line);
    return ResponseEntity.status(appended.repeat() ? HttpStatus.OK : HttpStatus.CREATED)
        .body(Map.of("id", appended.id()));
  }
}
