package com.reportix.aisolate.config.rules;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The row-filter grammar: {@code true}, {@code <column> == principal.<attribute>} or {@code
 * <column> == '<literal>'}. Anything else is rejected when the rule is loaded, so free text can
 * never reach SQL; the value is always bound as a parameter. The backend checks with this parser
 * what the tool server later enforces with it. Which principal attributes exist is the tool
 * plugin's business (its identity join names one); the tool server denies an attribute the asking
 * user does not have.
 */
public record RowFilter(String column, String attribute, String literal) {

  private static final Pattern EQUALITY =
      Pattern.compile(
          "^\\s*([a-z_][a-z0-9_]*)\\s*==\\s*(?:principal\\.([a-z_][a-z0-9_]*)|'([a-z0-9_-]+)')\\s*$");

  public static final RowFilter ALL = new RowFilter(null, null, null);

  public static RowFilter parse(String text) {
    if (text == null) {
      throw new IllegalArgumentException("row_filter is required on an allow rule");
    }
    if (text.trim().equals("true")) {
      return ALL;
    }
    Matcher m = EQUALITY.matcher(text);
    if (!m.matches()) {
      throw new IllegalArgumentException(
          "row_filter must be 'true', '<column> == principal.<attribute>' or \"<column> =="
              + " '<literal>'\": "
              + text);
    }
    return new RowFilter(m.group(1), m.group(2), m.group(3));
  }

  public boolean matchesAll() {
    return column == null;
  }
}
