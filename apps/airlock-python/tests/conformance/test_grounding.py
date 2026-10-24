import json
import re

import pytest
from aisolate import Passage

from aisolate_airlock import answer_format
from aisolate_airlock.evidence import Labels
from aisolate_airlock.grounding import MISMATCH, UNSOURCED, Call, Figure, Grounding, Passages, Rows
from conftest import read_case

TEMPLATES = ["company-hr", "company-sales", "auditor", "hospital"]
# Beyond the templates' sets: one rule's cases over their own evidence.
EXTRA = ["concept-wording"]

ERRORS = {
    "correct",
    "wrong_subject",
    "wrong_period",
    "wrong_concept",
    "wrong_unit",
    "wrong_scope",
    "typed_computation",
    "fabricated",
    "scale",
    "sign",
    "table_wrong_order",
    "table_missing_row",
    "table_other_evidence",
    "ranking_wrong_order",
    "ranking_missing_row",
    "ranking_other_evidence",
}

FIXTURES = {name: read_case("grounding", f"{name}.json") for name in TEMPLATES + EXTRA}
CASES = [(name, golden) for name in TEMPLATES + EXTRA for golden in FIXTURES[name]["cases"]]


def grounding(fixture, question):
    """The exchange's evidence as the airlock keeps it for grounding."""
    rows, documents = [], []
    for evidence in fixture["evidence"]:
        if "passages" in evidence:
            documents.append(Passages(evidence["id"], [Passage(**p) for p in evidence["passages"]]))
            continue
        rows.append(
            Rows(
                evidence["id"],
                Call(evidence["tool"], evidence["args"]),
                evidence["entity"],
                evidence["columns"],
                Labels.of(evidence["labels"]),
                evidence["rows"],
            )
        )
    return Grounding(rows, documents, question)


def case_id(case):
    template, golden = case
    return re.sub(r"\W+", "-", f"{template} {golden['error']} {golden['name']}").strip("-")


@pytest.mark.parametrize("template,golden", CASES, ids=[case_id(c) for c in CASES])
def test_given_model_answer_over_the_templates_evidence_when_grounded_then_every_figure_has_its_expected_status(
    template, golden
):
    # Arrange
    fixture = FIXTURES[template]
    # Jackson writes the case's decimals and the parser reads them back as doubles.
    answer = answer_format.parse(json.dumps(golden["answer"], default=float))

    # Act
    grounded = grounding(fixture, golden["question"]).ground(answer)

    # Assert
    statuses = {f.id: f.status for f in grounded.figures} | {t.id: t.status for t in grounded.tables}
    assert statuses == golden["expect"]
    if "quotes" in golden:
        assert {q.id: q.status for q in grounded.quotes} == golden["quotes"]
    if "rendered" in golden:
        assert grounded.answer == golden["rendered"]
    if "reason" in golden:
        reasons = [str(f.reason) for f in grounded.figures] + [str(t.reason) for t in grounded.tables]
        assert any(golden["reason"] in reason for reason in reasons), reasons
    assert "****" not in grounded.answer
    assert not any("****" in f.display for f in grounded.figures)
    assert grounding(fixture, golden["question"]).ground(answer) == grounded


def test_given_golden_sets_when_read_then_each_template_covers_every_error_and_each_error_is_caught():
    # Arrange
    covered = {template: set() for template in TEMPLATES}

    # Act
    for template in TEMPLATES:
        for golden in FIXTURES[template]["cases"]:
            covered[template].add(golden["error"])
            # An injected error expects a mismatch or an unsourced figure: never a clean answer
            if golden["error"] != "correct":
                assert {MISMATCH, UNSOURCED} & set(golden["expect"].values()), golden["name"]

    # Assert
    for template, errors in covered.items():
        assert errors >= ERRORS, template


@pytest.mark.parametrize("template", TEMPLATES)
def test_given_golden_set_when_read_then_row_handles_are_numbered_across_the_exchange(template):
    # Arrange
    evidence = FIXTURES[template]["evidence"]

    # Act
    handles = [handle for e in evidence for handle in e.get("rows", {})]

    # Assert
    assert handles == [f"r{i}" for i in range(1, len(handles) + 1)]


def _typed(text, quotes=()):
    """An answer with no placeholder: whatever it states, it typed."""
    return answer_format.parse(json.dumps({"text": text, "figures": [], "quotes": list(quotes), "dont_know": False}))


@pytest.mark.parametrize(
    ("labels", "facts"),
    [({"facts": ["drug", "dose"]}, ["drug", "dose"]), ({"facts": None}, []), ({}, []), (None, [])],
)
def test_given_tool_result_labels_when_read_then_facts_or_empty(labels, facts):
    # Arrange / Act
    read = Labels.of(labels)

    # Assert
    assert read.facts == facts


def test_given_dose_typed_when_grounded_then_one_unsourced_figure_naming_the_row_that_holds_it():
    # Arrange
    answer = _typed("Julian takes 5 mg.")

    # Act
    grounded = grounding(FIXTURES["hospital"], "What does Julian take?").ground(answer)

    # Assert: "5 mg" is one value of a fact column, not the number 5
    assert grounded.figures == [
        Figure("u1", "5 mg", None, UNSOURCED, None, None,
               "typed in the text, not referenced; ev-3 r4 dose holds the same value")
    ]
    assert grounded.answer == "Julian takes 5 mg."


TYPED = "typed in the text, not referenced"
DRUGS = Rows("ev-1", Call("read_medications", {}), "ehr.medications", ["drug"], Labels(facts=["drug"]),
             {"r1": {"drug": "Insulin"}, "r2": {"drug": "Insulin glargine"}, "r3": {"drug": " ab "}})


@pytest.mark.parametrize(
    ("question", "text", "typed"),
    [
        ("Does Julian take apixaban?", "Yes, Julian takes APIXABAN.", []),
        ("Does he take it twice  daily?", "Yes, twice\ndaily.", []),
        ("Is Julian on 5 mg of it?", "Yes, 5 mg.", []),
        ("Is Julian on 25 mg of anything?", "No, 5 mg.", ["5 mg"]),
        ("Is Julian on 2.5 mg of anything?", "No, 5 mg.", ["5 mg"]),
    ],
)
def test_given_fact_value_in_the_question_when_typed_then_exempt_only_as_a_whole_phrase_of_it(question, text, typed):
    # Arrange
    answer = _typed(text)

    # Act
    grounded = grounding(FIXTURES["hospital"], question).ground(answer)

    # Assert
    assert [f.display for f in grounded.figures] == typed


@pytest.mark.parametrize(
    ("question", "typed"),
    [("Is Julian on insulin glargine?", []), ("Is Julian's insulinoma treated?", ["insulin"])],
)
def test_given_question_holding_the_fact_in_a_phrase_or_a_word_when_typed_then_exempt_only_in_the_phrase(
    question, typed
):
    # Arrange
    answer = _typed("Julian takes insulin.")

    # Act
    grounded = Grounding([DRUGS], [], question).ground(answer)

    # Assert
    assert [f.display for f in grounded.figures] == typed


@pytest.mark.parametrize(
    ("text", "figures"),
    [
        ("Julian takes Apixaban 2.5 mg.", [("2.5", TYPED)]),
        ("Julian takes Apixaban 5 mg, daily.", [("5 mg", TYPED + "; ev-3 r4 dose holds the same value")]),
    ],
)
def test_given_fact_value_inside_a_number_when_typed_then_the_number_is_one_token(text, figures):
    # Arrange
    answer = _typed(text)

    # Act
    grounded = grounding(FIXTURES["hospital"], "How much Apixaban does Julian take?").ground(answer)

    # Assert
    assert [(f.display, f.reason) for f in grounded.figures] == figures


def test_given_facts_and_numbers_interleaved_when_typed_then_numbered_in_text_order():
    # Arrange
    answer = _typed("Take 3 tablets: Aspirin 100 mg, then 7 days Aspirin.")

    # Act
    grounded = grounding(FIXTURES["hospital"], "What does Julian take?").ground(answer)

    # Assert
    aspirin = TYPED + "; ev-3 r5 drug holds the same value"
    assert [(f.id, f.display, f.reason) for f in grounded.figures] == [
        ("u1", "3", TYPED),
        ("u2", "Aspirin", aspirin),
        ("u3", "100 mg", TYPED + "; ev-3 r5 dose holds the same value"),
        ("u4", "7", TYPED),
        ("u5", "Aspirin", aspirin),
    ]
    assert grounded.answer == "Take 3 tablets: Aspirin 100 mg, then 7 days Aspirin."


def test_given_value_held_by_several_cells_when_typed_then_the_first_by_result_fact_column_and_row():
    # Arrange: brand is ev-1's first fact column, so r2 brand comes before r1 drug
    first = Rows("ev-1", Call("read_medications", {}), "ehr.medications", ["drug", "brand"],
                 Labels(facts=["brand", "drug"]),
                 {"r1": {"drug": "Aspirin", "brand": "Bayer"}, "r2": {"drug": "Ibuprofen", "brand": "Aspirin"}})
    second = Rows("ev-2", Call("read_medications", {}), "ehr.medications", ["drug"], Labels(facts=["drug"]),
                  {"r3": {"drug": "Ibuprofen"}, "r4": {"drug": "Bayer"}})

    # Act
    grounded = Grounding([first, second], [], "What does Julian take?").ground(_typed("Aspirin, Ibuprofen, Bayer."))

    # Assert
    assert [f.reason for f in grounded.figures] == [
        TYPED + "; ev-1 r2 brand holds the same value",
        TYPED + "; ev-1 r2 drug holds the same value",
        TYPED + "; ev-1 r1 brand holds the same value",
    ]


@pytest.mark.parametrize(
    ("quoted", "typed"),
    [
        ("Continue apixaban 5 mg twice daily.", []),
        ("Continue apixaban 5 mg once daily.", ["apixaban", "5 mg", "once daily"]),
    ],
)
def test_given_quote_when_grounded_then_its_facts_unsourced_only_when_not_verbatim(quoted, typed):
    # Arrange
    answer = _typed("The note says {q1}.", [{"id": "q1", "passage": {"evidence": "ev-1", "index": 1}, "text": quoted}])

    # Act
    grounded = grounding(FIXTURES["hospital"], "How is Julian treated?").ground(answer)

    # Assert
    assert [f.display for f in grounded.figures] == typed


@pytest.mark.parametrize(
    ("text", "typed"),
    [
        ("Julian takes insulin glargine.", ["insulin glargine"]),
        ("Julian takes Insulin.", ["Insulin"]),
        ("Julian takes insulinum.", []),
        ("Julian takes ab.", []),
    ],
)
def test_given_fact_values_when_typed_then_the_longest_whole_words_of_three_characters_or_more(text, typed):
    # Arrange: " ab " is under three characters once trimmed, no fact
    answer = _typed(text)

    # Act
    grounded = Grounding([DRUGS], [], "What does Julian take?").ground(answer)

    # Assert
    assert [f.display for f in grounded.figures] == typed
