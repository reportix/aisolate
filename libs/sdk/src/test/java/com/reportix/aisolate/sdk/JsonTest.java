package com.reportix.aisolate.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@SuppressWarnings("unchecked")
class JsonTest {

  // the nesting limit parse's Javadoc names
  private static final int MAX_NESTING = 500;

  @Test
  void givenNestedObject_whenParsed_thenMapsListsAndScalars() {
    // Arrange
    String text =
        "{\"a\":[1,-2.50,3e2],"
            + "\"b\":{\"c\":\"x\\ny\",\"d\":true,\"e\":null}, "
            + "\"f\" : \"\\u00e9\\ud83d\\ude00\"}";

    // Act
    Map<String, Object> root = (Map<String, Object>) Json.parse(text);

    // Assert
    assertThat(root.keySet()).containsExactly("a", "b", "f");
    assertThat((List<Object>) root.get("a"))
        .containsExactly(new BigDecimal("1"), new BigDecimal("-2.50"), new BigDecimal("3e2"));
    Map<String, Object> b = (Map<String, Object>) root.get("b");
    assertThat(b).containsEntry("c", "x\ny").containsEntry("d", true).containsEntry("e", null);
    assertThat(root.get("f")).isEqualTo("é😀");
  }

  @Test
  void givenGroundingJson_whenParsed_thenFigureSourceReadable() {
    // Arrange: the airlock's shape (docs/api.md, the JSON answer)
    String text =
        """
        {"answer":"Your gross salary is 78,000 EUR.","segments":[{"text":"Your gross salary is "},{"figure":"f1"},{"text":"."}],"figures":[{"id":"f1","display":"78,000 EUR","value":"78000","status":"sourced","source":{"evidence":"ev-2","entity":"hr.compensation","column":"gross_salary","row_key":"3","subject":"Eric Lindholm","unit":"EUR"}}],"tables":[],"quotes":[],"provenance":{"status":"all_sourced","sourced":1,"computed":0,"unsourced":0,"mismatch":0}}
        """;

    // Act
    Map<String, Object> root = (Map<String, Object>) Json.parse(text);

    // Assert
    Map<String, Object> figure = (Map<String, Object>) ((List<Object>) root.get("figures")).get(0);
    assertThat(((Map<String, Object>) figure.get("source")).get("subject"))
        .isEqualTo("Eric Lindholm");
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        // A null text (an unquoted empty value) and an empty one both fail at 0
        "|0",
        "''|0",
        "{|1",
        "[1,]|3",
        "[1 2]|3",
        "{\"a\":1}x|7",
        "{'a':1}|1",
        "NaN|0",
        "[01]|2",
        "\"abc|4",
        "1e99999999999|0",
        "[1e99999999999]|1",
        "{\"a\":1e-2147483648}|5"
      })
  void givenMalformedText_whenParsed_thenMessageNamesTheOffset(String text, int offset) {
    // Arrange
    String message = "not JSON at " + offset;

    // Act
    Throwable thrown = catchThrowable(() -> Json.parse(text));

    // Assert
    assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessage(message);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Infinity",
        "-Infinity",
        "+1",
        ".5",
        "1.",
        "1e",
        "-",
        "tru",
        "[1] // note",
        "/* note */ 1",
        "[,1]",
        "[1,,2]",
        "{,}",
        "{\"a\" 1}",
        "{\"a\":1,}",
        "{1:2}",
        "\"\\x\"",
        "\"\\u12G4\"",
        "\"\\u12\"",
        "\"a\tb\"",
        "[1,\f2]",
        "１",
        "\"\\u００41\""
      })
  void givenNonStrictText_whenParsed_thenIllegalArgument(String text) {
    // Arrange: the text is the parameter, each one RFC 8259 forbids

    // Act
    Throwable thrown = catchThrowable(() -> Json.parse(text));

    // Assert
    assertThat(thrown)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not JSON");
  }

  @ParameterizedTest
  @MethodSource("escapes")
  void givenEscape_whenParsed_thenTheCharacter(String escape, String character) {
    // Arrange
    String text = "\"" + escape + "\"";

    // Act
    Object parsed = Json.parse(text);

    // Assert
    assertThat(parsed).isEqualTo(character);
  }

  static Stream<org.junit.jupiter.params.provider.Arguments> escapes() {
    return Stream.of(
        arguments("\\\"", "\""),
        arguments("\\\\", "\\"),
        arguments("\\/", "/"),
        arguments("\\b", "\b"),
        arguments("\\f", "\f"),
        arguments("\\n", "\n"),
        arguments("\\r", "\r"),
        arguments("\\t", "\t"),
        arguments("\\u0041", "A"),
        arguments("\\u00E9", "é"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "0",
        "-0",
        "10",
        "-12.50",
        "0.5",
        "1E5",
        "1e-2",
        "2.5E+3",
        "123456789012345678901234567890.123456789"
      })
  void givenNumber_whenParsed_thenBigDecimalOfTheSameText(String text) {
    // Arrange
    BigDecimal expected = new BigDecimal(text);

    // Act
    Object parsed = Json.parse(text);

    // Assert
    assertThat(parsed).isEqualTo(expected);
  }

  @Test
  void givenLiteralsAndEmptyContainers_whenParsed_thenTheValues() {
    // Arrange
    String text = " [ true ,\tfalse\r\n, null , { } , [ ] ] ";

    // Act
    List<Object> parsed = (List<Object>) Json.parse(text);

    // Assert
    assertThat(parsed).containsExactly(true, false, null, Map.of(), List.of());
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {"[|]", "{\"a\":|}"})
  void givenDeeplyNestedValues_whenParsed_thenIllegalArgumentNotStackOverflow(
      String open, String close) {
    // Arrange: far beyond any stack; the message pins the limit, where the first value inside
    // more than MAX_NESTING containers starts
    String text = open.repeat(100_000) + "1" + close.repeat(100_000);
    String message = "not JSON at " + open.length() * (MAX_NESTING + 1);

    // Act
    Throwable thrown = catchThrowable(() -> Json.parse(text));

    // Assert
    assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessage(message);
  }
}
