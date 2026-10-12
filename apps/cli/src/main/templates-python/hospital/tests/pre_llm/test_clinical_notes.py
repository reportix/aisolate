import pytest
from aisolate import AirlockRef, Principal
from support import FakeAIsolateManager, FakeExchange, RecordingConnection

from aldermoor_ehr.pre_llm.clinical_notes import SEARCH, TOP_K, ClinicalNotesContextPreparator, to_vector


@pytest.mark.parametrize("role", ["chief", "nurse", "employee"])
def test_given_no_doctor_role_when_prepare_then_the_health_record_is_never_opened(role):
    # Arrange: the chief's statistics never come with a patient's notes
    grace = FakeAIsolateManager(
        Principal("grace", "grace@example.com", frozenset({role})), AirlockRef("test-space", "cardiology", "test")
    )
    exchange = FakeExchange("Average blood pressure by age group")

    # Act
    ClinicalNotesContextPreparator().prepare(exchange, grace)

    # Assert
    assert grace.data_sources_asked == []
    assert exchange.evidence == []


def test_given_doctor_and_no_data_source_ehr_when_prepare_then_no_passages_and_no_error():
    # Arrange: the fake defines no data source, as a space without ehr would
    clara = FakeAIsolateManager(
        Principal("clara", "clara@example.com", frozenset({"doctor"})),
        AirlockRef("test-space", "psychiatry", "test"),
    )
    exchange = FakeExchange("How has Julian's mood changed?")

    # Act
    ClinicalNotesContextPreparator().prepare(exchange, clara)

    # Assert
    assert clara.data_sources_asked == ["ehr"]
    assert exchange.evidence == []
    assert exchange.context == []


def test_given_cardiology_doctor_when_prepare_then_searches_his_care_teams_cardiology_notes_only():
    # Arrange: the department is the airlock's id, the clinician the token's e-mail
    ehr = RecordingConnection()
    noah = FakeAIsolateManager(
        Principal("noah", "noah@example.com", frozenset({"doctor"})),
        AirlockRef("test-space", "cardiology", "test"),
        ehr,
    )
    exchange = FakeExchange("What was Julian's last troponin?")

    # Act
    ClinicalNotesContextPreparator().prepare(exchange, noah)

    # Assert
    search = next(s for s in ehr.statements if s.sql == SEARCH)
    assert "JOIN ehr.care_team ct ON ct.patient_id = n.patient_id" in search.sql
    assert "JOIN ehr.clinicians c ON c.id = ct.clinician_id" in search.sql
    assert search.params[0] == "cardiology"
    assert search.params[1] == "noah@example.com"
    assert search.params[5] == TOP_K
    # The indexing step too reads cardiology's notes alone.
    selects = [s for s in ehr.statements if s.sql.startswith("SELECT")]
    assert len(selects) == 2
    assert all(s.params[0] == "cardiology" for s in selects)
    assert exchange.evidence == []


def test_given_embedding_when_to_vector_then_pgvector_literal():
    # Arrange
    embedding = [0.5, -1.0, 2.25]

    # Act
    vector = to_vector(embedding)

    # Assert
    assert vector == "[0.5,-1.0,2.25]"
