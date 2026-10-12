package com.aldermoor.ehr.tools;

import com.reportix.aisolate.sdk.Arguments;
import com.reportix.aisolate.sdk.Column;
import com.reportix.aisolate.sdk.Entity;
import com.reportix.aisolate.sdk.Tool;
import com.reportix.aisolate.sdk.ToolPlugin;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Aldermoor Hospital's tool plugin over its one health record, the data source {@code ehr} each
 * department's airlock defines: the same database, opened as the department's own role, which
 * reaches its department's tables and the medication list every department shares, nothing else. It
 * publishes a pair of tools per department, {@code read_<department>_records} and {@code
 * aggregate_<department>_records}, and {@code read_medications}. The code is the same in every
 * department's airlock; the airlock's {@code tools} decides which of them its model may call, and
 * the role decides what its queries can reach, so cardiology never reaches a therapy note. A doctor
 * reads only the patients on their care team: the identity join finds the asking clinician, and the
 * rules' row filters compare it with the patient's care team in the department. The rules come from
 * the project's rules/ folder; every query goes through the governed query AIsolate hands the tool.
 */
public final class EhrTools implements ToolPlugin {

  // Ages as of today, banded: the chief's statistics group by band, never by birth date.
  static final String AGE_GROUP =
      "CASE WHEN age(p.birth_date) < interval '18 years' THEN '0-17'"
          + " WHEN age(p.birth_date) < interval '40 years' THEN '18-39'"
          + " WHEN age(p.birth_date) < interval '60 years' THEN '40-59'"
          + " WHEN age(p.birth_date) < interval '80 years' THEN '60-79' ELSE '80+' END";

  static final Entity VITALS =
      patientEntity(
              "cardiology.vitals",
              "v",
              List.of("cardiology"),
              new Column("vital_id", "v.id", Column.Type.NUMBER),
              new Column("measured_on", "v.measured_on::text", Column.Type.TEXT),
              new Column("systolic_bp", "v.systolic_bp", Column.Type.NUMBER).withUnit("mmHg"),
              new Column("diastolic_bp", "v.diastolic_bp", Column.Type.NUMBER).withUnit("mmHg"),
              new Column("heart_rate", "v.heart_rate", Column.Type.NUMBER).withUnit("bpm"))
          .withPeriod("measured_on");

  static final Entity ECGS =
      patientEntity(
              "cardiology.ecgs",
              "e",
              List.of("cardiology"),
              new Column("ecg_id", "e.id", Column.Type.NUMBER),
              new Column("recorded_on", "e.recorded_on::text", Column.Type.TEXT),
              new Column("rhythm", "e.rhythm", Column.Type.TEXT),
              new Column("heart_rate", "e.heart_rate", Column.Type.NUMBER).withUnit("bpm"),
              new Column("qtc_ms", "e.qtc_ms", Column.Type.NUMBER).withUnit("ms"),
              new Column("finding", "e.finding", Column.Type.TEXT))
          .withPeriod("recorded_on");

  static final Entity LABS =
      patientEntity(
              "cardiology.labs",
              "l",
              List.of("cardiology"),
              new Column("lab_id", "l.id", Column.Type.NUMBER),
              new Column("taken_at", "to_char(l.taken_at, 'YYYY-MM-DD HH24:MI')", Column.Type.TEXT),
              new Column("test", "l.test", Column.Type.TEXT),
              new Column("value", "l.value", Column.Type.NUMBER).withUnitFrom("unit"),
              new Column("unit", "l.unit", Column.Type.TEXT),
              new Column("reference_range", "l.reference_range", Column.Type.TEXT))
          .withPeriod("taken_at");

  static final Entity SESSIONS =
      patientEntity(
              "psychiatry.sessions",
              "s",
              List.of("psychiatry"),
              new Column("session_id", "s.id", Column.Type.NUMBER),
              new Column("session_date", "s.session_date::text", Column.Type.TEXT),
              new Column("session_no", "s.session_no", Column.Type.NUMBER),
              new Column("phq9_score", "s.phq9_score", Column.Type.NUMBER),
              new Column("mood", "s.mood", Column.Type.TEXT),
              new Column("note", "s.note", Column.Type.TEXT))
          .withPeriod("session_date");

  static final Entity DIAGNOSES =
      patientEntity(
              "psychiatry.diagnoses",
              "d",
              List.of("psychiatry"),
              new Column("diagnosis_id", "d.id", Column.Type.NUMBER),
              new Column("icd10", "d.icd10", Column.Type.TEXT).asFact(),
              new Column("diagnosis", "d.diagnosis", Column.Type.TEXT).asFact(),
              new Column("diagnosed_on", "d.diagnosed_on::text", Column.Type.TEXT))
          .withPeriod("diagnosed_on");

  /** What a patient takes, prescribed by any department; never why: that is the prescriber's. */
  static final Entity MEDICATIONS =
      patientEntity(
              "ehr.medications",
              "m",
              List.of("cardiology", "psychiatry"),
              new Column("medication_id", "m.id", Column.Type.NUMBER),
              new Column("drug", "m.drug", Column.Type.TEXT).asFact(),
              new Column("drug_class", "m.drug_class", Column.Type.TEXT),
              new Column("dose", "m.dose", Column.Type.TEXT).asFact(),
              new Column("frequency", "m.frequency", Column.Type.TEXT).asFact(),
              new Column("prescribed_by", "m.prescribed_by", Column.Type.TEXT),
              // Always unknown: without it a small model inferred the reason from drug_class.
              new Column(
                  "indication",
                  "'unknown: the medication list records no reason'",
                  Column.Type.TEXT),
              new Column("started_on", "m.started_on::text", Column.Type.TEXT),
              new Column("stopped_on", "m.stopped_on::text", Column.Type.TEXT))
          .withPeriod("started_on");

  /** Each department's own tables, by the department's airlock id. */
  static final Map<String, List<Entity>> DEPARTMENTS =
      Map.of("cardiology", List.of(VITALS, ECGS, LABS), "psychiatry", List.of(SESSIONS, DIAGNOSES));

  static final List<Entity> ENTITIES =
      List.of(VITALS, ECGS, LABS, SESSIONS, DIAGNOSES, MEDICATIONS);

  static final int MAX_ROWS = 20;

  // Every read sorts by row key ascending and stops at MAX_ROWS: the model must narrow by date.
  private static final String ROWS =
      " Returns only the rows of the patients on the asking clinician's care team, and only the"
          + " columns they may see, in id order, at most "
          + MAX_ROWS
          + ": for the latest values filter on the date column with op gte.";

  @Override
  public String name() {
    return "ehr-tools";
  }

  @Override
  public List<Entity> entities() {
    return ENTITIES;
  }

  /** The id of the data source each department's airlock defines: the one health record. */
  @Override
  public String dataSource() {
    return "ehr";
  }

  /**
   * The asking clinician, by the token's e-mail. Every entity is about a patient ({@code
   * patient_id}), never the clinician, so no row is marked as the user's own.
   */
  @Override
  public String identityJoin() {
    return "SELECT id FROM ehr.clinicians WHERE email = ?";
  }

  /** Rules compare with it as {@code principal.clinician_id}. */
  @Override
  public String identityAttribute() {
    return "clinician_id";
  }

  @Override
  public List<Tool> tools() {
    List<Tool> tools = new ArrayList<>();
    DEPARTMENTS.keySet().stream()
        .sorted()
        .forEach(department -> tools.addAll(departmentTools(department)));
    tools.add(
        new Tool(
            "read_medications",
            "Read up to "
                + MAX_ROWS
                + " rows of the medication list every department shares, "
                + catalog(List.of(MEDICATIONS))
                + ": what a patient takes and which department prescribed it, never why. Filter a"
                + " patient by name with op ilike."
                + ROWS,
            readSchema(null),
            (query, args) ->
                query.read(MEDICATIONS.name(), Arguments.filters(args.get("filters")), MAX_ROWS)));
    return tools;
  }

  /** {@code read_<department>_records} and {@code aggregate_<department>_records}. */
  private static List<Tool> departmentTools(String department) {
    List<Entity> entities = DEPARTMENTS.get(department);
    List<String> names = entities.stream().map(Entity::name).toList();
    Map<String, Object> entitySchema =
        Map.of("type", "string", "enum", names, "description", "The entity to query");
    return List.of(
        new Tool(
            "read_" + department + "_records",
            "Read up to "
                + MAX_ROWS
                + " "
                + department
                + " records of one entity: "
                + catalog(entities)
                + ". Filter a patient by name with op ilike; dates are ISO text."
                + ROWS,
            readSchema(entitySchema),
            (query, args) ->
                query.read(entity(args, names), Arguments.filters(args.get("filters")), MAX_ROWS)),
        new Tool(
            "aggregate_" + department + "_records",
            "Aggregate "
                + department
                + " records of one of the same entities: function sum, avg or count over a"
                + " numeric column, optionally grouped by a column such as age_group. Groups"
                + " smaller than the allowed minimum are not returned.",
            Arguments.schema(
                Map.of(
                    "entity",
                    entitySchema,
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
                    entity(args, names),
                    Arguments.function(args.get("function")),
                    Arguments.string(args.get("column"), "column"),
                    args.get("group_by") == null
                        ? null
                        : Arguments.string(args.get("group_by"), "group_by"),
                    Arguments.filters(args.get("filters")))));
  }

  /**
   * The table named like the entity, joined to its patient: who, their id (the subject, so a
   * minimum group counts patients, not readings), their national ID (the rules mask it), their age
   * band; then, per department in {@code careTeams}, the patient's treating clinician there, {@code
   * <department>_clinician}, which the doctors' row filters compare with {@code
   * principal.clinician_id} and every rule hides; then the table's own columns, the first its row
   * key. A care team has one clinician per patient and department, so the join never repeats a row,
   * and a patient on no team stays in the chief's statistics.
   */
  private static Entity patientEntity(
      String table, String alias, List<String> careTeams, Column... own) {
    StringBuilder from =
        new StringBuilder(table + " " + alias + " JOIN ehr.patients p ON p.id = " + alias)
            .append(".patient_id");
    List<Column> columns =
        new ArrayList<>(
            List.of(
                new Column("patient", "p.full_name", Column.Type.TEXT),
                new Column("patient_id", "p.id", Column.Type.NUMBER),
                new Column("national_id", "p.national_id", Column.Type.TEXT),
                new Column("age_group", AGE_GROUP, Column.Type.TEXT)));
    for (String department : careTeams) {
      String team = department + "_team";
      from.append(" LEFT JOIN ehr.care_team ")
          .append(team)
          .append(" ON ")
          .append(team)
          .append(".patient_id = p.id AND ")
          .append(team)
          .append(".department = '")
          .append(department)
          .append("'");
      columns.add(
          new Column(department + "_clinician", team + ".clinician_id", Column.Type.NUMBER));
    }
    columns.addAll(List.of(own));
    return new Entity(table, from.toString(), own[0].name(), columns.toArray(Column[]::new))
        .withSubject("patient_id")
        .withSubjectName("patient");
  }

  private static Map<String, Object> readSchema(Map<String, Object> entitySchema) {
    Map<String, Object> properties = new LinkedHashMap<>();
    if (entitySchema != null) {
      properties.put("entity", entitySchema);
    }
    // No limit: rows come oldest first, so "the last troponin" with limit 1 read the first one.
    properties.put("filters", Arguments.FILTERS_SCHEMA);
    return Arguments.schema(properties, entitySchema == null ? List.of() : List.of("entity"));
  }

  // The care-team columns are the rules' business, hidden from every role: never offered.
  private static String catalog(List<Entity> entities) {
    return entities.stream()
        .map(
            e ->
                e.name()
                    + " ("
                    + e.columns().keySet().stream()
                        .filter(column -> !column.endsWith("_clinician"))
                        .collect(Collectors.joining(", "))
                    + ")")
        .collect(Collectors.joining("; "));
  }

  // The enum keeps a well-behaved model inside its department; this refuses the rest, so a
  // department's tool never reads another department's table.
  private static String entity(Map<String, Object> args, List<String> names) {
    String name = Arguments.string(args.get("entity"), "entity");
    if (!names.contains(name)) {
      throw new IllegalArgumentException("unknown entity " + name + "; one of " + names);
    }
    return name;
  }
}
