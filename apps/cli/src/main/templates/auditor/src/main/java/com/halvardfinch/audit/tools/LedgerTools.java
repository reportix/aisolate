package com.halvardfinch.audit.tools;

import com.reportix.aisolate.sdk.Arguments;
import com.reportix.aisolate.sdk.Column;
import com.reportix.aisolate.sdk.Entity;
import com.reportix.aisolate.sdk.Filter;
import com.reportix.aisolate.sdk.Tool;
import com.reportix.aisolate.sdk.ToolPlugin;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Halvard & Finch's audit tool plugin over one audited company's books: the general ledger, the
 * supplier invoices, the contracts, the related parties and the reported figures, all in the data
 * source {@code ledger}. Which company's books that is, the airlock decides: each company's airlock
 * defines its own {@code ledger}, so the same code never sees two companies. The rules come from
 * the project's rules/ folder; every query goes through the governed query AIsolate hands the tool.
 */
public final class LedgerTools implements ToolPlugin {

  /** Whether a counterparty is a related party is a join, so the model filters one column. */
  static final Entity JOURNAL_ENTRIES =
      new Entity(
              "ledger.journal_entries",
              "ledger.journal_entries j LEFT JOIN ledger.related_parties r ON r.name ="
                  + " j.counterparty",
              "entry_id",
              new Column("entry_id", "j.id", Column.Type.NUMBER),
              new Column("entry_date", "j.entry_date::text", Column.Type.TEXT),
              new Column("account", "j.account", Column.Type.TEXT),
              new Column("description", "j.description", Column.Type.TEXT),
              new Column("counterparty", "j.counterparty", Column.Type.TEXT),
              new Column("counterparty_iban", "j.counterparty_iban", Column.Type.TEXT),
              new Column("entry_type", "j.entry_type", Column.Type.TEXT),
              new Column("amount_eur", "j.amount_eur", Column.Type.NUMBER).withUnit("EUR"),
              new Column(
                  "related_party",
                  "CASE WHEN r.id IS NULL THEN 'no' ELSE 'yes' END",
                  Column.Type.TEXT),
              new Column("relationship", "r.relationship", Column.Type.TEXT))
          .withSubjectName("counterparty")
          .withPeriod("entry_date");

  /** An invoice matches a contract of the same supplier under the reference it quotes. */
  static final Entity INVOICES =
      new Entity(
              "ledger.invoices",
              "ledger.invoices i LEFT JOIN ledger.contracts c"
                  + " ON c.reference = i.contract_ref AND c.counterparty = i.supplier",
              "invoice_id",
              new Column("invoice_id", "i.id", Column.Type.NUMBER),
              new Column("number", "i.number", Column.Type.TEXT),
              new Column("supplier", "i.supplier", Column.Type.TEXT),
              new Column("issued_on", "i.issued_on::text", Column.Type.TEXT),
              new Column("amount_eur", "i.amount_eur", Column.Type.NUMBER).withUnit("EUR"),
              new Column("contract_ref", "i.contract_ref", Column.Type.TEXT),
              new Column(
                  "has_contract",
                  "CASE WHEN c.id IS NULL THEN 'no' ELSE 'yes' END",
                  Column.Type.TEXT),
              new Column("supplier_iban", "i.supplier_iban", Column.Type.TEXT),
              new Column("paid_on", "i.paid_on::text", Column.Type.TEXT))
          .withSubjectName("supplier")
          .withPeriod("issued_on");

  static final Entity CONTRACTS =
      new Entity(
              "ledger.contracts",
              "ledger.contracts c",
              "contract_id",
              new Column("contract_id", "c.id", Column.Type.NUMBER),
              new Column("reference", "c.reference", Column.Type.TEXT),
              new Column("counterparty", "c.counterparty", Column.Type.TEXT),
              new Column("subject", "c.subject", Column.Type.TEXT),
              new Column("start_date", "c.start_date::text", Column.Type.TEXT),
              new Column("end_date", "c.end_date::text", Column.Type.TEXT),
              new Column("annual_value_eur", "c.annual_value_eur", Column.Type.NUMBER)
                  .withUnit("EUR"))
          .withSubjectName("counterparty");

  static final Entity RELATED_PARTIES =
      new Entity(
              "ledger.related_parties",
              "ledger.related_parties r",
              "party_id",
              new Column("party_id", "r.id", Column.Type.NUMBER),
              new Column("name", "r.name", Column.Type.TEXT),
              new Column("relationship", "r.relationship", Column.Type.TEXT),
              new Column("since", "r.since::text", Column.Type.TEXT),
              new Column("iban", "r.iban", Column.Type.TEXT))
          .withSubjectName("name");

  /**
   * What the company reported, by XBRL concept of the IFRS taxonomy (ifrs-full:Revenue, ...) and
   * period: the two together key a figure, as in the table.
   */
  static final Entity REPORTED_FIGURES =
      new Entity(
              "ledger.reported_figures",
              "ledger.reported_figures f",
              "figure_id",
              new Column("figure_id", "f.concept || ' ' || f.period", Column.Type.TEXT),
              new Column("concept", "f.concept", Column.Type.TEXT),
              new Column("label", "f.label", Column.Type.TEXT),
              new Column("period", "f.period", Column.Type.TEXT),
              new Column("value_eur", "f.value_eur", Column.Type.NUMBER).withUnit("EUR"))
          .withPeriod("period");

  static final List<Entity> ENTITIES =
      List.of(JOURNAL_ENTRIES, INVOICES, CONTRACTS, RELATED_PARTIES, REPORTED_FIGURES);

  private static final List<String> NAMES = ENTITIES.stream().map(Entity::name).toList();

  /** What the model reads to pick an entity: each one with its columns. */
  private static final String CATALOG =
      ENTITIES.stream()
          .map(e -> e.name() + " (" + String.join(", ", e.columns().keySet()) + ")")
          .collect(Collectors.joining("; "));

  private static final Map<String, Object> ENTITY_SCHEMA =
      Map.of("type", "string", "enum", NAMES, "description", "The entity to query");

  /** Rows per read: enough for a sample, never the whole ledger in a prompt. */
  static final int MAX_ROWS = 50;

  /** The threshold of the related-party payment test when the question names none. */
  static final int DEFAULT_THRESHOLD_EUR = 10_000;

  @Override
  public String name() {
    return "ledger-tools";
  }

  @Override
  public List<Entity> entities() {
    return ENTITIES;
  }

  /** The id of the data source each company's airlock defines: that company's books. */
  @Override
  public String dataSource() {
    return "ledger";
  }

  @Override
  public List<Tool> tools() {
    return List.of(
        new Tool(
            "read_records",
            "Read up to "
                + MAX_ROWS
                + " records of one entity of the audited company's books, oldest first: "
                + CATALOG
                + ". Filters narrow them (a name with op ilike, an amount with gt, a date as ISO"
                + " text with gte and lt); for the latest records filter by date. An empty result"
                + " means there is nothing to see.",
            // No limit: rows come oldest first, so "the five largest" with limit 5 read the five
            // oldest.
            Arguments.schema(
                Map.of("entity", ENTITY_SCHEMA, "filters", Arguments.FILTERS_SCHEMA),
                List.of("entity")),
            (query, args) ->
                query.read(entity(args), Arguments.filters(args.get("filters")), MAX_ROWS)),
        new Tool(
            "aggregate_records",
            "Aggregate records of one of the same entities: function sum, avg, count, min or max"
                + " over a numeric column, optionally grouped by a column (account, supplier,"
                + " counterparty, entry_type).",
            Arguments.schema(
                Map.of(
                    "entity",
                    ENTITY_SCHEMA,
                    "function",
                    Arguments.FUNCTION_SCHEMA,
                    "column",
                    Map.of("type", "string", "description", "Column to aggregate"),
                    "group_by",
                    Map.of("type", "string", "description", "Column to group by"),
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
                    Arguments.filters(args.get("filters")))),
        // The two audit tests a small model gets wrong with free filters: one call each.
        new Tool(
            "related_party_payments",
            "The audit test for payments to related parties: up to "
                + MAX_ROWS
                + " payments in the general ledger, in entry order, to a related party above a"
                + " threshold in EUR, "
                + DEFAULT_THRESHOLD_EUR
                + " unless the question names another.",
            Arguments.schema(
                Map.of(
                    "min_amount_eur",
                    Map.of(
                        "type",
                        "number",
                        "description",
                        "Only payments above this amount; default " + DEFAULT_THRESHOLD_EUR)),
                List.of()),
            (query, args) ->
                query.read(
                    JOURNAL_ENTRIES.name(),
                    relatedPartyPayments(args.get("min_amount_eur")),
                    MAX_ROWS)),
        new Tool(
            "invoices_without_contract",
            "The audit test for invoices without a matching contract: up to "
                + MAX_ROWS
                + " supplier invoices, in invoice order, that quote no contract, or one that is not"
                + " a contract with that supplier.",
            Arguments.schema(Map.of(), List.of()),
            (query, args) ->
                query.read(
                    INVOICES.name(),
                    List.of(new Filter("has_contract", Filter.Op.EQ, "no")),
                    MAX_ROWS)));
  }

  /** The tool server checks the threshold is a number, as for any filter on a numeric column. */
  static List<Filter> relatedPartyPayments(Object minAmount) {
    Object threshold = minAmount == null ? DEFAULT_THRESHOLD_EUR : minAmount;
    return List.of(
        new Filter("related_party", Filter.Op.EQ, "yes"),
        new Filter("entry_type", Filter.Op.EQ, "payment"),
        new Filter("amount_eur", Filter.Op.GT, threshold));
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
