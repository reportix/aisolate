package com.reportix.aisolate.airlock.domain.chat.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.reportix.aisolate.airlock.support.AuditCapture;
import com.reportix.aisolate.sdk.Verdict;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AuditLogTest {

  @RegisterExtension final AuditCapture audit = new AuditCapture();

  @Test
  void givenEvent_whenRecorded_thenOneJsonLineWithSnakeCaseFields() {
    // Arrange: the mapper as it is, with no naming strategy of its own
    AuditEvent event =
        new AuditEvent(
            Instant.parse("2026-10-16T20:15:30.123Z"),
            AuditEvent.Event.EXCHANGE,
            "4bf92f3577b34da6a3ce929d0e0e4736",
            "hr-acme",
            "acme-hr",
            "7b2280eab4b5",
            "eric@example.com",
            "acme-chat",
            "What is my salary?",
            "Your salary is 78,000 EUR.",
            AuditEvent.Outcome.ANSWERED,
            null,
            List.of(
                new AuditEvent.Tool(
                    "read_records",
                    Map.of("limit", 10),
                    "hr-documents",
                    1,
                    "7b2280eab4b5",
                    "abc123def456",
                    "9f86d0",
                    null)),
            List.of("ev-1"),
            List.of(new AuditEvent.Verifier("FigureVerifier", Verdict.Status.PASS)),
            new AuditEvent.Figures(1, 0, 0, 0),
            Map.of("segments", List.of(Map.of("text", "Your salary is 78,000 EUR."))),
            new AuditEvent.Model("ollama", "qwen3", 2, 200, 40),
            1840);

    // Act
    new AuditLog(JsonMapper.builder().build(), new SimpleMeterRegistry()).record(event);

    // Assert: one message, one object, every field named in snake case, in a fixed order
    assertThat(audit.lines()).hasSize(1);
    assertThat(audit.lines().getFirst()).doesNotContain("\n");
    JsonNode line = audit.events().getFirst();
    assertThat(new ArrayList<>(line.propertyNames()))
        .containsExactly(
            "ts",
            "event",
            "trace_id",
            "space",
            "airlock",
            "revision",
            "user",
            "client",
            "question",
            "answer",
            "outcome",
            "reason",
            "tools",
            "evidence",
            "verifiers",
            "figures",
            "grounding",
            "model",
            "duration_ms");
    assertThat(line.path("ts").asString()).isEqualTo("2026-10-16T20:15:30.123Z");
    assertThat(line.path("event").asString()).isEqualTo("exchange");
    assertThat(line.path("trace_id").asString()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    assertThat(line.path("outcome").asString()).isEqualTo("answered");
    assertThat(line.path("reason").isNull()).isTrue();
    JsonNode tool = line.path("tools").get(0);
    assertThat(new ArrayList<>(tool.propertyNames()))
        .containsExactly(
            "name",
            "args",
            "data_source",
            "rows",
            "rule_version",
            "sql_hash",
            "rows_hash",
            "error");
    assertThat(tool.path("error").isNull()).isTrue();
    assertThat(tool.path("args").path("limit").asInt()).isEqualTo(10);
    assertThat(tool.path("rows_hash").asString()).isEqualTo("9f86d0");
    assertThat(new ArrayList<>(line.path("figures").propertyNames()))
        .containsExactly("sourced", "computed", "unsourced", "mismatch");
    assertThat(tool.path("data_source").asString()).isEqualTo("hr-documents");
    assertThat(line.path("verifiers").get(0).path("verdict").asString()).isEqualTo("PASS");
    assertThat(new ArrayList<>(line.path("model").propertyNames()))
        .containsExactly("provider", "name", "calls", "input_tokens", "output_tokens");
    assertThat(line.path("duration_ms").asLong()).isEqualTo(1840);
  }

  @Test
  void givenRefusal_whenRecorded_thenNullsAndEmptyListsStayOnTheLine() {
    // Arrange: nothing ran, so there is no answer, no trace and no model to name
    AuditEvent refusal =
        AuditEvent.refusal(
            Instant.parse("2026-10-16T20:15:30.123Z"),
            null,
            "hr-acme",
            "acme-hr",
            "7b2280eab4b5",
            "ivy@example.com",
            "acme-chat",
            "hi",
            "user",
            3);

    // Act
    new AuditLog(JsonMapper.builder().build(), new SimpleMeterRegistry()).record(refusal);

    // Assert: a reader finds every field on every line
    JsonNode line = audit.events().getFirst();
    assertThat(line.propertyNames()).hasSize(19);
    assertThat(line.path("figures").isNull()).isTrue();
    assertThat(line.path("event").asString()).isEqualTo("refusal");
    assertThat(line.path("outcome").asString()).isEqualTo("refused");
    assertThat(line.path("reason").asString()).isEqualTo("user");
    assertThat(line.path("answer").isNull()).isTrue();
    assertThat(line.path("model").isNull()).isTrue();
    assertThat(line.path("tools")).isEmpty();
    assertThat(line.path("evidence")).isEmpty();
    assertThat(line.path("verifiers")).isEmpty();
  }
}
