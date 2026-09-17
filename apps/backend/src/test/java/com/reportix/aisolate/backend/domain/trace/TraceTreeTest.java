package com.reportix.aisolate.backend.domain.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TraceTreeTest {

  @ParameterizedTest
  @CsvSource({
    "http post /v1/spaces/{spaceId}/airlocks/{airlockId}/chat, , http",
    "exchange hr-space/hr, , exchange",
    "admission, , admission",
    "Read HR documents, aisolate.handler, handler",
    "Remember the conversation, aisolate.handler, handler",
    "Check the totals, aisolate.verdict, verifier",
    "embedding, , embedding",
    "chat granite4.1:8b, , model",
    "tool read_records, , tool",
    "http post /mcp, , mcp",
    "governed query read_records, , query",
    "sql hr, , sql",
    "spring_ai chat_client, , other"
  })
  void givenASpan_whenItsKindIsRead_thenTheStepTheRuntimeContractNames(
      String name, String attribute, String kind) {
    // Arrange
    Map<String, Object> tags = attribute == null ? Map.of() : Map.of(attribute, "x");

    // Act
    String read = TraceTree.kind(name, tags);

    // Assert
    assertThat(read).isEqualTo(kind);
  }

  @Test
  void givenSpansOutOfOrder_whenTreeBuilt_thenChildrenUnderParentsByStartAndTimedFromTheFirst() {
    // Arrange
    List<TraceTree.Span> spans =
        List.of(
            span("sql", "query", "sql hr", 1_400, Map.of()),
            span("tool", "exchange", "tool read_records", 1_200, Map.of("aisolate.rows", 1L)),
            span("chat", "exchange", "chat granite4.1:8b", 1_100, Map.of()),
            span("exchange", null, "exchange hr/v1", 1_000, Map.of("aisolate.space", "hr")),
            span("query", "tool", "governed query read_records", 1_300, Map.of()),
            span("orphan", "not-in-the-trace", "http post", 1_500, Map.of()));

    // Act
    List<TraceTree.Step> roots = TraceTree.of(spans);

    // Assert
    assertThat(roots).extracting(TraceTree.Step::id).containsExactly("exchange", "orphan");
    TraceTree.Step exchange = roots.getFirst();
    assertThat(exchange.startUs()).isZero();
    assertThat(exchange.children()).extracting(TraceTree.Step::id).containsExactly("chat", "tool");
    TraceTree.Step tool = exchange.children().get(1);
    assertThat(tool.startUs()).isEqualTo(200);
    assertThat(tool.attributes()).containsEntry("aisolate.rows", 1L);
    assertThat(tool.children().getFirst().children())
        .extracting(TraceTree.Step::kind)
        .containsExactly("sql");
  }

  @Test
  void givenAFailedSpan_whenTreeBuilt_thenItsErrorTypeAndNoDescriptionOrBookkeeping() {
    // Arrange
    Map<String, Object> tags =
        Map.of(
            "error", true,
            "error.type", "java.net.ConnectException",
            "otel.status_description", "Connection refused: ollama:11434",
            "exception.message", "Connection refused",
            "aisolate.airlock", "hr",
            "gen_ai.request.model", "granite4.1:8b");

    // Act
    TraceTree.Step step =
        TraceTree.of(List.of(span("chat", null, "chat granite", 0, tags))).getFirst();

    // Assert
    assertThat(step.error()).isTrue();
    assertThat(step.errorType()).isEqualTo("java.net.ConnectException");
    assertThat(step.attributes()).containsOnlyKeys("gen_ai.request.model");
  }

  private static TraceTree.Span span(
      String id, String parent, String name, long start, Map<String, Object> tags) {
    return new TraceTree.Span(id, parent, name, "aisolate-airlock", start, 50, tags);
  }
}
