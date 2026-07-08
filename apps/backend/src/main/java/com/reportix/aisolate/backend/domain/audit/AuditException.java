package com.reportix.aisolate.backend.domain.audit;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** A request the audit endpoints refuse, with the status and the sentence the caller gets. */
@Getter
public class AuditException extends RuntimeException {

  private final HttpStatus status;

  public AuditException(HttpStatus status, String detail) {
    super(detail);
    this.status = status;
  }

  static AuditException invalid(String detail) {
    return new AuditException(HttpStatus.BAD_REQUEST, detail);
  }
}
