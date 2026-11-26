package com.acme.hr.tools;

import com.reportix.aisolate.sdk.Arguments;
import com.reportix.aisolate.sdk.Column;
import com.reportix.aisolate.sdk.Entity;
import com.reportix.aisolate.sdk.Tool;
import com.reportix.aisolate.sdk.ToolPlugin;
import java.util.List;
import java.util.Map;

/**
 * Acme's contracts tool plugin: the terms of the employment contracts as the HR system keeps them,
 * and the two tools it publishes on AIsolate's tool server. Who reads which rows is the rules'
 * word, from the project's rules/ folder, never from here: an employee their own row, the CEO
 * everyone's; every query goes through the governed query AIsolate hands the tool.
 */
public final class ContractTools implements ToolPlugin {

  // The terms of every employment contract as the HR system keeps them, one row per employee: what
  // the signed contract in hr.documents states, transcribed at signing and kept current by its
  // amendments. The e-mail is the subject and the plugin's identity, so the asking user's row is
  // marked self; the rules hide it, as the contracts' bodies carry names and never addresses.
  public static final Entity TERMS =
      new Entity(
              "hr.contract_terms",
              "hr.contract_terms t",
              "employee",
              new Column("employee", "t.employee", Column.Type.TEXT),
              new Column("email", "t.email", Column.Type.TEXT),
              new Column("position", "t.position", Column.Type.TEXT),
              new Column("department", "t.department", Column.Type.TEXT),
              new Column("start_date", "t.start_date::text", Column.Type.TEXT),
              new Column("salary_eur", "t.salary_eur", Column.Type.NUMBER).withUnit("EUR"),
              new Column("bonus_eur", "t.bonus_eur", Column.Type.NUMBER).withUnit("EUR"),
              new Column("weekly_hours", "t.weekly_hours", Column.Type.NUMBER).withUnit("hours"),
              new Column("vacation_days", "t.vacation_days", Column.Type.NUMBER).withUnit("days"),
              new Column("probation_months", "t.probation_months", Column.Type.NUMBER)
                  .withUnit("months"),
              new Column("notice_months", "t.notice_months", Column.Type.NUMBER).withUnit("months"),
              new Column("non_compete", "t.non_compete", Column.Type.TEXT))
          .withSubject("email")
          .withSubjectName("employee");

  // The same table, the salary alone: the rules in rules/hr.salary.json govern it like the terms.
  public static final Entity SALARY =
      new Entity(
              "hr.salary",
              "hr.contract_terms t",
              "employee",
              new Column("employee", "t.employee", Column.Type.TEXT),
              new Column("email", "t.email", Column.Type.TEXT),
              new Column("salary_eur", "t.salary_eur", Column.Type.NUMBER).withUnit("EUR"))
          .withSubject("email")
          .withSubjectName("employee");

  /** What the model reads to pick a column. */
  private static final String COLUMNS = String.join(", ", TERMS.columns().keySet());

  private static final Map<String, Object> FILTERS =
      Arguments.schema(Map.of("filters", Arguments.FILTERS_SCHEMA), List.of());

  @Override
  public String name() {
    return "contract-tools";
  }

  @Override
  public List<Entity> entities() {
    return List.of(TERMS, SALARY);
  }

  /** The id of the data source that holds the hr schema, which the admin defines. */
  @Override
  public String dataSource() {
    return "hr";
  }

  /**
   * The token's e-mail, as {@code principal.email}, is the row's subject: AIsolate marks the asking
   * user's own row self, and the rules' row filter compares the column with it. No join: the claim
   * is it.
   */
  @Override
  public String identityAttribute() {
    return "email";
  }

  @Override
  public List<Tool> tools() {
    return List.of(
        new Tool(
            "read_contract_terms",
            "Read the terms of the employment contracts as the HR system keeps them, one row per"
                + " employee: "
                + COLUMNS
                + ". Dates are ISO text (YYYY-MM-DD); non_compete is yes or no. A read with no"
                + " filters returns every row the asking user may see, their own marked self: a"
                + " question about my contract passes none. Filter by employee (ilike, the name)"
                + " when the question names a person, by a term's column only when it names that"
                + " term's value. An empty result means there is nothing this user may see.",
            FILTERS,
            (query, args) ->
                // At most 50 rows: the whole company, and the assistant never pastes more into a
                // prompt.
                query.read(TERMS.name(), Arguments.filters(args.get("filters")), 50)),
        new Tool(
            "fetch_salary",
            "The gross annual salary. The asking user's own: call with no filters, their row is"
                + " marked self. A named person's: filters on employee (ilike). Columns: employee,"
                + " salary_eur.",
            FILTERS,
            (query, args) ->
                query.read(SALARY.name(), Arguments.filters(args.get("filters")), 50)));
  }
}
