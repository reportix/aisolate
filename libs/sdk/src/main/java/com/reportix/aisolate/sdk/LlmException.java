package com.reportix.aisolate.sdk;

/**
 * An LLM named wrongly in code: an id the catalog lacks, or a model asked for what it cannot do.
 * Its message is plain and holds no data, so the airlock logs it whole.
 */
public class LlmException extends IllegalArgumentException {

  public LlmException(String message) {
    super(message);
  }
}
