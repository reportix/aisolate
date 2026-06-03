package com.reportix.aisolate.config;

import java.util.regex.Pattern;

/**
 * Whether an answer is "I don't know": the one rule the airlock's audit line and the audit report
 * both apply, so the report never counts as answered what the airlock logged as not known.
 */
public final class Answers {

  /** Markdown emphasis, quotes and code marks a model puts before the sentence. */
  private static final Pattern LEADING_MARKS = Pattern.compile("^[\\s*_>\"'`“‘]+");

  private static final Pattern DONT_KNOW =
      Pattern.compile("i don['’]t know(?!\\p{Alnum})", Pattern.CASE_INSENSITIVE);

  /**
   * An empty answer, or one that begins with the sentence: the model often goes on after it ("I
   * don't know. The list records no reason. [ev-1]").
   */
  public static boolean isDontKnow(String answer) {
    String text = LEADING_MARKS.matcher(answer).replaceFirst("");
    return text.isBlank() || DONT_KNOW.matcher(text).lookingAt();
  }

  private Answers() {}
}
