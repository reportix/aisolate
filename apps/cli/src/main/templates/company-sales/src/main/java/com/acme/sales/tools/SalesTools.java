package com.acme.sales.tools;

import com.reportix.aisolate.sdk.Arguments;
import com.reportix.aisolate.sdk.Column;
import com.reportix.aisolate.sdk.Entity;
import com.reportix.aisolate.sdk.Tool;
import com.reportix.aisolate.sdk.ToolPlugin;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Acme's sales tool plugin: the client accounts and their invoices, and the two tools it publishes
 * on AIsolate's tool server, each taking the entity it reads. No identity join: the founders read
 * every row, so no row is anyone's own. The rules come from the project's rules/ folder, never from
 * here; every query goes through the governed query AIsolate hands the tool.
 */
public final class SalesTools implements ToolPlugin {

  // Dates are text in ISO form: the model filters them with gte and lt as it would numbers.
  static final Entity ACCOUNTS =
      new Entity(
              "clients.accounts",
              "clients.accounts a",
              "account_id",
              new Column("account_id", "a.id", Column.Type.NUMBER),
              new Column("name", "a.name", Column.Type.TEXT),
              new Column("industry", "a.industry", Column.Type.TEXT),
              new Column("country", "a.country", Column.Type.TEXT),
              new Column("product", "a.product", Column.Type.TEXT),
              new Column("account_owner", "a.account_owner", Column.Type.TEXT),
              new Column("contract_value_eur", "a.contract_value_eur", Column.Type.NUMBER)
                  .withUnit("EUR"),
              new Column("contract_start", "a.contract_start::text", Column.Type.TEXT),
              new Column("renewal_date", "a.renewal_date::text", Column.Type.TEXT),
              new Column("status", "a.status", Column.Type.TEXT),
              new Column("notes", "a.notes", Column.Type.TEXT))
          .withSubjectName("name");

  // The invoice's status is derived from its dates, so the model filters on paid, overdue or open.
  static final Entity INVOICES =
      new Entity(
              "clients.invoices",
              "clients.invoices i JOIN clients.accounts a ON a.id = i.account_id",
              "invoice_id",
              new Column("invoice_id", "i.id", Column.Type.NUMBER),
              new Column("client", "a.name", Column.Type.TEXT),
              new Column("number", "i.number", Column.Type.TEXT),
              new Column("amount_eur", "i.amount_eur", Column.Type.NUMBER).withUnit("EUR"),
              new Column("issued_on", "i.issued_on::text", Column.Type.TEXT),
              new Column("due_on", "i.due_on::text", Column.Type.TEXT),
              new Column("paid_on", "i.paid_on::text", Column.Type.TEXT),
              new Column(
                  "status",
                  "CASE WHEN i.paid_on IS NOT NULL THEN 'paid' WHEN i.due_on < current_date THEN"
                      + " 'overdue' ELSE 'open' END",
                  Column.Type.TEXT))
          .withSubjectName("client")
          .withPeriod("issued_on");

  static final List<Entity> ENTITIES = List.of(ACCOUNTS, INVOICES);

  private static final List<String> NAMES = ENTITIES.stream().map(Entity::name).toList();

  /** What the model reads to pick an entity: each one with its columns. */
  private static final String CATALOG =
      ENTITIES.stream()
          .map(e -> e.name() + " (" + String.join(", ", e.columns().keySet()) + ")")
          .collect(Collectors.joining("; "));

  private static final Map<String, Object> ENTITY_SCHEMA =
      Map.of("type", "string", "enum", NAMES, "description", "The entity to query");

  @Override
  public String name() {
    return "sales-tools";
  }

  @Override
  public List<Entity> entities() {
    return ENTITIES;
  }

  /** The id of the data source that holds the clients schema, which the admin defines. */
  @Override
  public String dataSource() {
    return "clients";
  }

  @Override
  public List<Tool> tools() {
    return List.of(
        new Tool(
            "read_records",
            "Read up to 20 records of one entity: "
                + CATALOG
                + ". Dates are ISO text (YYYY-MM-DD). Pass the entity and, only when the question"
                + " names a value, filters on it; a read with no filters returns every row the"
                + " asking user may see. An empty result means there is nothing this user may see.",
            Arguments.schema(
                Map.of(
                    "entity",
                    ENTITY_SCHEMA,
                    "filters",
                    Arguments.FILTERS_SCHEMA,
                    "limit",
                    Map.of("type", "integer", "description", "Maximum rows, at most 20")),
                List.of("entity")),
            (query, args) ->
                query.read(
                    entity(args),
                    Arguments.filters(args.get("filters")),
                    // At most 20 rows: the assistant never pastes every client into a prompt.
                    Math.min(Arguments.limit(args.get("limit"), 20), 20))),
        new Tool(
            "aggregate_records",
            "Aggregate records of one entity: "
                + CATALOG
                + ". Function sum, avg, count, min or max over a numeric column, optionally"
                + " grouped by a column.",
            Arguments.schema(
                Map.of(
                    "entity",
                    ENTITY_SCHEMA,
                    "function",
                    Arguments.FUNCTION_SCHEMA,
                    "column",
                    Map.of("type", "string", "description", "Column to aggregate"),
                    "group_by",
                    Map.of(
                        "type",
                        "string",
                        "description",
                        "Column to group by: one row per value, for example department or"
                            + " industry when asked per department or per industry"),
                    "filters",
                    Arguments.FILTERS_SCHEMA),
                List.of("entity", "function", "column")),
            (query, args) ->
                query.aggregate(
                    entity(args),
                    Arguments.function(args.get("function")),
                    Arguments.string(args.get("column"), "column"),
                    args.get("group_by") == null
                        ? null
                        : Arguments.string(args.get("group_by"), "group_by"),
                    Arguments.filters(args.get("filters")))));
  }

  // The governed query would answer an entity of another plugin with zero rows; a message names
  // the choice the model has.
  private static String entity(Map<String, Object> args) {
    String name = Arguments.string(args.get("entity"), "entity");
    if (!NAMES.contains(name)) {
      throw new IllegalArgumentException("unknown entity " + name + "; one of " + NAMES);
    }
    return name;
  }
}
