package com.reportix.aisolate.backend.domain.airlock;

import lombok.Getter;

/** No such airlock in the space; {@code detail}, when set, is what the caller reads. */
@Getter
public class AirlockNotFoundException extends RuntimeException {

  private final String detail;

  public AirlockNotFoundException(String id) {
    this(id, null);
  }

  public AirlockNotFoundException(String id, String detail) {
    super("no airlock " + id);
    this.detail = detail;
  }
}
