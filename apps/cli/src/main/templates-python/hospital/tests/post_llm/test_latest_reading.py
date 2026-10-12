import json

import pytest
from aisolate import Answer, Evidence, Status
from support import FakeAIsolateManager

from aldermoor_ehr.post_llm.latest_reading import LatestReadingVerifier

JULIAN = {"patient": "Julian Marek", "patient_id": 17, "national_id": "****", "age_group": "60-79"}
AMIRA = {"patient": "Amira Haddad", "patient_id": 23, "national_id": "****", "age_group": "40-59"}


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


def lab(lab_id, taken_at, test, value, unit, reference_range):
    return {
        **JULIAN,
        "lab_id": lab_id,
        "taken_at": taken_at,
        "test": test,
        "value": value,
        "unit": unit,
        "reference_range": reference_range,
    }


def labs(*rows, **labels):
    """cardiology.labs as read_cardiology_records returns it for Julian."""
    declared = {"row_key": "lab_id", "subject": "patient", "period": "taken_at", "masked": ["national_id"]}
    result = tool_result("cardiology.labs", list(rows), unit_columns={"value": "unit"}, **{**declared, **labels})
    return Evidence("ev-2", "read_cardiology_records", result)


def vital(vital_id, patient, measured_on, systolic, diastolic, heart_rate):
    return {
        **patient,
        "vital_id": vital_id,
        "measured_on": measured_on,
        "systolic_bp": systolic,
        "diastolic_bp": diastolic,
        "heart_rate": heart_rate,
    }


def vitals(*rows):
    """cardiology.vitals as read_cardiology_records returns it."""
    result = tool_result(
        "cardiology.vitals",
        list(rows),
        row_key="vital_id",
        subject="patient",
        period="measured_on",
        units={"systolic_bp": "mmHg", "diastolic_bp": "mmHg", "heart_rate": "bpm"},
        masked=["national_id"],
    )
    return Evidence("ev-4", "read_cardiology_records", result)


def session(session_id, session_date, phq9_score, mood):
    return {
        **JULIAN,
        "session_id": session_id,
        "session_date": session_date,
        "session_no": session_id,
        "phq9_score": phq9_score,
        "mood": mood,
    }


def sessions(*rows):
    """psychiatry.sessions as read_psychiatry_records returns it for Julian."""
    result = tool_result(
        "psychiatry.sessions",
        list(rows),
        row_key="session_id",
        subject="patient",
        period="session_date",
        masked=["national_id"],
    )
    return Evidence("ev-2", "read_psychiatry_records", result)


def medication(medication_id, drug, dose, started_on):
    return {**JULIAN, "medication_id": medication_id, "drug": drug, "dose": dose, "started_on": started_on}


def two_rows(entity, row_key, period, earlier, later):
    """A result of ``entity`` with two rows of Julian's, the first at ``earlier``, the second at ``later``."""
    rows = [{**JULIAN, row_key: 1, period: earlier}, {**JULIAN, row_key: 2, period: later}]
    result = tool_result(entity, rows, row_key=row_key, subject="patient", period=period, masked=["national_id"])
    return Evidence("ev-2", "read_records", result)


EARLIER_TROPONIN_ROW = lab(301, "2026-10-01 06:10", "Troponin T", 52, "ng/L", "<14")
LATEST_TROPONIN_ROW = lab(305, "2026-10-03 07:40", "Troponin T", 38, "ng/L", "<14")
POTASSIUM_ROW = lab(306, "2026-10-03 07:40", "Potassium", 4.1, "mmol/L", "3.5-5.1")
LABS = labs(EARLIER_TROPONIN_ROW, LATEST_TROPONIN_ROW, POTASSIUM_ROW)
SESSION_ROWS = [
    session(1, "2026-08-27", 17, "low, withdrawn"),
    session(2, "2026-09-17", 13, "low but more engaged"),
    session(3, "2026-10-08", 9, "brighter, hopeful"),
]
# What Julian takes: sertraline since June, the blood thinners since the end of September.
MEDICATIONS = Evidence(
    "ev-3",
    "read_medications",
    tool_result(
        "ehr.medications",
        [
            medication(41, "Apixaban", "5 mg", "2026-09-30"),
            medication(42, "Aspirin", "100 mg", "2026-09-30"),
            medication(43, "Sertraline", "50 mg", "2026-06-12"),
        ],
        row_key="medication_id",
        subject="patient",
        period="started_on",
        masked=["national_id"],
    ),
)
VITALS = vitals(
    vital(901, JULIAN, "2026-10-02", 142, 88, 76),
    vital(902, AMIRA, "2026-10-02", 128, 82, 64),
    vital(903, JULIAN, "2026-10-03", 135, 84, 72),
)
# The average systolic pressure per age group: a group value, no patient and no period.
AVERAGES = Evidence(
    "ev-5",
    "aggregate_cardiology_records",
    tool_result(
        "cardiology.vitals",
        [{"age_group": "40-59", "value": 137.33, "n": 9}, {"age_group": "60-79", "value": 139.11, "n": 9}],
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
# The average per day of the vitals: a group value, though the result still labels a period.
AVERAGE_BY_DAY = Evidence(
    "ev-5",
    "aggregate_cardiology_records",
    tool_result(
        "cardiology.vitals",
        [{"measured_on": "2026-10-02", "value": 135, "n": 2}, {"measured_on": "2026-10-03", "value": 135, "n": 1}],
        row_key="measured_on",
        period="measured_on",
        units={"value": "mmHg"},
        function="avg",
    ),
)
# A passages evidence is a JSON list, not a tool result.
PASSAGES = Evidence(
    "ev-1",
    "clinical-notes",
    json.dumps([{"title": "Cardiology note Julian Marek", "section": "2026-10-03", "body": "Troponin trending down."}]),
)


def source(evidence, entity, column, row_key, subject, period, unit=None):
    """Where a cited cell is from, as the airlock writes it: what is null is left out."""
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


def troponin(lab_id, taken_at):
    return source("ev-2", "cardiology.labs", "value", lab_id, "Julian Marek", taken_at, "ng/L")


def systolic(vital_id, subject, measured_on):
    return source("ev-4", "cardiology.vitals", "systolic_bp", vital_id, subject, measured_on, "mmHg")


def phq9(session_id, session_date):
    return source("ev-2", "psychiatry.sessions", "phq9_score", str(session_id), "Julian Marek", session_date)


def mood(session_id, session_date):
    return source("ev-2", "psychiatry.sessions", "mood", str(session_id), "Julian Marek", session_date)


EARLIER_TROPONIN = troponin("301", "2026-10-01 06:10")
LATEST_TROPONIN = troponin("305", "2026-10-03 07:40")
POTASSIUM = source("ev-2", "cardiology.labs", "value", "306", "Julian Marek", "2026-10-03 07:40", "mmol/L")
OLDER_POTASSIUM = source("ev-2", "cardiology.labs", "value", "310", "Julian Marek", "2026-10-02 08:00", "mmol/L")


def operand(display, cited):
    """One cell of a computation, shown as itself."""
    return {**cited, "display": display}


def grounded(figures=(), tables=()):
    """The members of a grounded answer that a verifier reads."""
    return json.dumps({"figures": list(figures), "tables": list(tables)})


def grounding(display, cited, status="sourced", **parts):
    return grounded([{"id": "f1", "display": display, "status": status, "source": cited, **parts}])


def grounding_cells(*cells):
    """Cell figures f1, f2, ... with the displays and sources given."""
    figures = [
        {"id": f"f{n}", "display": display, "status": "sourced", "source": cited}
        for n, (display, cited) in enumerate(cells, 1)
    ]
    return grounded(figures)


def grounding_computed(display, **computed):
    return grounded([{"id": "f1", "display": display, "status": "computed", "computed": computed}])


LAST = "What was Julian's last troponin?"


def test_given_an_earlier_troponin_cited_for_the_last_when_verified_then_fail():
    # Arrange
    answer = Answer("Julian's last troponin T was 52 ng/L.", (), LAST, grounding("52 ng/L", EARLIER_TROPONIN))

    # Act
    verdict = LatestReadingVerifier().verify(answer, [LABS], FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (
        Status.FAIL,
        "the answer cites the 2026-10-01 06:10 Troponin T; the latest in the records is 2026-10-03 07:40",
    )


def test_given_the_latest_troponin_cited_for_the_last_when_verified_then_pass():
    # Arrange
    answer = Answer("Julian's last troponin T was 38 ng/L.", (), LAST, grounding("38 ng/L", LATEST_TROPONIN))

    # Act
    verdict = LatestReadingVerifier().verify(answer, [LABS], FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (Status.PASS, "the latest reading is cited")


@pytest.mark.parametrize(
    "question",
    [
        "What were Julian's last 3 troponins?",
        "What is Julian's latest troponin?",
        "What was Julian's most recent troponin?",
        "What was Julian's most  recent troponin?",
        "What is Julian's current troponin?",
        "WHAT WAS JULIAN'S LAST TROPONIN?",
    ],
)
def test_given_a_question_for_the_latest_when_the_earlier_reading_is_cited_then_fail(question):
    # Arrange
    answer = Answer("Julian's troponin T was 52 ng/L.", (), question, grounding("52 ng/L", EARLIER_TROPONIN))

    # Act
    verdict = LatestReadingVerifier().verify(answer, [LABS], FakeAIsolateManager())

    # Assert
    assert verdict.status is Status.FAIL


@pytest.mark.parametrize(
    "question",
    [
        "What was Julian's troponin on 1 October?",
        # "last" and "current" only inside a longer word
        "Was an elastic bandage applied to Julian?",
        "Which concurrent medications does Julian take?",
        # "last" as a date or a sigh, not as the latest
        "What was the troponin last Thursday?",
        "What was Julian's blood pressure last night?",
        "At last, what was Julian's troponin?",
    ],
)
def test_given_a_question_that_does_not_ask_for_the_latest_when_verified_then_pass(question):
    # Arrange: the earlier reading would fail a question for the latest
    answer = Answer("Julian's troponin T was 52 ng/L.", (), question, grounding("52 ng/L", EARLIER_TROPONIN))

    # Act
    verdict = LatestReadingVerifier().verify(answer, [LABS], FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (Status.PASS, "the question does not ask for the latest")


def test_given_a_table_of_the_labs_when_verified_then_pass_with_no_figure_to_check():
    # Arrange: a table lists readings, each cell a source, and none is judged
    values = [("52 ng/L", EARLIER_TROPONIN), ("38 ng/L", LATEST_TROPONIN), ("4.1 mmol/L", POTASSIUM)]
    rows = [[{"display": display, "status": "sourced", "source": cited}] for display, cited in values]
    table = {"id": "t1", "status": "sourced", "rows": rows}
    answer = Answer("Julian's labs:\n...", (), "What were Julian's last labs?", grounded(tables=[table]))

    # Act
    verdict = LatestReadingVerifier().verify(answer, [LABS], FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (Status.PASS, "no figure to check")


def test_given_no_grounding_when_verified_then_pass():
    # Arrange
    answer = Answer("I don't know", (), LAST, "")

    # Act
    verdict = LatestReadingVerifier().verify(answer, [], FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (Status.PASS, "no figure to check")


@pytest.mark.parametrize(
    "cited, display, status, note",
    [
        pytest.param(
            LATEST_TROPONIN,
            "38 ng/L",
            Status.PASS,
            "the latest reading is cited",
            id="another-tests-later-reading-does-not-count",
        ),
        pytest.param(
            POTASSIUM,
            "4.1 mmol/L",
            Status.FAIL,
            "the answer cites the 2026-10-03 07:40 Potassium; the latest in the records is 2026-10-04 09:00",
            id="its-own-test-is-named",
        ),
    ],
)
def test_given_a_later_reading_of_another_test_when_verified_then_each_test_has_its_own_latest(
    cited, display, status, note
):
    # Arrange
    later_potassium = lab(307, "2026-10-04 09:00", "Potassium", 4.4, "mmol/L", "3.5-5.1")
    evidence = labs(EARLIER_TROPONIN_ROW, LATEST_TROPONIN_ROW, POTASSIUM_ROW, later_potassium)
    answer = Answer("...", (), "What are Julian's most recent labs?", grounding(display, cited))

    # Act
    verdict = LatestReadingVerifier().verify(answer, [evidence], FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (status, note)


@pytest.mark.parametrize(
    "cited, display, question, status, note",
    [
        pytest.param(
            systolic("901", "Julian Marek", "2026-10-02"),
            "142 mmHg",
            "What was Julian's most recent blood pressure?",
            Status.FAIL,
            "the answer cites the 2026-10-02 vitals; the latest in the records is 2026-10-03",
            id="the-entity-is-named-when-it-has-no-concept",
        ),
        pytest.param(
            systolic("902", "Amira Haddad", "2026-10-02"),
            "128 mmHg",
            "What was Amira's latest blood pressure?",
            Status.PASS,
            "the latest reading is cited",
            id="another-patients-later-reading-does-not-count",
        ),
    ],
)
def test_given_vitals_of_two_patients_when_verified_then_each_patient_has_their_own_latest(
    cited, display, question, status, note
):
    # Arrange
    answer = Answer("...", (), question, grounding(display, cited))

    # Act
    verdict = LatestReadingVerifier().verify(answer, [VITALS], FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (status, note)


def test_given_a_figure_the_grounding_flagged_when_verified_then_it_is_judged_too():
    # Arrange: the grounding keeps the source of a mismatch, here the earlier troponin claimed as the last
    flagged = grounding("52 ng/L", EARLIER_TROPONIN, "mismatch", reason="about 2026-10-01 06:10, not 2026-10-03")
    answer = Answer("The last troponin was 52 ng/L.", (), LAST, flagged)

    # Act
    verdict = LatestReadingVerifier().verify(answer, [LABS], FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (
        Status.FAIL,
        "the answer cites the 2026-10-01 06:10 Troponin T; the latest in the records is 2026-10-03 07:40",
    )


def test_given_an_earlier_reading_beside_the_latest_when_verified_then_pass():
    # Arrange: a series is judged as a whole, and the latest of it is cited
    cited = grounding_cells(("38 ng/L", LATEST_TROPONIN), ("52 ng/L", EARLIER_TROPONIN))
    answer = Answer("It was 38 ng/L, before 52 ng/L.", (), LAST, cited)

    # Act
    verdict = LatestReadingVerifier().verify(answer, [LABS], FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (Status.PASS, "the latest reading is cited")


def test_given_the_last_three_sessions_when_verified_then_pass():
    # Arrange: the prompt's own example, a trend over the sessions, cites the three on purpose
    cited = grounding_cells(
        ("17", phq9(1, "2026-08-27")),
        ("13", phq9(2, "2026-09-17")),
        ("9", phq9(3, "2026-10-08")),
        ("low, withdrawn", mood(1, "2026-08-27")),
        ("brighter, hopeful", mood(3, "2026-10-08")),
    )
    question = "How did Julian's depression score and mood change over his last three sessions?"
    answer = Answer("Over his last three sessions ...", (), question, cited)
    evidence = sessions(*SESSION_ROWS)

    # Act
    verdict = LatestReadingVerifier().verify(answer, [evidence], FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (Status.PASS, "the latest reading is cited")


def test_given_the_three_sessions_before_a_later_one_when_verified_then_fail_naming_the_latest_cited():
    # Arrange: a fourth session is in the records and the answer leaves it out
    cited = grounding_cells(("17", phq9(1, "2026-08-27")), ("13", phq9(2, "2026-09-17")), ("9", phq9(3, "2026-10-08")))
    answer = Answer(
        "Over his last three sessions ...", (), "How did Julian's score change over his last three sessions?", cited
    )
    evidence = sessions(*SESSION_ROWS, session(4, "2026-10-15", 7, "settled"))

    # Act
    verdict = LatestReadingVerifier().verify(answer, [evidence], FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (
        Status.FAIL,
        "the answer cites the 2026-10-08 sessions; the latest in the records is 2026-10-15",
    )


def test_given_a_stale_series_beside_a_current_one_when_verified_then_fail_naming_the_stale_one():
    # Arrange: a later troponin does not excuse a potassium that a later one, taken before it, outdates
    latest_troponin = lab(308, "2026-10-03 12:00", "Troponin T", 41, "ng/L", "<14")
    later_potassium = lab(307, "2026-10-03 09:00", "Potassium", 4.4, "mmol/L", "3.5-5.1")
    evidence = labs(EARLIER_TROPONIN_ROW, POTASSIUM_ROW, later_potassium, latest_troponin)
    cited = grounding_cells(("41 ng/L", troponin("308", "2026-10-03 12:00")), ("4.1 mmol/L", POTASSIUM))
    answer = Answer("...", (), "What are Julian's most recent troponin and potassium?", cited)

    # Act
    verdict = LatestReadingVerifier().verify(answer, [evidence], FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (
        Status.FAIL,
        "the answer cites the 2026-10-03 07:40 Potassium; the latest in the records is 2026-10-03 09:00",
    )


def test_given_an_earlier_troponin_and_the_latest_potassium_when_verified_then_fail_naming_the_troponin():
    # Arrange: each test is a series of its own, the potassium's latest does not make up for the troponin
    cited = grounding_cells(("52 ng/L", EARLIER_TROPONIN), ("4.1 mmol/L", POTASSIUM))
    answer = Answer("...", (), "What were Julian's last troponin and potassium?", cited)

    # Act
    verdict = LatestReadingVerifier().verify(answer, [LABS], FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (
        Status.FAIL,
        "the answer cites the 2026-10-01 06:10 Troponin T; the latest in the records is 2026-10-03 07:40",
    )


@pytest.mark.parametrize(
    "cited, name, period",
    [
        pytest.param(
            [("4.0 mmol/L", OLDER_POTASSIUM), ("52 ng/L", EARLIER_TROPONIN)],
            "Potassium",
            "2026-10-02 08:00",
            id="potassium-cited-first",
        ),
        pytest.param(
            [("52 ng/L", EARLIER_TROPONIN), ("4.0 mmol/L", OLDER_POTASSIUM)],
            "Troponin T",
            "2026-10-01 06:10",
            id="troponin-cited-first",
        ),
    ],
)
def test_given_two_stale_series_when_verified_then_the_one_cited_first_is_named(cited, name, period):
    # Arrange: both tests have a later reading than the one cited
    older_potassium = lab(310, "2026-10-02 08:00", "Potassium", 4.0, "mmol/L", "3.5-5.1")
    evidence = labs(EARLIER_TROPONIN_ROW, LATEST_TROPONIN_ROW, older_potassium, POTASSIUM_ROW)
    answer = Answer("...", (), LAST, grounding_cells(*cited))

    # Act
    verdict = LatestReadingVerifier().verify(answer, [evidence], FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (
        Status.FAIL,
        f"the answer cites the {period} {name}; the latest in the records is 2026-10-03 07:40",
    )


@pytest.mark.parametrize("test_value", [7, ""], ids=["number", "empty"])
def test_given_a_test_that_is_no_text_when_verified_then_the_entity_is_named(test_value):
    # Arrange: a column of a result may hold anything; only a word can name the series
    rows = [
        lab(401, "2026-10-01 06:10", test_value, 52, "ng/L", "<14"),
        lab(402, "2026-10-03 07:40", test_value, 38, "ng/L", "<14"),
    ]
    answer = Answer("...", (), LAST, grounding("52 ng/L", troponin("401", "2026-10-01 06:10")))

    # Act
    verdict = LatestReadingVerifier().verify(answer, [labs(*rows)], FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (
        Status.FAIL,
        "the answer cites the 2026-10-01 06:10 labs; the latest in the records is 2026-10-03 07:40",
    )


def test_given_an_earlier_reading_of_one_patient_and_the_latest_of_another_when_verified_then_fail():
    # Arrange: each patient's readings are a series of their own
    evidence = vitals(
        vital(901, JULIAN, "2026-10-02", 142, 88, 76),
        vital(902, AMIRA, "2026-10-02", 128, 82, 64),
        vital(903, JULIAN, "2026-10-03", 135, 84, 72),
        vital(904, AMIRA, "2026-10-09", 131, 80, 66),
    )
    cited = grounding_cells(
        ("135 mmHg", systolic("903", "Julian Marek", "2026-10-03")),
        ("128 mmHg", systolic("902", "Amira Haddad", "2026-10-02")),
    )
    answer = Answer("...", (), "What is the latest blood pressure of Julian and Amira?", cited)

    # Act
    verdict = LatestReadingVerifier().verify(answer, [evidence], FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (
        Status.FAIL,
        "the answer cites the 2026-10-02 vitals; the latest in the records is 2026-10-09",
    )


@pytest.mark.parametrize("lab_id", ["305", "309"])
def test_given_two_readings_tied_for_the_latest_when_either_is_cited_then_pass(lab_id):
    # Arrange: two troponins taken the same minute, both the latest
    tied = lab(309, "2026-10-03 07:40", "Troponin T", 36, "ng/L", "<14")
    evidence = labs(EARLIER_TROPONIN_ROW, LATEST_TROPONIN_ROW, tied)
    answer = Answer("...", (), LAST, grounding("38 ng/L", troponin(lab_id, "2026-10-03 07:40")))

    # Act
    verdict = LatestReadingVerifier().verify(answer, [evidence], FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (Status.PASS, "the latest reading is cited")


def test_given_a_stale_cell_of_one_result_beside_the_latest_of_another_when_verified_then_fail():
    # Arrange: two tool calls, each result its own series; the second call found nothing newer than its row
    second = Evidence("ev-6", "read_cardiology_records", labs(LATEST_TROPONIN_ROW).content_json)
    stale = {**EARLIER_TROPONIN, "evidence": "ev-2"}
    current = {**LATEST_TROPONIN, "evidence": "ev-6"}
    answer = Answer("...", (), LAST, grounding_cells(("52 ng/L", stale), ("38 ng/L", current)))

    # Act
    verdict = LatestReadingVerifier().verify(answer, [LABS, second], FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (
        Status.FAIL,
        "the answer cites the 2026-10-01 06:10 Troponin T; the latest in the records is 2026-10-03 07:40",
    )


@pytest.mark.parametrize(
    "entity, row_key, period, name",
    [
        pytest.param("cardiology.ecgs", "ecg_id", "recorded_on", "ecgs", id="ecgs"),
        pytest.param("psychiatry.sessions", "session_id", "session_date", "sessions", id="sessions"),
    ],
)
def test_given_an_earlier_ecg_or_session_cited_when_verified_then_fail_naming_the_entity(entity, row_key, period, name):
    # Arrange
    evidence = two_rows(entity, row_key, period, "2026-10-01", "2026-10-02")
    cited = grounding("...", source("ev-2", entity, period, "1", "Julian Marek", "2026-10-01"))
    answer = Answer("...", (), "What was Julian's last reading?", cited)

    # Act
    verdict = LatestReadingVerifier().verify(answer, [evidence], FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (
        Status.FAIL,
        f"the answer cites the 2026-10-01 {name}; the latest in the records is 2026-10-02",
    )


@pytest.mark.parametrize(
    "question, evidence, cited",
    [
        pytest.param(
            "What is Julian's current sertraline dose?",
            MEDICATIONS,
            source("ev-3", "ehr.medications", "dose", "43", "Julian Marek", "2026-06-12"),
            id="medications",
        ),
        pytest.param(
            "What is Julian's current diagnosis?",
            two_rows("psychiatry.diagnoses", "diagnosis_id", "diagnosed_on", "2026-08-20", "2026-10-01"),
            source("ev-2", "psychiatry.diagnoses", "diagnosis_id", "1", "Julian Marek", "2026-08-20"),
            id="diagnoses",
        ),
    ],
)
def test_given_a_cell_of_an_entity_that_holds_no_readings_when_verified_then_pass_with_no_figure_to_check(
    question, evidence, cited
):
    # Arrange: what a patient has started earlier is as current as what they started later
    answer = Answer("...", (), question, grounding("...", cited))

    # Act
    verdict = LatestReadingVerifier().verify(answer, [evidence], FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (Status.PASS, "no figure to check")


def result_without(**members):
    return Evidence("ev-2", "read_cardiology_records", json.dumps({"entity": "cardiology.labs", **members}))


@pytest.mark.parametrize(
    "grounding_json, evidence",
    [
        pytest.param(
            grounding_computed(
                "14 ng/L",
                op="subtract",
                of=[operand("52 ng/L", EARLIER_TROPONIN), operand("38 ng/L", LATEST_TROPONIN)],
            ),
            [LABS],
            id="computed-operands",
        ),
        pytest.param(
            grounding_computed("52 ng/L", op="max", by={"evidence": "ev-2", "column": "value"}, row=EARLIER_TROPONIN),
            [LABS],
            id="computed-row",
        ),
        pytest.param(
            grounding("135 mmHg", source("ev-5", "cardiology.vitals", "value", "2026-10-02", None, None, "mmHg")),
            [AVERAGE_BY_DAY],
            id="aggregate-though-it-labels-a-period",
        ),
        pytest.param(
            grounding("139.11 mmHg", source("ev-5", "cardiology.vitals", "value", "60-79", None, None, "mmHg")),
            [AVERAGES],
            id="aggregate",
        ),
        pytest.param(
            grounding("52 ng/L", EARLIER_TROPONIN),
            [labs(EARLIER_TROPONIN_ROW, LATEST_TROPONIN_ROW, period=None)],
            id="result-without-a-period-label",
        ),
        pytest.param(
            grounding("Call", source("ev-6", "notes", "note", "1", None, None)),
            [OWN_TOOL],
            id="result-a-tool-built-itself",
        ),
        pytest.param(grounding("52 ng/L", {**EARLIER_TROPONIN, "evidence": "ev-1"}), [PASSAGES, LABS], id="passages"),
        pytest.param(grounding("52 ng/L", {**EARLIER_TROPONIN, "evidence": "ev-9"}), [LABS], id="not-in-the-exchange"),
        pytest.param(grounding("52 ng/L", {**EARLIER_TROPONIN, "row_key": "999"}), [LABS], id="row-not-found"),
        pytest.param(
            grounded(
                [
                    {"id": "u1", "display": "52", "status": "unsourced", "reason": "typed in the text, not referenced"},
                    {"id": "f1", "display": "…", "status": "mismatch", "reason": "ev-2 has no column dose"},
                ]
            ),
            [LABS],
            id="no-source",
        ),
        pytest.param(grounding("52 ng/L", EARLIER_TROPONIN), [result_without(rows=None, labels=None)], id="null"),
        pytest.param(grounding("52 ng/L", EARLIER_TROPONIN), [result_without()], id="missing"),
    ],
)
def test_given_a_figure_it_cannot_judge_when_verified_then_pass_with_no_figure_to_check(grounding_json, evidence):
    # Arrange
    answer = Answer("Julian's last troponin T was 52 ng/L.", (), LAST, grounding_json)

    # Act
    verdict = LatestReadingVerifier().verify(answer, evidence, FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (Status.PASS, "no figure to check")


@pytest.mark.parametrize(
    "cited, note",
    [
        pytest.param(LATEST_TROPONIN, "the latest reading is cited", id="a-row-without-a-period-is-left-out"),
        pytest.param(troponin("307", None), "no figure to check", id="a-cited-row-without-a-period"),
    ],
)
def test_given_a_row_without_a_period_when_verified_then_it_is_not_compared(cited, note):
    # Arrange
    undated = lab(307, None, "Troponin T", 41, "ng/L", "<14")
    evidence = labs(EARLIER_TROPONIN_ROW, LATEST_TROPONIN_ROW, undated)
    answer = Answer("...", (), LAST, grounding("38 ng/L", cited))

    # Act
    verdict = LatestReadingVerifier().verify(answer, [evidence], FakeAIsolateManager())

    # Assert
    assert (verdict.status, verdict.note) == (Status.PASS, note)
