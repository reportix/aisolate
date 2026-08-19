package com.reportix.aisolate.airlock.domain.chat;

import lombok.Getter;

/**
 * A collected exchange ended in an {@code error} event. Where the stream says it in the event, the
 * synchronous answer says it as a status and a problem document; the message is the event's fixed
 * sentence, the cause stays in the pod's log.
 */
@Getter
public class ExchangeFailedException extends RuntimeException {

  private final transient ChatEvent.Error error;

  public ExchangeFailedException(ChatEvent.Error error) {
    super(error.message());
    this.error = error;
  }
}
