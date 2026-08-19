package com.reportix.aisolate.airlock.infrastructure.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.reportix.aisolate.config.AirlockDocument;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * The airlock's LLMs as the controller rendered them into the airlock pod's env: {@code
 * AISOLATE_LLM}, the main one's id, and {@code AISOLATE_LLM_<i>_ID}, {@code _PROVIDER}, {@code
 * _URL}, {@code _MODEL} and an optional {@code _API_KEY}, read until the first missing {@code _ID}.
 * The controller resolved the levels; the pod never does.
 *
 * @param main the id of the LLM that answers the exchange, one of {@code entries}
 */
public record LlmCatalog(String main, List<Entry> entries) {

  /**
   * @param apiKey the bearer of an {@code openai} one, from its Secret; null without one
   */
  public record Entry(
      String id, String provider, String url, String model, @JsonIgnore String apiKey) {

    public Entry(String id, String provider, String url, String model) {
      this(id, provider, url, model, null);
    }

    /** Without the key: an entry ends up in logs and failure messages. */
    @Override
    public String toString() {
      return "Entry[id="
          + id
          + ", provider="
          + provider
          + ", url="
          + url
          + ", model="
          + model
          + "]";
    }
  }

  /**
   * Refused at the start, as {@code DataSourceLogin.check}: a wrong entry would otherwise fail at
   * its first question, and a second entry with one id would silently replace the first.
   *
   * @param env the pod's environment by variable name, null for an unset one
   */
  public static LlmCatalog read(UnaryOperator<String> env) {
    String main = env.apply("AISOLATE_LLM");
    if (blank(main)) {
      throw new IllegalStateException("AISOLATE_LLM is blank");
    }
    List<Entry> entries = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    for (int i = 0; env.apply("AISOLATE_LLM_" + i + "_ID") != null; i++) {
      String prefix = "AISOLATE_LLM_" + i + "_";
      Entry entry =
          new Entry(
              env.apply(prefix + "ID"),
              env.apply(prefix + "PROVIDER"),
              env.apply(prefix + "URL"),
              env.apply(prefix + "MODEL"),
              blank(env.apply(prefix + "API_KEY")) ? null : env.apply(prefix + "API_KEY"));
      for (String field : List.of("ID", "URL", "MODEL")) {
        if (blank(env.apply(prefix + field))) {
          throw new IllegalStateException(prefix + field + " is blank");
        }
      }
      if (!ids.add(entry.id())) {
        throw new IllegalStateException("model " + entry.id() + " is defined twice");
      }
      if (!AirlockDocument.PROVIDERS.contains(entry.provider())) {
        throw new IllegalStateException(
            "model "
                + entry.id()
                + ": provider "
                + entry.provider()
                + " is not one of "
                + AirlockDocument.PROVIDERS);
      }
      entries.add(entry);
    }
    if (!ids.contains(main)) {
      throw new IllegalStateException(
          "AISOLATE_LLM " + main + " is none of the AISOLATE_LLM_<i>_ID");
    }
    return new LlmCatalog(main, List.copyOf(entries));
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
