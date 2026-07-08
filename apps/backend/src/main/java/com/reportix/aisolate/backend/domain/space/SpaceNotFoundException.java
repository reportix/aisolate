package com.reportix.aisolate.backend.domain.space;

public class SpaceNotFoundException extends RuntimeException {
  public SpaceNotFoundException(String id) {
    super("no space " + id);
  }
}
