package com.reportix.aisolate.airlock.domain.chat;

/**
 * A handler named a data source its airlock does not define: {@code <space>/<airlock> has no data
 * source <id>}. Plain and data-free like {@link com.reportix.aisolate.sdk.LlmException}, so the
 * handler's failure is logged with it.
 */
public class UnknownDataSourceException extends IllegalArgumentException {

  public UnknownDataSourceException(String message) {
    super(message);
  }
}
