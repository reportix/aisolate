package com.reportix.aisolate.config;

import java.util.List;

/** Every problem found in one pass, each naming its file, document and field. */
public class ConfigurationException extends RuntimeException {

  private final List<String> problems;

  public ConfigurationException(List<String> problems) {
    super(String.join("\n", problems));
    this.problems = List.copyOf(problems);
  }

  public List<String> problems() {
    return problems;
  }
}
