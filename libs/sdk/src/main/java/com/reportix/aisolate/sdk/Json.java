package com.reportix.aisolate.sdk;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A JSON reader for project code: a verifier's {@code Answer.groundingJson()} and {@code
 * Evidence.contentJson()}. Objects are insertion-ordered maps, numbers {@link BigDecimal}.
 */
public final class Json {

  // Far deeper than any grounding or evidence JSON, so a hostile text cannot overflow the stack.
  // parse's Javadoc and JsonTest name it.
  private static final int MAX_DEPTH = 500;

  private final String text;
  private int pos;

  private Json(String text) {
    this.text = text == null ? "" : text;
  }

  /**
   * Reads one JSON value, strictly as RFC 8259 has it: no comments, no trailing commas, no NaN or
   * Infinity, no single quotes.
   *
   * <p>A number such as {@code 1e999999999} parses, and {@code toPlainString()} or {@code
   * toBigInteger()} on it allocates hugely or fails: compare with {@code compareTo}, print with
   * {@code toString}.
   *
   * @return a {@code Map<String, Object>} for an object, a {@code List<Object>} for an array, a
   *     {@code String}, a {@link BigDecimal} keeping the number's written scale, a {@code Boolean},
   *     or {@code null} for the literal null
   * @throws IllegalArgumentException {@code not JSON at <offset>} (in chars) if the text is not
   *     exactly one JSON value, and also if a value sits inside more than 500 arrays and objects,
   *     if a number's exponent is beyond what {@link BigDecimal} holds (the offset is the number's
   *     start), or if the text is {@code null} (offset 0)
   */
  public static Object parse(String text) {
    Json json = new Json(text);
    Object value = json.value(0);
    json.skipWhitespace();
    if (json.pos < json.text.length()) {
      throw fail(json.pos);
    }
    return value;
  }

  // depth: how many arrays and objects enclose the value
  private Object value(int depth) {
    if (depth > MAX_DEPTH) {
      throw fail(pos);
    }
    skipWhitespace();
    int c = peek();
    return switch (c) {
      case '{' -> object(depth);
      case '[' -> array(depth);
      case '"' -> string();
      case 't' -> literal("true", Boolean.TRUE);
      case 'f' -> literal("false", Boolean.FALSE);
      case 'n' -> literal("null", null);
      default -> {
        if (c == '-' || isDigit(c)) {
          yield number();
        }
        throw fail(pos);
      }
    };
  }

  private Map<String, Object> object(int depth) {
    pos++; // {
    Map<String, Object> members = new LinkedHashMap<>();
    if (consume('}')) {
      return members;
    }
    do {
      skipWhitespace();
      if (peek() != '"') {
        throw fail(pos);
      }
      String name = string();
      expect(':');
      members.put(name, value(depth + 1));
    } while (consume(','));
    expect('}');
    return members;
  }

  private List<Object> array(int depth) {
    pos++; // [
    List<Object> items = new ArrayList<>();
    if (consume(']')) {
      return items;
    }
    do {
      items.add(value(depth + 1));
    } while (consume(','));
    expect(']');
    return items;
  }

  private String string() {
    StringBuilder out = new StringBuilder();
    pos++; // opening quote
    for (int c = peek(); c != '"'; c = peek()) {
      if (c < 0x20) { // end of text, or a control character, which JSON requires escaped
        throw fail(pos);
      }
      pos++;
      // an escaped surrogate pair lands as two chars, which Java reads as one code point
      out.append(c == '\\' ? escape() : (char) c);
    }
    pos++; // closing quote
    return out.toString();
  }

  // after the backslash
  private char escape() {
    int e = peek();
    pos++;
    return switch (e) {
      case '"', '\\', '/' -> (char) e;
      case 'b' -> '\b';
      case 'f' -> '\f';
      case 'n' -> '\n';
      case 'r' -> '\r';
      case 't' -> '\t';
      case 'u' -> unicode();
      default -> throw fail(pos - 1);
    };
  }

  private char unicode() {
    int code = 0;
    for (int i = 0; i < 4; i++) {
      int digit = hexDigit(peek());
      if (digit < 0) {
        throw fail(pos);
      }
      code = code * 16 + digit;
      pos++;
    }
    return (char) code;
  }

  private BigDecimal number() {
    int start = pos;
    if (peek() == '-') {
      pos++;
    }
    if (peek() == '0') { // no leading zeros: a digit after it is trailing text
      pos++;
    } else {
      digits();
    }
    if (peek() == '.') {
      pos++;
      digits();
    }
    if (peek() == 'e' || peek() == 'E') {
      pos++;
      if (peek() == '+' || peek() == '-') {
        pos++;
      }
      digits();
    }
    try {
      return new BigDecimal(text.substring(start, pos));
    } catch (NumberFormatException e) { // an exponent beyond BigDecimal's range
      throw fail(start);
    }
  }

  private void digits() {
    if (!isDigit(peek())) {
      throw fail(pos);
    }
    while (isDigit(peek())) {
      pos++;
    }
  }

  private Object literal(String word, Object value) {
    if (!text.startsWith(word, pos)) {
      throw fail(pos);
    }
    pos += word.length();
    return value;
  }

  private boolean consume(char c) {
    skipWhitespace();
    if (peek() != c) {
      return false;
    }
    pos++;
    return true;
  }

  private void expect(char c) {
    if (!consume(c)) {
      throw fail(pos);
    }
  }

  // JSON's whitespace is these four, not Character.isWhitespace
  private void skipWhitespace() {
    while (" \t\n\r".indexOf(peek()) >= 0) {
      pos++;
    }
  }

  // -1 at the end of the text
  private int peek() {
    return pos < text.length() ? text.charAt(pos) : -1;
  }

  // ASCII only, unlike Character.isDigit and Character.digit
  private static boolean isDigit(int c) {
    return c >= '0' && c <= '9';
  }

  private static int hexDigit(int c) {
    if (isDigit(c)) {
      return c - '0';
    }
    if (c >= 'a' && c <= 'f') {
      return c - 'a' + 10;
    }
    if (c >= 'A' && c <= 'F') {
      return c - 'A' + 10;
    }
    return -1;
  }

  private static IllegalArgumentException fail(int offset) {
    return new IllegalArgumentException("not JSON at " + offset);
  }
}
