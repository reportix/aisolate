package com.reportix.aisolate.backend.domain.trace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * A trace's spans as the AIsolate UI's trace page shows them: a tree of steps, each with its kind,
 * its time from the trace's start, whether it failed and its attributes (docs/observability.md).
 */
public final class TraceTree {

  /**
   * Every span repeats the airlock, which the page's header shows once; the rest is the exporter's
   * bookkeeping. A status description or an exception attribute may quote what never goes into a
   * span (the runtime sets neither): dropped, should a span carry one anyway.
   */
  private static final Set<String> NOT_SHOWN =
      Set.of(
          "aisolate.space",
          "aisolate.airlock",
          "aisolate.revision",
          "error",
          "error.type",
          "otel.status_code",
          "otel.status_description",
          "otel.scope.name",
          "otel.scope.version",
          "otel.library.name",
          "otel.library.version",
          "span.kind",
          "internal.span.format",
          "thread.id",
          "thread.name");

  private TraceTree() {}

  /**
   * One span as the trace store holds it.
   *
   * @param parentId null for a root
   * @param startUs microseconds since the epoch
   */
  public record Span(
      String id,
      String parentId,
      String name,
      String service,
      long startUs,
      long durationUs,
      Map<String, Object> tags) {}

  /**
   * @param kind {@code exchange}, {@code admission}, {@code handler}, {@code verifier}, {@code
   *     embedding}, {@code model}, {@code tool}, {@code mcp}, {@code query}, {@code sql}, {@code
   *     http} or {@code other}
   * @param startUs microseconds after the trace's first span started
   * @param errorType the exception's class when the runtime recorded one
   */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Step(
      String id,
      String name,
      String kind,
      String service,
      long startUs,
      long durationUs,
      boolean error,
      String errorType,
      Map<String, Object> attributes,
      List<Step> children) {}

  /** The roots, each with its children by start; a span whose parent the trace lacks is a root. */
  public static List<Step> of(List<Span> spans) {
    long start = spans.stream().mapToLong(Span::startUs).min().orElse(0);
    Map<String, Span> byId = new HashMap<>();
    spans.forEach(s -> byId.put(s.id(), s));
    Map<String, List<Span>> children = new HashMap<>();
    List<Span> roots = new ArrayList<>();
    for (Span span : spans) {
      if (span.parentId() != null && byId.containsKey(span.parentId())) {
        children.computeIfAbsent(span.parentId(), k -> new ArrayList<>()).add(span);
      } else {
        roots.add(span);
      }
    }
    return steps(roots, children, start);
  }

  private static List<Step> steps(List<Span> spans, Map<String, List<Span>> children, long start) {
    return spans.stream()
        .sorted(Comparator.comparingLong(Span::startUs))
        .map(
            s ->
                new Step(
                    s.id(),
                    s.name(),
                    kind(s.name(), s.tags()),
                    s.service(),
                    s.startUs() - start,
                    s.durationUs(),
                    Boolean.TRUE.equals(s.tags().get("error"))
                        || "ERROR".equals(s.tags().get("otel.status_code")),
                    s.tags().get("error.type") instanceof String t ? t : null,
                    attributes(s.tags()),
                    steps(children.getOrDefault(s.id(), List.of()), children, start)))
        .toList();
  }

  /** What a span is, read from the names and attributes docs/runtime-contract.md §8 gives it. */
  static String kind(String name, Map<String, Object> tags) {
    if (tags.containsKey("aisolate.verdict")) return "verifier";
    if (tags.containsKey("aisolate.handler")) return "handler";
    if (name.startsWith("exchange ")) return "exchange";
    if (name.equals("admission")) return "admission";
    if (name.startsWith("embedding")) return "embedding";
    if (name.startsWith("chat ")) return "model";
    if (name.startsWith("tool ")) return "tool";
    if (name.startsWith("governed query ")) return "query";
    if (name.startsWith("sql ")) return "sql";
    if (name.startsWith("http ") && name.endsWith(" /mcp")) return "mcp";
    if (name.startsWith("http ")) return "http";
    return "other";
  }

  /** Sorted by name, so the page lists them alike for every span. */
  private static Map<String, Object> attributes(Map<String, Object> tags) {
    Map<String, Object> shown = new TreeMap<>();
    tags.forEach(
        (key, value) -> {
          if (!NOT_SHOWN.contains(key) && !key.startsWith("exception.")) shown.put(key, value);
        });
    return shown;
  }
}
