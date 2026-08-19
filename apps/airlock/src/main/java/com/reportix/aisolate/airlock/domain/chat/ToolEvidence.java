package com.reportix.aisolate.airlock.domain.chat;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * What a governed tool returns. {@code rows} go to the model only; the airlock strips them before
 * anything reaches the client and assigns the {@code ev-n} id per exchange.
 *
 * @param dataSource the data source the tool's plugin reads, as the tool server names it; null from
 *     a tool that reads none or a tool server that does not say
 * @param labels what the columns mean ({@code com.reportix.aisolate.sdk.Result.Labels}); null for a
 *     result the tool built itself
 */
public record ToolEvidence(
    String entity,
    @JsonProperty("row_count") int rowCount,
    List<String> columns,
    List<Map<String, Object>> rows,
    @JsonProperty("rule_version") String ruleVersion,
    List<String> rules,
    @JsonProperty("sql_hash") String sqlHash,
    @JsonProperty("data_source") String dataSource,
    Labels labels) {

  public ToolEvidence {
    columns = columns == null ? List.of() : columns;
    rows = rows == null ? List.of() : rows;
  }

  public record Labels(
      @JsonProperty("row_key") String rowKey,
      String subject,
      String period,
      Map<String, String> units,
      @JsonProperty("unit_columns") Map<String, String> unitColumns,
      Map<String, Integer> decimals,
      List<String> masked,
      String function,
      List<String> facts) {

    static final Labels NONE =
        new Labels(null, null, null, Map.of(), Map.of(), Map.of(), List.of(), null, List.of());

    public Labels {
      units = units == null ? Map.of() : units;
      unitColumns = unitColumns == null ? Map.of() : unitColumns;
      decimals = decimals == null ? Map.of() : decimals;
      masked = masked == null ? List.of() : masked;
      facts = facts == null ? List.of() : facts;
    }
  }
}
