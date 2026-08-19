package com.reportix.aisolate.airlock.domain.airlock;

/**
 * The pod's buffer of audit lines the backend has not taken yet is full, and the install fails
 * closed ({@code platform.audit.failClosed}): no question is admitted until it drains (503).
 */
public class AuditUnavailableException extends RuntimeException {

  public AuditUnavailableException() {
    super("audit unavailable");
  }
}
