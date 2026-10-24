package com.reportix.aisolate.backend.infrastructure.handler;

import com.reportix.aisolate.backend.domain.airlock.AirlockNotFoundException;
import com.reportix.aisolate.backend.domain.airlock.ConflictException;
import com.reportix.aisolate.backend.domain.airlock.InvalidFormException;
import com.reportix.aisolate.backend.domain.audit.AuditException;
import com.reportix.aisolate.backend.domain.credential.CredentialException;
import com.reportix.aisolate.backend.domain.space.NotDeveloperException;
import com.reportix.aisolate.backend.domain.space.SpaceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** RFC 9457 problem details. Bodies stay generic; the reasons live in the log. */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

  /** Unlimited (-1) unless the operator set {@code AISOLATE_MAX_ARTIFACT_SIZE}. */
  @Value("${spring.servlet.multipart.max-file-size}")
  private DataSize maxJar;

  /** There is no audit log, so the refusal reason is at least logged; the body says nothing. */
  @ExceptionHandler(AuthorizationDeniedException.class)
  ProblemDetail forbidden(Exception denied, HttpServletRequest request) {
    log.warn("403 {} {}: {}", request.getMethod(), request.getRequestURI(), denied.getMessage());
    return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "forbidden");
  }

  /** The one 403 that says why: the caller can ask the space's admin for the role. */
  @ExceptionHandler(NotDeveloperException.class)
  ProblemDetail notDeveloper(NotDeveloperException e, HttpServletRequest request) {
    log.warn("403 {} {}: {}", request.getMethod(), request.getRequestURI(), e.getMessage());
    return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
  }

  /** The detail, when set: {@code no model named …}, or what the caller should read. */
  @ExceptionHandler(AirlockNotFoundException.class)
  ProblemDetail unknownAirlock(AirlockNotFoundException e) {
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.NOT_FOUND, e.getDetail() == null ? "unknown airlock" : e.getDetail());
  }

  @ExceptionHandler(SpaceNotFoundException.class)
  ProblemDetail unknownSpace() {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "unknown space");
  }

  /**
   * The audit endpoints' own refusals: each says which parameter or why, never a line's content.
   */
  @ExceptionHandler(AuditException.class)
  ProblemDetail audit(AuditException e) {
    return ProblemDetail.forStatusAndDetail(e.getStatus(), e.getMessage());
  }

  /** A refused reveal: the sentence and a {@code reason} the UI branches on, never a value. */
  @ExceptionHandler(CredentialException.class)
  ProblemDetail credential(CredentialException e) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(e.getStatus(), e.getMessage());
    problem.setProperty("reason", e.getReason());
    return problem;
  }

  /** The one body that says more: the developer needs the field errors to fix the form. */
  @ExceptionHandler(InvalidFormException.class)
  ProblemDetail invalidForm(InvalidFormException e) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "invalid form");
    problem.setProperty("errors", e.getErrors());
    return problem;
  }

  /**
   * Refused while the request is read, before any form exists: only with an operator's limit. Every
   * part is held to {@code spring.servlet.multipart.max-file-size}; the jar is the one that grows.
   */
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ProblemDetail partTooLarge(MaxUploadSizeExceededException e) {
    // The container names the field: "The field [jar] exceeds its maximum permitted size …".
    String cause = String.valueOf(NestedExceptionUtils.getMostSpecificCause(e).getMessage());
    String part = JAR_FIELD.matcher(cause).find() ? "jar" : "a part";
    return invalidForm(
        new InvalidFormException(
            List.of(part + ": larger than " + describe(maxJar) + ", the most this install takes")));
  }

  private static final Pattern JAR_FIELD = Pattern.compile("\\bjar\\b");

  /** As configured: {@code 1MB} reads 1 MiB, {@code 512KB} 512 KiB, never rounded to 0. */
  private static String describe(DataSize size) {
    long bytes = size.toBytes();
    return bytes % (1024 * 1024) == 0
        ? bytes / (1024 * 1024) + " MiB"
        : bytes % 1024 == 0 ? bytes / 1024 + " KiB" : bytes + " bytes";
  }

  /** The detail, and the same message on its field for the form that sent it. */
  @ExceptionHandler(ConflictException.class)
  ProblemDetail conflict(ConflictException e) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    problem.setProperty("errors", List.of(e.fieldError()));
    return problem;
  }
}
