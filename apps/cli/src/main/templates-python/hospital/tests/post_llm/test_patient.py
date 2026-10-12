import json

import pytest
from aisolate import Answer, Evidence, Status
from support import FakeAIsolateManager

from aldermoor_ehr.post_llm.patient import PatientVerifier

JULIAN, AMIRA = "Julian Marek", "Amira Haddad"


def tool_result(entity, rows, **labels):
    """A tool result as the tool server writes it; ``labels`` are the ones the entity declares."""
    unset = {"row_key": None, "subject": None, "period": None, "units": {}, "unit_columns": {}, "decimals": {}}
    return json.dumps(
        {
            "entity": entity,
            "row_count": len(rows),
            "columns": list(rows[0]),
            "rows": rows,
            "labels": {**unset, "masked": [], "function": None, **labels},
        }
    )


def vital(vital_id, patient, patient_id, age_group, measured_on, systolic, diastolic, heart_rate):
    return {
        "patient": patient,
        "patient_id": patient_id,
        "national_id": "****",
        "age_group": age_group,
        "vital_id": vital_id,
        "measured_on": measured_on,
        "systolic_bp": systolic,
        "diastolic_bp": diastolic,
        "heart_rate": heart_rate,
    }


# A passages evidence is a JSON list, not a tool result.
PASSAGES = Evidence(
    "ev-1",
    "clinical-notes",
    json.dumps([{"title": f"Cardiology note {JULIAN}", "section": "2026-10-03", "body": "Troponin trending down."}]),
)
VITALS = Evidence(
    "ev-4",
    "read_cardiology_records",
    tool_result(
        "cardiology.vitals",
        [
            vital(901, JULIAN, 17, "60-79", "2026-10-02", 142, 88, 76),
            vital(902, AMIRA, 23, "40-59", "2026-10-02", 128, 82, 64),
            vital(903, JULIAN, 17, "60-79", "2026-10-03", 135, 84, 72),
        ],
        row_key="vital_id",
        subject="patient",
        period="measured_on",
        units={"systolic_bp": "mmHg", "diastolic_bp": "mmHg", "heart_rate": "bpm"},
        masked=["national_id"],
    ),
)
# The average systolic pressure per age group: rows about a group, never a patient.
AVERAGES = Evidence(
    "ev-5",
    "aggregate_cardiology_records",
    tool_result(
        "cardiology.vitals",
        [
            {"age_group": "18-39", "value": 119.25, "n": 8},
            {"age_group": "40-59", "value": 137.33, "n": 9},
            {"age_group": "60-79", "value": 139.11, "n": 9},
        ],
        row_key="age_group",
        units={"value": "mmHg"},
        function="avg",
    ),
)
# A tool the project wrote itself builds its result without labels.
OWN_TOOL = Evidence(
    "ev-6",
    "find_notes",
    json.dumps({"entity": "notes", "row_count": 1, "columns": ["note"], "rows": [{"note": "Call"}], "labels": None}),
)
EVIDENCE = [PASSAGES, VITALS, AVERAGES, OWN_TOOL]


def source(evidence, entity, column, row_key, subject, period, unit=None):
    """Where a cited value is from, as the airlock writes it: what is null is left out."""
    fields = {
        "evidence": evidence,
        "entity": entity,
        "column": column,
        "row_key": row_key,
        "subject": subject,
        "period": period,
        "unit": unit,
    }
    return {key: value for key, value in fields.items() if value is not None}


def systolic(vital_id, subject, measured_on):
    return source("ev-4", "cardiology.vitals", "systolic_bp", vital_id, subject, measured_on, "mmHg")


JULIAN_OCT_2 = systolic("901", JULIAN, "2026-10-02")
AMIRA_OCT_2 = systolic("902", AMIRA, "2026-10-02")
AVERAGE_AGED_60_79 = source("ev-5", "cardiology.vitals", "value", "60-79", None, None, "mmHg")


def operand(display, cited):
    """One cell of a computation, shown as itself."""
    return {**cited, "display": display}


def cell(display, cited, status="sourced"):
    return {"display": display, "status": status, "source": cited}


def grounded(figures=(), tables=()):
    """The members of a grounded answer that a verifier reads."""
    return json.dumps({"figures": list(figures), "tables": list(tables)})


def grounding(display, cited, status="sourced"):
    return grounded([{"id": "f1", "display": display, "status": status, "source": cited}])


def grounding_computed(display, **computed):
    return grounded([{"id": "f1", "display": display, "status": "computed", "computed": computed}])


def grounding_table(rows):
    return grounded(tables=[{"id": "t1", "status": "sourced", "rows": rows}])


JULIANS_PRESSURE = "What was Julian's blood pressure on 2026-10-02?"


def test_given_julians_reading_for_julian_when_verified_then_pass():
    # Arrange
    answer = Answer(
        "Julian's systolic pressure was 142 mmHg.", (), JULIANS_PRESSURE, grounding("142 mmHg", JULIAN_OCT_2)
    )

    # Act
    verdict = PatientVerifier().verify(answer, EVIDENCE, FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (Status.PASS, "every figure is about Julian Marek")


def test_given_a_question_naming_no_patient_when_verified_then_pass():
    # Arrange
    answer = Answer(
        "Average systolic pressure, 60-79: 139.11 mmHg.",
        (),
        "Average systolic by age group?",
        grounding("139.11 mmHg", AVERAGE_AGED_60_79),
    )

    # Act
    verdict = PatientVerifier().verify(answer, EVIDENCE, FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (Status.PASS, "the question names no patient")


def test_given_no_grounding_when_verified_then_pass():
    # Arrange
    answer = Answer("I don't know", (), JULIANS_PRESSURE, "")

    # Act
    verdict = PatientVerifier().verify(answer, [], FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (Status.PASS, "no figure to check")


def test_given_a_comparison_of_several_named_patients_when_verified_then_pass_naming_all_sorted():
    # Arrange: six names, so that a note in the order a set happens to iterate in is almost never the sorted one
    others = ["Zoe Quill", "Mira Stone", "Omar Reed", "Lena Frost"]
    rows = [vital(910 + n, name, 30 + n, "40-59", "2026-10-02", 120 + n, 80, 64) for n, name in enumerate(others)]
    more = Evidence(
        "ev-7",
        "read_cardiology_records",
        tool_result(
            "cardiology.vitals",
            rows,
            row_key="vital_id",
            subject="patient",
            period="measured_on",
            units={"systolic_bp": "mmHg"},
            masked=["national_id"],
        ),
    )
    cells = [("142 mmHg", JULIAN_OCT_2), ("128 mmHg", AMIRA_OCT_2)]
    cells += [
        (
            f"{120 + n} mmHg",
            source("ev-7", "cardiology.vitals", "systolic_bp", str(910 + n), name, "2026-10-02", "mmHg"),
        )
        for n, name in enumerate(others)
    ]
    figures = [
        {"id": f"f{n}", "display": display, "status": "sourced", "source": cited}
        for n, (display, cited) in enumerate(cells, 1)
    ]
    question = "Compare the blood pressure on 2026-10-02 of Julian, Amira, Zoe, Mira, Omar and Lena."
    answer = Answer("...", (), question, grounded(figures))

    # Act
    verdict = PatientVerifier().verify(answer, [*EVIDENCE, more], FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (
        Status.PASS,
        "every figure is about Amira Haddad, Julian Marek, Lena Frost, Mira Stone, Omar Reed, Zoe Quill",
    )


def test_given_a_group_figure_beside_the_named_patients_when_verified_then_it_is_not_judged():
    # Arrange: an average is about no patient
    figures = [
        {"id": "f1", "display": "142 mmHg", "status": "sourced", "source": JULIAN_OCT_2},
        {"id": "f2", "display": "139.11 mmHg", "status": "sourced", "source": AVERAGE_AGED_60_79},
    ]
    question = "How does Julian's blood pressure on 2026-10-02 compare with his age group's average?"
    answer = Answer("Julian's was 142 mmHg, the average 139.11 mmHg.", (), question, grounded(figures))

    # Act
    verdict = PatientVerifier().verify(answer, EVIDENCE, FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (Status.PASS, "every figure is about Julian Marek")


@pytest.mark.parametrize(
    "question, grounding_json",
    [
        pytest.param(JULIANS_PRESSURE, grounding("128 mmHg", AMIRA_OCT_2), id="figure"),
        pytest.param(
            "By how much did Julian's systolic pressure change?",
            grounding_computed(
                "14 mmHg",
                op="subtract",
                of=[operand("142 mmHg", JULIAN_OCT_2), operand("128 mmHg", AMIRA_OCT_2)],
            ),
            id="operand-shown-as-itself",
        ),
        pytest.param(
            "What are Julian's two highest systolic pressures?",
            grounding_computed(
                "Julian Marek, Amira Haddad",
                op="top_n",
                n=2,
                by={"evidence": "ev-4", "entity": "cardiology.vitals", "column": "systolic_bp"},
                rows=[operand("142 mmHg", JULIAN_OCT_2), operand("128 mmHg", AMIRA_OCT_2)],
            ),
            id="ranked-row-shown-as-itself",
        ),
        pytest.param(
            "What was Julian's highest systolic pressure?",
            grounding_computed(
                "128 mmHg",
                op="max",
                by={"evidence": "ev-4", "entity": "cardiology.vitals", "column": "systolic_bp"},
                row=AMIRA_OCT_2,
            ),
            id="row-shown-as-the-figure",
        ),
        pytest.param(
            "What were Julian's systolic pressures?",
            grounding_table([[cell("142 mmHg", JULIAN_OCT_2)], [cell("128 mmHg", AMIRA_OCT_2)]]),
            id="table-cell",
        ),
    ],
)
def test_given_amiras_figure_or_cell_when_verified_then_fail_with_its_display(question, grounding_json):
    # Arrange
    answer = Answer("...", (), question, grounding_json)

    # Act
    verdict = PatientVerifier().verify(answer, EVIDENCE, FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (
        Status.FAIL,
        "the figure 128 mmHg is about Amira Haddad; the question asks about Julian Marek",
    )


@pytest.mark.parametrize(
    "question, grounding_json",
    [
        pytest.param("What did the cardiology note say about Julian?", grounded(), id="quote-only-answer"),
        pytest.param(JULIANS_PRESSURE, grounded(), id="dont-know"),
        pytest.param(
            "How does Julian's age group fare on average?",
            grounding("139.11 mmHg", AVERAGE_AGED_60_79),
            id="source-without-subject",
        ),
    ],
)
def test_given_no_cited_source_with_a_subject_when_verified_then_pass_with_no_figure_to_check(question, grounding_json):
    # Arrange
    answer = Answer("...", (), question, grounding_json)

    # Act
    verdict = PatientVerifier().verify(answer, EVIDENCE, FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (Status.PASS, "no figure to check")


def test_given_a_figure_grounding_flagged_from_amiras_row_when_verified_then_fail():
    # Arrange
    flagged = grounding("128 mmHg", AMIRA_OCT_2, "mismatch")
    answer = Answer("Julian's systolic pressure was 128 mmHg.", (), JULIANS_PRESSURE, flagged)

    # Act
    verdict = PatientVerifier().verify(answer, EVIDENCE, FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (
        Status.FAIL,
        "the figure 128 mmHg is about Amira Haddad; the question asks about Julian Marek",
    )


@pytest.mark.parametrize("evidence_id", ["ev-1", "ev-9"])
def test_given_a_source_that_is_no_tool_result_when_verified_then_pass_with_no_figure_to_check(evidence_id):
    # Arrange: ev-1 holds passages and ev-9 is not in the exchange: neither is a record of it
    cited = {**AMIRA_OCT_2, "evidence": evidence_id}
    answer = Answer("Julian's systolic pressure was 128 mmHg.", (), JULIANS_PRESSURE, grounding("128 mmHg", cited))

    # Act
    verdict = PatientVerifier().verify(answer, EVIDENCE, FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (Status.PASS, "no figure to check")


@pytest.mark.parametrize("without", [{}, {"rows": None}], ids=["absent", "null"])
def test_given_a_tool_result_without_rows_when_verified_then_it_names_no_patient(without):
    # Arrange
    result = {"entity": "cardiology.ecgs", "row_count": 0, "columns": [], "labels": {"subject": "patient"}, **without}
    evidence = [Evidence("ev-7", "read_cardiology_records", json.dumps(result)), VITALS]
    answer = Answer(
        "Julian's systolic pressure was 142 mmHg.", (), JULIANS_PRESSURE, grounding("142 mmHg", JULIAN_OCT_2)
    )

    # Act
    verdict = PatientVerifier().verify(answer, evidence, FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (Status.PASS, "every figure is about Julian Marek")


@pytest.mark.parametrize(
    "patient, question",
    [
        pytest.param("Ann Berg", "What was Joanne's last troponin?", id="word-that-only-contains-the-name"),
        pytest.param("Jo Marsh", "What was Jo's blood pressure?", id="leading-word-of-under-three-letters"),
    ],
)
def test_given_question_that_only_resembles_a_patients_name_when_verified_then_it_names_none(patient, question):
    # Arrange
    rows = [{"patient": patient, "vital_id": 7}]
    evidence = [
        *EVIDENCE,
        Evidence("ev-7", "read_cardiology_records", tool_result("cardiology.vitals", rows, subject="patient")),
    ]
    answer = Answer("...", (), question, grounding("142 mmHg", JULIAN_OCT_2))

    # Act
    verdict = PatientVerifier().verify(answer, evidence, FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (Status.PASS, "the question names no patient")
