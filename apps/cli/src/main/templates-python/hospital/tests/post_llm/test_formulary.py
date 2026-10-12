import json
import re

import pytest
from aisolate import Answer, DatabaseError, Evidence, Status
from support import FakeAIsolateManager, RecordingConnection, Statement

from aldermoor_ehr.post_llm.formulary import FormularyVerifier

# What SELECT DISTINCT drug FROM ehr.medications returns: one tuple per drug of the hospital's list.
FORMULARY = [("Apixaban",), ("Aspirin",), ("Sertraline",), ("Metformin",)]
JULIAN = {"patient": "Julian Marek", "patient_id": 17, "national_id": "****", "age_group": "60-79"}
QUESTION = "What does Julian take?"


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


def medication(medication_id, drug, drug_class, dose, frequency, prescribed_by, started_on):
    return {
        **JULIAN,
        "medication_id": medication_id,
        "drug": drug,
        "drug_class": drug_class,
        "dose": dose,
        "frequency": frequency,
        "prescribed_by": prescribed_by,
        "indication": "unknown: the medication list records no reason",
        "started_on": started_on,
        "stopped_on": None,
    }


def medications(*rows):
    """ehr.medications as read_medications returns it for Julian."""
    result = tool_result(
        "ehr.medications",
        list(rows),
        row_key="medication_id",
        subject="patient",
        period="started_on",
        masked=["national_id"],
    )
    return Evidence("ev-3", "read_medications", result)


MEDICATIONS = medications(
    medication(41, "Apixaban", "Anticoagulant", "5 mg", "twice daily", "cardiology", "2026-09-30"),
    medication(42, "Aspirin", "Antiplatelet", "100 mg", "once daily", "cardiology", "2026-09-30"),
    medication(43, "Sertraline", "SSRI", "50 mg", "once daily", "psychiatry", "2026-06-12"),
)
# The golden clinical note: a passage, not a record the tools returned.
NOTE_WORDS = "Continue apixaban 5 mg twice daily."
NOTE = Evidence(
    "ev-1",
    "clinical-notes",
    json.dumps(
        [
            {
                "title": "Cardiology note Julian Marek",
                "section": "2026-10-03",
                "body": f"Chest pain resolved overnight; troponin trending down. {NOTE_WORDS}",
            }
        ]
    ),
)
NOTE_ON_METFORMIN = Evidence(
    "ev-1",
    "clinical-notes",
    json.dumps([{"title": "Cardiology note Julian Marek", "section": "2026-10-03", "body": "Metformin stopped."}]),
)


def source(column, row_key):
    return {
        "evidence": "ev-3",
        "entity": "ehr.medications",
        "column": column,
        "row_key": row_key,
        "subject": "Julian Marek",
        "period": "2026-09-30",
    }


# The cells grounding writes in carry the drugs of the records, but are not the answer's own words.
DOSE = {"id": "f1", "display": "5 mg", "status": "sourced", "source": source("dose", "41")}
DRUGS = {
    "id": "t1",
    "status": "sourced",
    "rows": [
        [{"display": "Apixaban", "status": "sourced", "source": source("drug", "41")}],
        [{"display": "Aspirin", "status": "sourced", "source": source("drug", "42")}],
    ],
}
PLACEHOLDER = re.compile(r"\{([ft]\d+)}")


def quote(quote_id, status, text):
    """A quote as grounding writes it: the words, and whether the passage holds them as they are."""
    return {
        "id": quote_id,
        "status": status,
        "evidence": "ev-1",
        "index": 1,
        "title": "Cardiology note Julian Marek",
        "section": "2026-10-03",
        "text": text,
    }


def grounded(text, figures=(), tables=(), quotes=()):
    """The grounded answer as the airlock serialises it: the text in segments, a figure or
    a table where the text places it; a quote is in the text between double quotes."""
    segments = [
        {"text": part} if index % 2 == 0 else {"table" if part.startswith("t") else "figure": part}
        for index, part in enumerate(PLACEHOLDER.split(text))
        if part
    ]
    return json.dumps({"segments": segments, "figures": list(figures), "tables": list(tables), "quotes": list(quotes)})


@pytest.mark.parametrize(
    "text",
    ["Julian takes Metformin {f1}", "Julian takes metformin {f1}", "JULIAN TAKES METFORMIN, {f1}"],
    ids=["as-listed", "lower-case", "upper-case"],
)
def test_given_a_drug_the_records_do_not_hold_when_verified_then_fail_naming_it_as_the_formulary_does(text):
    # Arrange
    answer = Answer("...", (), QUESTION, grounded(text, [DOSE]))
    aisolate = FakeAIsolateManager(ehr=RecordingConnection(FORMULARY))

    # Act
    verdict = FormularyVerifier().verify(answer, [MEDICATIONS], aisolate)

    # Assert
    assert (verdict.status, verdict.note) == (Status.FAIL, "names a drug the returned records do not hold: Metformin")


# Bisoprolol is in the formulary, but no returned row holds it: nobody of this record takes it.
WITH_BISOPROLOL = [*FORMULARY, ("Bisoprolol",)]
NOT_TAKEN = "No, Julian does not take Bisoprolol; his medications: {t1}"


def test_given_a_drug_the_question_names_when_the_answer_says_it_is_not_taken_then_pass():
    # Arrange
    answer = Answer("...", (), "Is Julian on Bisoprolol?", grounded(NOT_TAKEN, tables=[DRUGS]))
    aisolate = FakeAIsolateManager(ehr=RecordingConnection(WITH_BISOPROLOL))

    # Act
    verdict = FormularyVerifier().verify(answer, [MEDICATIONS], aisolate)

    # Assert
    assert (verdict.status, verdict.note) == (Status.PASS, "no drug named outside the records")


def test_given_a_drug_the_question_does_not_name_when_the_answer_types_it_then_fail():
    # Arrange: the same answer, to a question that never said Bisoprolol
    answer = Answer("...", (), QUESTION, grounded(NOT_TAKEN, tables=[DRUGS]))
    aisolate = FakeAIsolateManager(ehr=RecordingConnection(WITH_BISOPROLOL))

    # Act
    verdict = FormularyVerifier().verify(answer, [MEDICATIONS], aisolate)

    # Assert
    assert (verdict.status, verdict.note) == (Status.FAIL, "names a drug the returned records do not hold: Bisoprolol")


@pytest.mark.parametrize("formulary", [FORMULARY, []], ids=["drugs", "none"])
def test_given_no_drug_typed_when_verified_then_pass_with_no_drug_named(formulary):
    # Arrange: the table shows Apixaban and Aspirin, but a table is not the answer's own words
    answer = Answer("...", (), QUESTION, grounded("Julian takes {t1}", tables=[DRUGS]))
    aisolate = FakeAIsolateManager(ehr=RecordingConnection(formulary))

    # Act
    verdict = FormularyVerifier().verify(answer, [MEDICATIONS], aisolate)

    # Assert
    assert (verdict.status, verdict.note) == (Status.PASS, "no drug named outside the records")


@pytest.mark.parametrize(
    "text", ["Julian takes Apixaban", "julian takes apixaban and Aspirin."], ids=["one", "two-in-another-spelling"]
)
def test_given_drugs_in_the_returned_records_when_verified_then_pass(text):
    # Arrange
    answer = Answer(text, (), QUESTION, grounded(text))
    aisolate = FakeAIsolateManager(ehr=RecordingConnection(FORMULARY))

    # Act
    verdict = FormularyVerifier().verify(answer, [MEDICATIONS], aisolate)

    # Assert
    assert (verdict.status, verdict.note) == (Status.PASS, "every drug named is in the records")


def test_given_a_drug_beside_one_the_records_do_not_hold_when_verified_then_fail():
    # Arrange
    text = "Julian takes Apixaban and Metformin"
    answer = Answer(text, (), QUESTION, grounded(text))
    aisolate = FakeAIsolateManager(ehr=RecordingConnection(FORMULARY))

    # Act
    verdict = FormularyVerifier().verify(answer, [MEDICATIONS], aisolate)

    # Assert
    assert (verdict.status, verdict.note) == (Status.FAIL, "names a drug the returned records do not hold: Metformin")


@pytest.mark.parametrize(
    "formulary, text",
    [
        pytest.param(
            [("Warfarin",), ("Metformin",), ("Aspirin",)], "Julian takes Warfarin and Metformin", id="by-name"
        ),
        # Sorted as the names are spelled, as Java's natural order does: upper case first
        pytest.param(
            [("atorvastatin",), ("Metformin",)], "Julian takes atorvastatin and metformin", id="upper-case-first"
        ),
    ],
)
def test_given_two_drugs_the_records_do_not_hold_when_verified_then_the_first_by_name_is_named(formulary, text):
    # Arrange: whatever order the database returns the list in
    answer = Answer(text, (), QUESTION, grounded(text))
    aisolate = FakeAIsolateManager(ehr=RecordingConnection(formulary))

    # Act
    verdict = FormularyVerifier().verify(answer, [MEDICATIONS], aisolate)

    # Assert
    assert (verdict.status, verdict.note) == (Status.FAIL, "names a drug the returned records do not hold: Metformin")


def test_given_a_drug_name_with_a_regex_character_when_verified_then_it_is_matched_as_written():
    # Arrange
    text = "Julian takes amoxicillin+clavulanate"
    answer = Answer(text, (), QUESTION, grounded(text))
    aisolate = FakeAIsolateManager(ehr=RecordingConnection([("Amoxicillin+clavulanate",)]))

    # Act
    verdict = FormularyVerifier().verify(answer, [MEDICATIONS], aisolate)

    # Assert
    assert (verdict.status, verdict.note) == (
        Status.FAIL,
        "names a drug the returned records do not hold: Amoxicillin+clavulanate",
    )


def test_given_a_drug_split_by_a_figure_when_verified_then_it_names_no_drug():
    # Arrange: the text pieces are words apart, never one word
    answer = Answer("...", (), QUESTION, grounded("Julian takes Aspir{f1}in", [DOSE]))
    aisolate = FakeAIsolateManager(ehr=RecordingConnection(FORMULARY))

    # Act
    verdict = FormularyVerifier().verify(answer, [MEDICATIONS], aisolate)

    # Assert
    assert (verdict.status, verdict.note) == (Status.PASS, "no drug named outside the records")


def test_given_blank_names_in_the_formulary_when_verified_then_they_name_no_drug():
    # Arrange: a blank name would be a word between any two words, even two spaces apart
    text = "Julian takes  Metformin"
    answer = Answer(text, (), QUESTION, grounded(text))
    aisolate = FakeAIsolateManager(ehr=RecordingConnection([("",), ("  ",), (None,)]))

    # Act
    verdict = FormularyVerifier().verify(answer, [MEDICATIONS], aisolate)

    # Assert
    assert (verdict.status, verdict.note) == (Status.PASS, "no drug named outside the records")


@pytest.mark.parametrize("text", ["Julian takes Metformins", "Julian takes bimetformin", "Julian takes Metformin2"])
def test_given_a_word_that_only_contains_a_drug_when_verified_then_it_names_no_drug(text):
    # Arrange
    answer = Answer(text, (), QUESTION, grounded(text))
    aisolate = FakeAIsolateManager(ehr=RecordingConnection(FORMULARY))

    # Act
    verdict = FormularyVerifier().verify(answer, [MEDICATIONS], aisolate)

    # Assert
    assert (verdict.status, verdict.note) == (Status.PASS, "no drug named outside the records")


# The records of a drug are the medication rows returned, and those alone: not a note that names it, not
# another entity with a column drug.
PHARMACY_ORDERS = Evidence(
    "ev-6", "find_prescriptions", tool_result("pharmacy.orders", [{"order_id": 1, "drug": "Metformin"}])
)


@pytest.mark.parametrize(
    "evidence, drug",
    [
        pytest.param([NOTE_ON_METFORMIN, MEDICATIONS], "Metformin", id="a-note-that-names-it"),
        pytest.param([PHARMACY_ORDERS, MEDICATIONS], "Metformin", id="a-result-of-another-entity"),
        pytest.param(
            [Evidence("ev-3", "read_medications", json.dumps({"entity": "ehr.medications", "rows": None}))],
            "Apixaban",
            id="a-medication-result-with-null-rows",
        ),
        pytest.param(
            [Evidence("ev-3", "read_medications", json.dumps({"entity": "ehr.medications"}))],
            "Apixaban",
            id="a-medication-result-without-rows",
        ),
    ],
)
def test_given_records_holding_no_such_drug_when_typed_then_fail_naming_it(evidence, drug):
    # Arrange
    text = f"Julian takes {drug}"
    answer = Answer(text, (), QUESTION, grounded(text))
    aisolate = FakeAIsolateManager(ehr=RecordingConnection(FORMULARY))

    # Act
    verdict = FormularyVerifier().verify(answer, evidence, aisolate)

    # Assert
    assert (verdict.status, verdict.note) == (Status.FAIL, f"names a drug the returned records do not hold: {drug}")


def test_given_the_words_of_a_note_quoted_when_verified_then_pass_with_no_drug_named():
    # Arrange: apixaban is the note's own word, and no medication list was returned
    text = f'The note says: "{NOTE_WORDS}"'
    answer = Answer(text, (), "What does the note say?", grounded(text, quotes=[quote("q1", "quoted", NOTE_WORDS)]))
    aisolate = FakeAIsolateManager(ehr=RecordingConnection(FORMULARY))

    # Act
    verdict = FormularyVerifier().verify(answer, [NOTE], aisolate)

    # Assert
    assert (verdict.status, verdict.note) == (Status.PASS, "no drug named outside the records")


def test_given_the_words_of_a_note_typed_and_not_quoted_when_verified_then_fail():
    # Arrange
    answer = Answer(NOTE_WORDS, (), "What does the note say?", grounded(NOTE_WORDS))
    aisolate = FakeAIsolateManager(ehr=RecordingConnection(FORMULARY))

    # Act
    verdict = FormularyVerifier().verify(answer, [NOTE], aisolate)

    # Assert
    assert (verdict.status, verdict.note) == (Status.FAIL, "names a drug the returned records do not hold: Apixaban")


def test_given_words_the_grounding_did_not_find_in_the_note_when_verified_then_they_are_scanned():
    # Arrange: a quote flagged as not verbatim is the model's own words
    text = f'The note says: "{NOTE_WORDS}"'
    answer = Answer(text, (), "What does the note say?", grounded(text, quotes=[quote("q1", "mismatch", NOTE_WORDS)]))
    aisolate = FakeAIsolateManager(ehr=RecordingConnection(FORMULARY))

    # Act
    verdict = FormularyVerifier().verify(answer, [NOTE], aisolate)

    # Assert
    assert (verdict.status, verdict.note) == (Status.FAIL, "names a drug the returned records do not hold: Apixaban")


def test_given_a_quote_beside_a_drug_typed_outside_it_when_verified_then_that_drug_is_named():
    # Arrange
    text = f'The note says: "{NOTE_WORDS}" Julian also takes Metformin.'
    answer = Answer(text, (), "What does the note say?", grounded(text, quotes=[quote("q1", "quoted", NOTE_WORDS)]))
    aisolate = FakeAIsolateManager(ehr=RecordingConnection(FORMULARY))

    # Act
    verdict = FormularyVerifier().verify(answer, [NOTE], aisolate)

    # Assert
    assert (verdict.status, verdict.note) == (Status.FAIL, "names a drug the returned records do not hold: Metformin")


def test_given_a_short_quote_when_verified_then_it_hides_nothing_outside_its_quote_marks():
    # Arrange: "pirin" is a quote of the note, and part of the Aspirin typed elsewhere is not it
    text = 'The note says: "pirin" and Julian takes Aspirin'
    answer = Answer(text, (), "What does the note say?", grounded(text, quotes=[quote("q1", "quoted", "pirin")]))
    aisolate = FakeAIsolateManager(ehr=RecordingConnection(FORMULARY))

    # Act
    verdict = FormularyVerifier().verify(answer, [NOTE], aisolate)

    # Assert
    assert (verdict.status, verdict.note) == (Status.FAIL, "names a drug the returned records do not hold: Aspirin")


def test_given_a_one_word_quote_when_verified_then_the_same_word_typed_elsewhere_is_still_named():
    # Arrange: the quote exempts the quoted word, not Apixaban wherever it stands
    text = 'The note says: "Apixaban" and Julian takes Apixaban'
    answer = Answer(text, (), "What does the note say?", grounded(text, quotes=[quote("q1", "quoted", "Apixaban")]))
    aisolate = FakeAIsolateManager(ehr=RecordingConnection(FORMULARY))

    # Act
    verdict = FormularyVerifier().verify(answer, [NOTE], aisolate)

    # Assert
    assert (verdict.status, verdict.note) == (Status.FAIL, "names a drug the returned records do not hold: Apixaban")


def test_given_a_quote_inside_another_quote_when_verified_then_both_are_left_alone():
    # Arrange: the note itself has quote marks; cutting the short quote first would leave the long one's aspirin
    outer = 'now stop "apixaban" and aspirin'
    text = f'The note says: "{outer}" and "apixaban".'
    quotes = [quote("q2", "quoted", "apixaban"), quote("q1", "quoted", outer)]
    answer = Answer(text, (), "What does the note say?", grounded(text, quotes=quotes))
    aisolate = FakeAIsolateManager(ehr=RecordingConnection(FORMULARY))

    # Act
    verdict = FormularyVerifier().verify(answer, [NOTE], aisolate)

    # Assert
    assert (verdict.status, verdict.note) == (Status.PASS, "no drug named outside the records")


def test_given_a_quote_between_two_pieces_of_a_word_when_verified_then_it_joins_nothing():
    # Arrange: Aspir, a quote, in: never the word Aspirin
    text = 'Julian takes Aspir"x"in'
    answer = Answer(text, (), QUESTION, grounded(text, quotes=[quote("q1", "quoted", "x")]))
    aisolate = FakeAIsolateManager(ehr=RecordingConnection(FORMULARY))

    # Act
    verdict = FormularyVerifier().verify(answer, [NOTE], aisolate)

    # Assert
    assert (verdict.status, verdict.note) == (Status.PASS, "no drug named outside the records")


@pytest.mark.parametrize("formulary", [FORMULARY, []], ids=["drugs", "none"])
def test_given_two_answers_when_verified_then_the_formulary_is_read_once_and_only_its_drug_names(formulary):
    # Arrange
    ehr = RecordingConnection(formulary)
    aisolate = FakeAIsolateManager(ehr=ehr)
    answer = Answer("...", (), QUESTION, grounded("Julian takes {t1}", tables=[DRUGS]))
    verifier = FormularyVerifier()

    # Act
    verifier.verify(answer, [MEDICATIONS], aisolate)
    verifier.verify(answer, [MEDICATIONS], aisolate)

    # Assert
    assert ehr.statements == [Statement("SELECT DISTINCT drug FROM ehr.medications", ())]
    assert aisolate.data_sources_asked == ["ehr"]


def test_given_no_grounding_when_verified_then_pass_without_opening_the_health_record():
    # Arrange
    answer = Answer("I don't know", (), QUESTION, "")
    aisolate = FakeAIsolateManager(ehr=RecordingConnection(FORMULARY))

    # Act
    verdict = FormularyVerifier().verify(answer, [], aisolate)

    # Assert
    assert (verdict.status, verdict.note) == (Status.PASS, "no figure to check")
    assert aisolate.data_sources_asked == []


def test_given_no_data_source_ehr_when_verified_then_it_raises_for_the_airlock_to_warn():
    # Arrange: the fake defines no data source, as a space without ehr would
    answer = Answer("...", (), QUESTION, grounded("Julian takes {t1}", tables=[DRUGS]))

    # Act / Assert: raised, not caught: the airlock turns it into a WARN verdict
    with pytest.raises(ValueError):
        FormularyVerifier().verify(answer, [MEDICATIONS], FakeAIsolateManager())


class UnreadableConnection(RecordingConnection):
    """A health record that fails every statement, as a database that went away does."""

    def execute(self, sql, params=()):
        raise DatabaseError("connection lost")


def test_given_a_database_error_when_verified_then_it_propagates_for_the_airlock_to_warn():
    # Arrange
    answer = Answer("...", (), QUESTION, grounded("Julian takes {t1}", tables=[DRUGS]))
    aisolate = FakeAIsolateManager(ehr=UnreadableConnection())

    # Act / Assert
    with pytest.raises(DatabaseError):
        FormularyVerifier().verify(answer, [MEDICATIONS], aisolate)


def test_given_the_formulary_unreadable_once_when_verified_again_then_it_is_read_then():
    # Arrange
    text = "Julian takes Metformin"
    answer = Answer(text, (), QUESTION, grounded(text))
    aisolate = FakeAIsolateManager()
    verifier = FormularyVerifier()
    with pytest.raises(ValueError):
        verifier.verify(answer, [MEDICATIONS], aisolate)
    aisolate.ehr = RecordingConnection(FORMULARY)

    # Act
    verdict = verifier.verify(answer, [MEDICATIONS], aisolate)

    # Assert
    assert (verdict.status, verdict.note) == (Status.FAIL, "names a drug the returned records do not hold: Metformin")
