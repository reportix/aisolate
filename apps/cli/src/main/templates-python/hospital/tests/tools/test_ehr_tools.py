import re
from pathlib import Path

import pytest
from aisolate import Filter, Fn, Op
from support import RecordingQuery

from aldermoor_ehr.tools.ehr_tools import EhrTools

RULES = Path(__file__).parents[2] / "rules"
ENTITY_NAMES = [
    "cardiology.vitals",
    "cardiology.ecgs",
    "cardiology.labs",
    "psychiatry.sessions",
    "psychiatry.diagnoses",
    "ehr.medications",
]


def test_given_plugin_when_tools_then_a_pair_per_department_and_the_shared_medication_list():
    # Arrange: aisolate.yaml names read_<airlock>_records and aggregate_<airlock>_records
    plugin = EhrTools()

    # Act
    names = [t.name for t in plugin.tools()]

    # Assert
    assert names == [
        "read_cardiology_records",
        "aggregate_cardiology_records",
        "read_psychiatry_records",
        "aggregate_psychiatry_records",
        "read_medications",
    ]


@pytest.mark.parametrize(
    ("name", "entity", "choices"),
    [
        ("read_cardiology_records", "psychiatry.sessions", "[cardiology.vitals, cardiology.ecgs, cardiology.labs]"),
        (
            "aggregate_cardiology_records",
            "psychiatry.diagnoses",
            "[cardiology.vitals, cardiology.ecgs, cardiology.labs]",
        ),
        ("read_psychiatry_records", "cardiology.labs", "[psychiatry.sessions, psychiatry.diagnoses]"),
        ("aggregate_psychiatry_records", "ehr.medications", "[psychiatry.sessions, psychiatry.diagnoses]"),
    ],
)
def test_given_another_departments_entity_when_called_then_refused_without_a_query(name, entity, choices):
    # Arrange
    query = RecordingQuery()
    args = {"entity": entity, "function": "count", "column": "patient"}

    # Act
    with pytest.raises(ValueError) as thrown:
        tool(name).handler(query, args)

    # Assert
    assert str(thrown.value) == f"unknown entity {entity}; one of {choices}"
    assert query.calls == []


def test_given_group_by_when_aggregate_cardiology_records_then_grouped_by_it():
    # Arrange: the chief's question, average blood pressure by age group
    query = RecordingQuery()
    args = {"entity": "cardiology.vitals", "function": "avg", "column": "systolic_bp", "group_by": "age_group"}

    # Act
    tool("aggregate_cardiology_records").handler(query, args)

    # Assert
    assert query.calls == [("aggregate", "cardiology.vitals", Fn.AVG, "systolic_bp", "age_group", [])]


@pytest.mark.parametrize(
    ("name", "entity", "limit"),
    [
        ("read_medications", "ehr.medications", 1),
        ("read_medications", "ehr.medications", 500),
        ("read_cardiology_records", "cardiology.labs", 1),
    ],
)
def test_given_any_limit_when_read_then_every_row_up_to_20(name, entity, limit):
    # Arrange: a limit of 1 asked for "the last" value read the oldest row, so none is offered
    query = RecordingQuery()
    args = {"entity": entity, "filters": [{"column": "patient", "op": "ilike", "value": "Julian"}], "limit": limit}

    # Act
    tool(name).handler(query, args)

    # Assert
    assert query.calls == [("read", entity, [Filter("patient", Op.ILIKE, "Julian")], 20)]
    assert "limit" not in str(tool(name).input_schema)


def test_given_every_entity_when_columns_then_each_has_the_patients_name_id_national_id_and_age_band():
    # Arrange: a rule masking or hiding a column the entity lacks stops the tool server
    entities = EhrTools().entities

    # Act
    lacking = [e.name for e in entities if not {"patient", "patient_id", "national_id", "age_group"} <= set(e.columns)]

    # Assert
    assert len(entities) == 6
    assert lacking == []


def test_given_every_entity_when_subject_then_the_patient_not_the_clinician():
    # Arrange: a minimum group counts distinct subjects, and self marks only the identity's own
    plugin = EhrTools()

    # Act
    subjects = [e.column(e.subject).expression for e in plugin.entities]

    # Assert
    assert subjects == ["p.id"] * 6
    assert all(e.subject != plugin.identity_attribute for e in plugin.entities)


def test_given_plugin_when_identity_join_then_the_asking_clinician_by_email():
    # Arrange
    plugin = EhrTools()

    # Act
    join = plugin.identity_join

    # Assert
    assert join == "SELECT id FROM ehr.clinicians WHERE email = %s"
    assert plugin.identity_attribute == "clinician_id"


@pytest.mark.parametrize(
    ("name", "departments"),
    [
        ("cardiology.vitals", ["cardiology"]),
        ("cardiology.ecgs", ["cardiology"]),
        ("cardiology.labs", ["cardiology"]),
        ("psychiatry.sessions", ["psychiatry"]),
        ("psychiatry.diagnoses", ["psychiatry"]),
        ("ehr.medications", ["cardiology", "psychiatry"]),
    ],
)
def test_given_entity_when_from_then_it_joins_exactly_its_departments_care_teams(name, departments):
    # Arrange: one clinician per patient and department, so a LEFT JOIN never repeats a row
    entity = EhrTools().entity(name)

    # Act
    joined = re.findall(
        r"LEFT JOIN ehr\.care_team (\w+)_team ON \1_team\.patient_id = p\.id AND \1_team\.department = '\1'",
        entity.from_,
    )

    # Assert
    assert joined == departments
    assert [c for c in entity.columns if c.endswith("_clinician")] == [f"{d}_clinician" for d in departments]
    assert all(entity.column(f"{d}_clinician").expression == f"{d}_team.clinician_id" for d in departments)


@pytest.mark.parametrize("name", ENTITY_NAMES)
def test_given_rule_set_when_read_then_it_names_only_the_entitys_columns_and_filters_by_the_clinician(name):
    # Arrange
    json = (RULES / f"{name}.json").read_text()
    plugin = EhrTools()
    entity = plugin.entity(name)

    # Act
    filters = re.findall(r'"row_filter": "(\w+) == principal\.(\w+)"', json)
    filtered = [column for column, _ in filters]
    attributes = {attribute for _, attribute in filters}
    named = re.findall(r'"(\w+)": "(?:hide|mask)"', json)
    rules = json.split('{"name": ')[1:]

    # Assert: every doctor rule filters by a care team the entity joins; every rule hides them all;
    # the chief's also hides the patient's id
    assert filtered and all(c.endswith("_clinician") for c in filtered)
    assert attributes == {plugin.identity_attribute}
    assert set(filtered) | set(named) <= set(entity.columns)
    assert len(rules) == len(filtered) + 1
    assert all(f'"{c}": "hide"' in rule for rule in rules for c in filtered)
    assert rules[-1].startswith('"chief-statistics"')
    assert '"patient_id": "hide"' in rules[-1]


@pytest.mark.parametrize(
    ("name", "facts"),
    [("ehr.medications", ["dose", "drug", "frequency"]), ("psychiatry.diagnoses", ["diagnosis", "icd10"])],
)
def test_given_entity_when_columns_then_its_fact_columns_are_the_clinical_values_a_model_may_type(name, facts):
    # Arrange: a typed value of a fact column is shown as unsourced, so only what a reader must not take
    # from the model's memory is marked
    entity = EhrTools().entity(name)

    # Act
    marked = sorted(c.name for c in entity.columns.values() if c.fact)

    # Assert
    assert marked == facts


def tool(name):
    return next(t for t in EhrTools().tools() if t.name == name)
