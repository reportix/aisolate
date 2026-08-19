package com.reportix.aisolate.airlock.domain.airlock;

public class AirlockNotFoundException extends RuntimeException {
  public AirlockNotFoundException(String id) {
    super("no airlock " + id);
  }
}
