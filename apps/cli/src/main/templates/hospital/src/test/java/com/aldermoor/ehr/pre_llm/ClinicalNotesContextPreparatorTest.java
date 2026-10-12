package com.aldermoor.ehr.pre_llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.aldermoor.ehr.support.FakeAIsolateManager;
import com.aldermoor.ehr.support.FakeExchange;
import com.aldermoor.ehr.support.RecordingDataSource;
import com.reportix.aisolate.sdk.AirlockRef;
import com.reportix.aisolate.sdk.Principal;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ClinicalNotesContextPreparatorTest {

  @ParameterizedTest
  @ValueSource(strings = {"chief", "nurse", "employee"})
  void givenNoDoctorRole_whenPrepare_thenTheHealthRecordIsNeverOpened(String role) {
    // Arrange: the chief's statistics never come with a patient's notes
    FakeAIsolateManager grace =
        new FakeAIsolateManager(
            new Principal("grace", "grace@example.com", Set.of(role)),
            new AirlockRef("test-space", "cardiology", "test"));
    FakeExchange exchange = new FakeExchange("Average blood pressure by age group");

    // Act
    new ClinicalNotesContextPreparator().prepare(exchange, grace);

    // Assert
    assertThat(grace.dataSourcesAsked).isEmpty();
    assertThat(exchange.evidence).isEmpty();
  }

  @Test
  void givenDoctorAndNoDataSourceEhr_whenPrepare_thenNoPassagesAndNoError() {
    // Arrange: the fake defines no data source, as a space without ehr would
    FakeAIsolateManager clara =
        new FakeAIsolateManager(
            new Principal("clara", "clara@example.com", Set.of("doctor")),
            new AirlockRef("test-space", "psychiatry", "test"));
    FakeExchange exchange = new FakeExchange("How has Julian's mood changed?");

    // Act
    new ClinicalNotesContextPreparator().prepare(exchange, clara);

    // Assert
    assertThat(clara.dataSourcesAsked).containsExactly("ehr");
    assertThat(exchange.evidence).isEmpty();
    assertThat(exchange.context).isEmpty();
  }

  @Test
  void givenCardiologyDoctor_whenPrepare_thenSearchesHisCareTeamsCardiologyNotesOnly() {
    // Arrange: the department is the airlock's id, the clinician the token's e-mail
    RecordingDataSource ehr = new RecordingDataSource();
    FakeAIsolateManager noah =
        new FakeAIsolateManager(
            new Principal("noah", "noah@example.com", Set.of("doctor")),
            new AirlockRef("test-space", "cardiology", "test"),
            ehr.dataSource());
    FakeExchange exchange = new FakeExchange("What was Julian's last troponin?");

    // Act
    new ClinicalNotesContextPreparator().prepare(exchange, noah);

    // Assert
    RecordingDataSource.Statement search =
        ehr.statements.stream()
            .filter(s -> s.sql().equals(ClinicalNotesContextPreparator.SEARCH))
            .findFirst()
            .orElseThrow();
    assertThat(search.sql())
        .contains("JOIN ehr.care_team ct ON ct.patient_id = n.patient_id")
        .contains("JOIN ehr.clinicians c ON c.id = ct.clinician_id");
    assertThat(search.params())
        .containsEntry(1, "cardiology")
        .containsEntry(2, "noah@example.com")
        .containsEntry(6, ClinicalNotesContextPreparator.TOP_K);
    // The indexing step too reads cardiology's notes alone.
    assertThat(ehr.statements)
        .filteredOn(statement -> statement.sql().startsWith("SELECT"))
        .hasSize(2)
        .allSatisfy(statement -> assertThat(statement.params()).containsEntry(1, "cardiology"));
    assertThat(exchange.evidence).isEmpty();
  }

  @Test
  void givenEmbedding_whenToVector_thenPgvectorLiteral() {
    // Arrange
    float[] embedding = {0.5f, -1.0f, 2.25f};

    // Act
    String vector = ClinicalNotesContextPreparator.toVector(embedding);

    // Assert
    assertThat(vector).isEqualTo("[0.5,-1.0,2.25]");
  }
}
