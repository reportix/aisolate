package com.reportix.aisolate.airlock.domain.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * The answer as the user gets it: the text with every figure written in by AIsolate, the same text
 * as segments, and each figure, table and quote with where it comes from. Every value in it is one
 * the asking user may read: a cell of a tool result, or computed from such cells. A masked or
 * hidden value never reaches the airlock, so it cannot be in here.
 *
 * @param answer the segments as one text, each figure and table written in
 */
public record GroundedAnswer(
    String answer,
    List<Segment> segments,
    List<Figure> figures,
    List<Table> tables,
    List<Quote> quotes,
    Provenance provenance) {

  /** An answer with no reference in it: "I don't know", a greeting, prose without a number. */
  static GroundedAnswer plain(String text) {
    return new GroundedAnswer(
        text,
        List.of(new Segment(text, null, null)),
        List.of(),
        List.of(),
        List.of(),
        Provenance.of(List.of(), List.of()));
  }

  /** One piece of the answer: text, a figure ({@code f1}, {@code u1}) or a table ({@code t1}). */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Segment(String text, String figure, String table) {}

  /**
   * @param status {@code sourced}, {@code computed}, {@code unsourced} or {@code mismatch}
   * @param value the exact value, plain; null for a ranking, an unsourced or a mismatched figure
   * @param reason why it is a mismatch, or what an unsourced one matches
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Figure(
      String id,
      String display,
      String value,
      String status,
      Source source,
      Computed computed,
      String reason) {}

  /**
   * One cell and its labels. {@code value} and {@code display} are set where the cell is one of
   * several (an operand, a ranked row), not where it is the figure itself.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Source(
      String evidence,
      String entity,
      String column,
      @JsonProperty("row_key") String rowKey,
      String subject,
      String period,
      String unit,
      String value,
      String display) {}

  /**
   * @param of the operands of an arithmetic operation, in order
   * @param by the column a ranking, a max or a min ranks the rows of one evidence by
   * @param rows the ranked rows, in rank order
   * @param row the row holding a max or a min
   * @param call the tool call whose result is ranked: its arguments say the query's scope
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Computed(
      String op, List<Source> of, Integer n, By by, List<Source> rows, Source row, Call call) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record By(String evidence, String entity, String column) {}

  /**
   * The tool call an evidence came from: its arguments the tool declares and its query applied
   * ({@code ChatExchange#applied}); the {@code tool_call} event and the audit line keep them all.
   */
  public record Call(String tool, Map<String, Object> args) {}

  /**
   * Rows of one evidence as a markdown table, each cell sourced.
   *
   * @param status {@code sourced} or {@code mismatch}
   * @param display the table as markdown, as {@code answer} holds it
   * @param rank the ranking the rows claim to be, checked against the evidence; null when none
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Table(
      String id,
      String status,
      String display,
      String evidence,
      String entity,
      Call call,
      List<String> columns,
      List<List<Cell>> rows,
      Rank rank,
      String reason) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Cell(String display, String value, String status, Source source) {}

  public record Rank(String op, String by) {}

  /**
   * @param status {@code quoted} (the exact words, whitespace aside, of the passage) or {@code
   *     mismatch}
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Quote(
      String id,
      String status,
      String evidence,
      Integer index,
      String title,
      String section,
      String text,
      String reason) {}

  /**
   * The answer's figures and tables by status; a table counts once.
   *
   * @param status {@code has_mismatch}, else {@code has_unsourced}, else {@code all_sourced};
   *     {@code no_figures} when there is none
   */
  public record Provenance(String status, int sourced, int computed, int unsourced, int mismatch) {

    static Provenance of(List<Figure> figures, List<Table> tables) {
      int[] counts = new int[4];
      figures.forEach(f -> counts[index(f.status())]++);
      tables.forEach(t -> counts[index(t.status())]++);
      String status =
          counts[3] > 0
              ? "has_mismatch"
              : counts[2] > 0
                  ? "has_unsourced"
                  : counts[0] + counts[1] > 0 ? "all_sourced" : "no_figures";
      return new Provenance(status, counts[0], counts[1], counts[2], counts[3]);
    }

    private static int index(String status) {
      return switch (status) {
        case Grounding.SOURCED -> 0;
        case Grounding.COMPUTED -> 1;
        case Grounding.UNSOURCED -> 2;
        default -> 3;
      };
    }
  }
}
