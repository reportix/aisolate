from typing import Any

from aisolate import Column, ColumnType, Entity, GovernedQuery, Result, ToolPlugin, arguments, tool

NUMBER, TEXT = ColumnType.NUMBER, ColumnType.TEXT

# Ages as of today, banded: the chief's statistics group by band, never by birth date.
AGE_GROUP = (
    "CASE WHEN age(p.birth_date) < interval '18 years' THEN '0-17'"
    " WHEN age(p.birth_date) < interval '40 years' THEN '18-39'"
    " WHEN age(p.birth_date) < interval '60 years' THEN '40-59'"
    " WHEN age(p.birth_date) < interval '80 years' THEN '60-79' ELSE '80+' END"
)


def _patient_entity(table: str, alias: str, care_teams: list[str], *own: Column, period: str) -> Entity:
    """The table named like the entity, joined to its patient: who, their id (the subject, so a
    minimum group counts patients, not readings), their national ID (the rules mask it), their age
    band; then, per department in ``care_teams``, the patient's treating clinician there,
    ``<department>_clinician``, which the doctors' row filters compare with
    ``principal.clinician_id`` and every rule hides; then the table's own columns, the first its row
    key. A care team has one clinician per patient and department, so the join never repeats a row,
    and a patient on no team stays in the chief's statistics."""
    from_ = f"{table} {alias} JOIN ehr.patients p ON p.id = {alias}.patient_id"
    columns = [
        Column("patient", "p.full_name", TEXT),
        Column("patient_id", "p.id", NUMBER),
        Column("national_id", "p.national_id", TEXT),
        Column("age_group", AGE_GROUP, TEXT),
    ]
    for department in care_teams:
        team = f"{department}_team"
        from_ += f" LEFT JOIN ehr.care_team {team} ON {team}.patient_id = p.id AND {team}.department = '{department}'"
        columns.append(Column(f"{department}_clinician", f"{team}.clinician_id", NUMBER))
    return Entity(
        table, from_, own[0].name, *columns, *own, subject="patient_id", subject_name="patient", period=period
    )


VITALS = _patient_entity(
    "cardiology.vitals",
    "v",
    ["cardiology"],
    Column("vital_id", "v.id", NUMBER),
    Column("measured_on", "v.measured_on::text", TEXT),
    Column("systolic_bp", "v.systolic_bp", NUMBER, unit="mmHg"),
    Column("diastolic_bp", "v.diastolic_bp", NUMBER, unit="mmHg"),
    Column("heart_rate", "v.heart_rate", NUMBER, unit="bpm"),
    period="measured_on",
)

ECGS = _patient_entity(
    "cardiology.ecgs",
    "e",
    ["cardiology"],
    Column("ecg_id", "e.id", NUMBER),
    Column("recorded_on", "e.recorded_on::text", TEXT),
    Column("rhythm", "e.rhythm", TEXT),
    Column("heart_rate", "e.heart_rate", NUMBER, unit="bpm"),
    Column("qtc_ms", "e.qtc_ms", NUMBER, unit="ms"),
    Column("finding", "e.finding", TEXT),
    period="recorded_on",
)

LABS = _patient_entity(
    "cardiology.labs",
    "l",
    ["cardiology"],
    Column("lab_id", "l.id", NUMBER),
    Column("taken_at", "to_char(l.taken_at, 'YYYY-MM-DD HH24:MI')", TEXT),
    Column("test", "l.test", TEXT),
    Column("value", "l.value", NUMBER, unit_column="unit"),
    Column("unit", "l.unit", TEXT),
    Column("reference_range", "l.reference_range", TEXT),
    period="taken_at",
)

SESSIONS = _patient_entity(
    "psychiatry.sessions",
    "s",
    ["psychiatry"],
    Column("session_id", "s.id", NUMBER),
    Column("session_date", "s.session_date::text", TEXT),
    Column("session_no", "s.session_no", NUMBER),
    Column("phq9_score", "s.phq9_score", NUMBER),
    Column("mood", "s.mood", TEXT),
    Column("note", "s.note", TEXT),
    period="session_date",
)

DIAGNOSES = _patient_entity(
    "psychiatry.diagnoses",
    "d",
    ["psychiatry"],
    Column("diagnosis_id", "d.id", NUMBER),
    Column("icd10", "d.icd10", TEXT, fact=True),
    Column("diagnosis", "d.diagnosis", TEXT, fact=True),
    Column("diagnosed_on", "d.diagnosed_on::text", TEXT),
    period="diagnosed_on",
)

# What a patient takes, prescribed by any department; never why: that is the prescriber's.
MEDICATIONS = _patient_entity(
    "ehr.medications",
    "m",
    ["cardiology", "psychiatry"],
    Column("medication_id", "m.id", NUMBER),
    Column("drug", "m.drug", TEXT, fact=True),
    Column("drug_class", "m.drug_class", TEXT),
    Column("dose", "m.dose", TEXT, fact=True),
    Column("frequency", "m.frequency", TEXT, fact=True),
    Column("prescribed_by", "m.prescribed_by", TEXT),
    # Always unknown: without it a small model inferred the reason from drug_class.
    Column("indication", "'unknown: the medication list records no reason'", TEXT),
    Column("started_on", "m.started_on::text", TEXT),
    Column("stopped_on", "m.stopped_on::text", TEXT),
    period="started_on",
)

# Each department's own tables, by the department's airlock id.
DEPARTMENTS = {"cardiology": [VITALS, ECGS, LABS], "psychiatry": [SESSIONS, DIAGNOSES]}

ENTITIES = (VITALS, ECGS, LABS, SESSIONS, DIAGNOSES, MEDICATIONS)

MAX_ROWS = 20

# Every read sorts by row key ascending and stops at MAX_ROWS: the model must narrow by date.
ROWS = (
    " Returns only the rows of the patients on the asking clinician's care team, and only the"
    f" columns they may see, in id order, at most {MAX_ROWS}: for the latest values filter on the"
    " date column with op gte."
)


# The care-team columns are the rules' business, hidden from every role: never offered.
def _catalog(entities: list[Entity]) -> str:
    return "; ".join(f"{e.name} ({', '.join(c for c in e.columns if not c.endswith('_clinician'))})" for e in entities)


def _read_schema(entity_schema: dict[str, Any] | None) -> dict[str, Any]:
    properties = {} if entity_schema is None else {"entity": entity_schema}
    # No limit: rows come oldest first, so "the last troponin" with limit 1 read the first one.
    properties["filters"] = arguments.FILTERS_SCHEMA
    return arguments.schema(properties, [] if entity_schema is None else ["entity"])


# The enum keeps a well-behaved model inside its department; this refuses the rest, so a
# department's tool never reads another department's table.
def _entity(args: dict[str, Any], names: list[str]) -> str:
    name = arguments.string(args.get("entity"), "entity")
    if name not in names:
        raise ValueError(f"unknown entity {name}; one of [{', '.join(names)}]")
    return name


def _department_tools(department: str) -> tuple[Any, Any]:
    """``read_<department>_records`` and ``aggregate_<department>_records``."""
    names = [e.name for e in DEPARTMENTS[department]]
    entity_schema = {"type": "string", "enum": names, "description": "The entity to query"}

    @tool(
        f"read_{department}_records",
        f"Read up to {MAX_ROWS} {department} records of one entity: "
        + _catalog(DEPARTMENTS[department])
        + ". Filter a patient by name with op ilike; dates are ISO text."
        + ROWS,
        _read_schema(entity_schema),
    )
    def read(self: Any, query: GovernedQuery, args: dict[str, Any]) -> Result:
        return query.read(_entity(args, names), arguments.filters(args.get("filters")), MAX_ROWS)

    @tool(
        f"aggregate_{department}_records",
        f"Aggregate {department} records of one of the same entities: function sum, avg or count over"
        " a numeric column, optionally grouped by a column such as age_group. Groups smaller than the"
        " allowed minimum are not returned.",
        arguments.schema(
            {
                "entity": entity_schema,
                "function": arguments.FUNCTION_SCHEMA,
                "column": {"type": "string", "description": "Column to aggregate"},
                "group_by": {"type": "string", "description": "Column to group by"},
                "filters": arguments.FILTERS_SCHEMA,
            },
            ["entity", "function", "column"],
        ),
    )
    def aggregate(self: Any, query: GovernedQuery, args: dict[str, Any]) -> Result:
        return query.aggregate(
            _entity(args, names),
            arguments.function(args.get("function")),
            arguments.string(args.get("column"), "column"),
            None if args.get("group_by") is None else arguments.string(args.get("group_by"), "group_by"),
            arguments.filters(args.get("filters")),
        )

    return read, aggregate


class EhrTools(ToolPlugin):
    """Aldermoor Hospital's tool plugin over its one health record, the data source ``ehr`` each
    department's airlock defines: the same database, opened as the department's own role, which
    reaches its department's tables and the medication list every department shares, nothing else.
    It publishes a pair of tools per department, ``read_<department>_records`` and
    ``aggregate_<department>_records``, and ``read_medications``. The code is the same in every
    department's airlock; the airlock's ``tools`` decides which of them its model may call, and
    the role decides what its queries can reach, so cardiology never reaches a therapy note. A
    doctor reads only the patients on their care team: the identity join finds the asking clinician,
    and the rules' row filters compare it with the patient's care team in the department. The rules
    come from the project's rules/ folder; every query goes through the governed query AIsolate
    hands the tool."""

    name = "ehr-tools"
    entities = ENTITIES
    # The id of the data source each department's airlock defines: the one health record.
    data_source = "ehr"
    # The asking clinician, by the token's e-mail. Every entity is about a patient (patient_id),
    # never the clinician, so no row is marked as the user's own.
    identity_join = "SELECT id FROM ehr.clinicians WHERE email = %s"
    # Rules compare with it as principal.clinician_id.
    identity_attribute = "clinician_id"

    read_cardiology_records, aggregate_cardiology_records = _department_tools("cardiology")
    read_psychiatry_records, aggregate_psychiatry_records = _department_tools("psychiatry")

    @tool(
        "read_medications",
        f"Read up to {MAX_ROWS} rows of the medication list every department shares, "
        + _catalog([MEDICATIONS])
        + ": what a patient takes and which department prescribed it, never why. Filter a"
        " patient by name with op ilike." + ROWS,
        _read_schema(None),
    )
    def read_medications(self, query: GovernedQuery, args: dict[str, Any]) -> Result:
        return query.read(MEDICATIONS.name, arguments.filters(args.get("filters")), MAX_ROWS)
