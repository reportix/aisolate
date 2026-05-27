package com.reportix.aisolate.sdk;

import java.util.List;

/**
 * The text the user gets, the verdicts collected so far, the question as typed, and the grounded
 * answer as JSON text ({@code {answer, segments, figures, tables, quotes, provenance}}), empty only
 * for an Answer built without it (the airlock always fills it, a plain answer included).
 */
public record Answer(String text, List<Verdict> verdicts, String question, String groundingJson) {
  public Answer {
    text = text == null ? "" : text;
    verdicts = verdicts == null ? List.of() : List.copyOf(verdicts);
    question = question == null ? "" : question;
    groundingJson = groundingJson == null ? "" : groundingJson;
  }

  public Answer(String text, List<Verdict> verdicts) {
    this(text, verdicts, "", "");
  }
}
