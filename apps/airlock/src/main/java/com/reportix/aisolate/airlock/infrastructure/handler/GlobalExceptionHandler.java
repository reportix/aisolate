package com.reportix.aisolate.airlock.infrastructure.handler;

import com.reportix.aisolate.airlock.domain.airlock.AirlockNotFoundException;
import com.reportix.aisolate.airlock.domain.airlock.AuditUnavailableException;
import com.reportix.aisolate.airlock.domain.airlock.ForbiddenException;
import com.reportix.aisolate.airlock.domain.chat.ChatEvent;
import com.reportix.aisolate.airlock.domain.chat.ExchangeFailedException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** RFC 9457 problem details. Bodies stay generic; the reasons live in the log. */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

  /**
   * The audit trail has the refusal's reason as data; the log names it too, the body says nothing.
   */
  @ExceptionHandler(ForbiddenException.class)
  ProblemDetail forbidden(ForbiddenException denied, HttpServletRequest request) {
    log.warn("403 {} {}: {}", request.getMethod(), request.getRequestURI(), denied.getMessage());
    return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "forbidden");
  }

  /** Failing closed: no exchange the audit trail could not keep. */
  @ExceptionHandler(AuditUnavailableException.class)
  ProblemDetail auditUnavailable() {
    return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "audit unavailable");
  }

  @ExceptionHandler(AirlockNotFoundException.class)
  ProblemDetail unknownAirlock() {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "unknown airlock");
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ProblemDetail invalid() {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "message is blank or too long");
  }

  /** Only a synchronous answer gets here: a stream says the same in its {@code error} event. */
  @ExceptionHandler(ExchangeFailedException.class)
  ProblemDetail exchangeFailed(ExchangeFailedException failed) {
    boolean modelUnavailable =
        failed.getError().equals(ChatEvent.Error.MODEL_UNAVAILABLE)
            || failed.getError().equals(ChatEvent.Error.NO_MODEL_ENDPOINT);
    return ProblemDetail.forStatusAndDetail(
        modelUnavailable ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.INTERNAL_SERVER_ERROR,
        failed.getMessage());
  }
}
