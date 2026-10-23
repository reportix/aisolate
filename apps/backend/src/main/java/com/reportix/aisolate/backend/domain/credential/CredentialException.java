package com.reportix.aisolate.backend.domain.credential;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** A reveal the service refuses: the status, one plain sentence and a machine-readable reason. */
@Getter
public class CredentialException extends RuntimeException {

  private final HttpStatus status;
  private final String reason;

  public CredentialException(HttpStatus status, String detail, String reason) {
    super(detail);
    this.status = status;
    this.reason = reason;
  }
}
