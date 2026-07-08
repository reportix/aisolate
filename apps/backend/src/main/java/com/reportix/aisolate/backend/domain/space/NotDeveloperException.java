package com.reportix.aisolate.backend.domain.space;

/** 403: the caller is neither the admin nor a developer of the space, said in plain words. */
public class NotDeveloperException extends RuntimeException {
  public NotDeveloperException(String spaceId) {
    super(
        "You are not a developer of space " + spaceId + ": ask its admin to add you as Developer.");
  }
}
