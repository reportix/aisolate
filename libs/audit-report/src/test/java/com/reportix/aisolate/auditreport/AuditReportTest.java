package com.reportix.aisolate.auditreport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** A context per row, its keys as typed dimensions, and the row's facts in it. */
class AuditReportTest {

  private static final String LINE =
      "{\"ts\":\"2026-10-03T09:00:00Z\",\"event\":\"exchange\",\"trace_id\":\"t-1\","
          + "\"space\":\"aldermoor\",\"airlock\":\"cardiology\","
          + "\"outcome\":\"error\",\"duration_ms\":5}";

  private static final AuditReport.Period OCTOBER =
      new AuditReport.Period(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"));

  @Test
  void givenAuditLine_whenReported_thenAContextOfItsExchangeOverThePeriodAndItsFacts() {
    // Arrange
    AuditLine line = AuditLine.parse(LINE);

    // Act
    String xml = xml(line);

    // Assert
    assertThat(xml)
        .isEqualTo(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <xbrl xmlns="http://www.xbrl.org/2003/instance"
              xmlns:xbrli="http://www.xbrl.org/2003/instance"
              xmlns:aisolate="https://aisolate.ai/xbrl/audit/2026-10-26"
              xmlns:link="http://www.xbrl.org/2003/linkbase"
              xmlns:xlink="http://www.w3.org/1999/xlink"
              xmlns:xbrldi="http://xbrl.org/2006/xbrldi"
              xmlns:lei="http://standards.iso.org/iso/17442">
              <link:schemaRef xlink:type="simple" \
            xlink:href="https://aisolate.ai/xbrl/audit/2026-10-26/aisolate-audit-2026-10-26.xsd"/>
              <unit id="u-1">
                <measure>xbrli:pure</measure>
              </unit>
              <context id="c-1">
                <entity>
                  <identifier scheme="http://standards.iso.org/iso/17442">529900ALDERMOORHSP32</identifier>
                </entity>
                <period>
                  <startDate>2026-10-01</startDate>
                  <endDate>2026-10-31</endDate>
                </period>
                <scenario>
                  <xbrldi:typedMember dimension="aisolate:ExchangeAxis">
                    <aisolate:ExchangeId>t-1</aisolate:ExchangeId>
                  </xbrldi:typedMember>
                </scenario>
              </context>
              <aisolate:Space contextRef="c-1">aldermoor</aisolate:Space>
              <aisolate:Airlock contextRef="c-1">cardiology</aisolate:Airlock>
              <aisolate:ExchangeTime contextRef="c-1">2026-10-03T09:00:00Z</aisolate:ExchangeTime>
              <aisolate:Outcome contextRef="c-1">error</aisolate:Outcome>
              <aisolate:Duration contextRef="c-1">PT0.005S</aisolate:Duration>
            </xbrl>
            """);
  }

  @ParameterizedTest
  @CsvSource({
    "'\"user\":\"eric@example.com\",\"client\":\"acme-chat\",', eric@example.com",
    "'\"client\":\"acme-batch\",', acme-batch"
  })
  void givenAsker_whenReported_thenTheUserFactIsTheEmailOrTheMachineClientsId(
      String asker, String user) {
    // Arrange
    AuditLine line = AuditLine.parse(LINE.replace("\"outcome\"", asker + "\"outcome\""));

    // Act
    String xml = xml(line);

    // Assert
    assertThat(xml).contains("<aisolate:User contextRef=\"c-1\">" + user + "</aisolate:User>");
  }

  @Test
  void givenToolCall_whenReported_thenAContextOfItsExchangeAndPositionWithItsRowsAPureNumber() {
    // Arrange
    AuditLine line =
        AuditLine.parse(
            LINE.replace(
                "\"outcome\"",
                "\"tools\":[{\"name\":\"read_records\",\"data_source\":\"ehr\",\"rows\":3}],"
                    + "\"outcome\""));

    // Act
    String xml = xml(line);

    // Assert
    assertThat(xml)
        .contains(
            """
                <scenario>
                  <xbrldi:typedMember dimension="aisolate:ExchangeAxis">
                    <aisolate:ExchangeId>t-1</aisolate:ExchangeId>
                  </xbrldi:typedMember>
                  <xbrldi:typedMember dimension="aisolate:ToolCallAxis">
                    <aisolate:ToolCallPosition>1</aisolate:ToolCallPosition>
                  </xbrldi:typedMember>
                </scenario>
              </context>
              <aisolate:ToolName contextRef="c-2">read_records</aisolate:ToolName>
              <aisolate:DataSource contextRef="c-2">ehr</aisolate:DataSource>
              <aisolate:RowsReturned contextRef="c-2" unitRef="u-1" decimals="INF">3</aisolate:RowsReturned>
            """);
  }

  static Stream<Arguments> texts() {
    return Stream.of(
        arguments(
            "\"question\":\"Is 1 < 2 && \\\"b\\\" > a?\",",
            "<aisolate:Question contextRef=\"c-1\">Is 1 &lt; 2 &amp;&amp; &quot;b&quot; &gt;"
                + " a?</aisolate:Question>"),
        // A bare CR would read as a line feed; a bell is no XML character
        arguments(
            "\"question\":\"one\\r\\ntwo\\u0007\",",
            "<aisolate:Question contextRef=\"c-1\">one&#13;\ntwo�</aisolate:Question>"),
        // An empty answer is a fact, an empty string
        arguments("\"answer\":\"\",", "<aisolate:Answer contextRef=\"c-1\"/>"));
  }

  @ParameterizedTest
  @MethodSource("texts")
  void givenText_whenReported_thenItsFactAsXmlHoldsIt(String field, String fact) {
    // Arrange
    AuditLine line = AuditLine.parse(LINE.replace("\"outcome\"", field + "\"outcome\""));

    // Act
    String xml = xml(line);

    // Assert
    assertThat(xml).contains(fact);
  }

  @ParameterizedTest
  @ValueSource(strings = {"space", "airlock"})
  void givenAuditLineWithoutAnIdentifier_whenParsed_thenRefused(String field) {
    // Arrange
    String line = LINE.replace("\"" + field + "\":", "\"other\":");

    // Act
    Throwable thrown = catchThrowable(() -> AuditLine.parse(line));

    // Assert
    assertThat(thrown)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("no space or airlock");
  }

  private static String xml(AuditLine line) {
    return AuditReport.of(List.of(line), OCTOBER, AuditReport.Entity.parse("529900ALDERMOORHSP32"))
        .xml();
  }
}
