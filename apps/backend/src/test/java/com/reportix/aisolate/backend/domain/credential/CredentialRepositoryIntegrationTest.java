package com.reportix.aisolate.backend.domain.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reportix.aisolate.backend.BackendApplication;
import com.reportix.aisolate.backend.support.BackendTestConfig;
import com.reportix.aisolate.backend.support.OwnDatabase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionOperations;

@SpringBootTest(classes = BackendApplication.class)
@ContextConfiguration(initializers = {BackendTestConfig.class, OwnDatabase.class})
@TestPropertySource(properties = "aisolate.test.database=aisolate_credentials")
class CredentialRepositoryIntegrationTest {

  @Autowired CredentialRepository repository;
  @Autowired JdbcClient db;
  @Autowired TransactionOperations transactions;

  private static CredentialRepository.Row row(String ref) {
    return row(ref, "install", "");
  }

  private static CredentialRepository.Row row(String ref, String ownerKind, String owner) {
    return new CredentialRepository.Row(
        ref,
        ownerKind,
        owner,
        "ds",
        "hr",
        "deadbeef",
        new byte[12],
        "ct".getBytes(),
        "andrew@example.com");
  }

  private static String ref() {
    return "secret-" + UUID.randomUUID();
  }

  private void insert(CredentialRepository.Row row) {
    transactions.executeWithoutResult(s -> repository.insert(row));
  }

  @Test
  void givenAnEvent_whenRecorded_thenItIsReadBack() {
    // Arrange
    String ref = ref();
    CredentialRepository.Row row = row(ref);

    // Act
    repository.record(CredentialRepository.Event.of(row, "andrew@example.com", "stored", null));

    // Assert
    List<CredentialRepository.Event> events = repository.events(ref);
    assertThat(events).hasSize(1);
    assertThat(events.get(0).action()).isEqualTo("stored");
    assertThat(events.get(0).actor()).isEqualTo("andrew@example.com");
    assertThat(events.get(0).reason()).isNull();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "UPDATE credential_events SET actor = 'x'",
        "DELETE FROM credential_events",
        "TRUNCATE credential_events"
      })
  void givenAnEvent_whenChangedOrRemoved_thenTheTriggerRefuses(String sql) {
    // Arrange
    repository.record(CredentialRepository.Event.of(row(ref()), "a", "stored", null));

    // Act
    var change = assertThatThrownBy(() -> db.sql(sql).update());

    // Assert
    change.isInstanceOf(DataAccessException.class).hasStackTraceContaining("append-only");
  }

  @Test
  void givenARow_whenDeleteOrphan_thenTheRowGoesAndADeletedEventStays() {
    // Arrange
    String ref = ref();
    insert(row(ref));

    // Act
    var deleted = repository.deleteOrphan(ref, "nora@example.com");

    // Assert
    assertThat(deleted).isPresent();
    assertThat(deleted.get().secretRef()).isEqualTo(ref);
    assertThat(repository.find(ref)).isEmpty();
    List<CredentialRepository.Event> events = repository.events(ref);
    assertThat(events.get(events.size() - 1).action()).isEqualTo("deleted");
    assertThat(events.get(events.size() - 1).actor()).isEqualTo("nora@example.com");
  }

  @Test
  void givenNoRow_whenDeleteOrphan_thenEmptyAndNoEvent() {
    // Arrange
    String ref = ref();

    // Act
    var deleted = repository.deleteOrphan(ref, "nora@example.com");

    // Assert
    assertThat(deleted).isEmpty();
    assertThat(repository.events(ref)).isEmpty();
  }

  @Test
  void givenAnEventRecordedInsideARolledBackTransaction_thenTheEventStays() {
    // Arrange
    String ref = ref();

    // Act
    transactions.execute(
        s -> {
          repository.record(CredentialRepository.Event.of(row(ref), "a", "stored", null));
          s.setRollbackOnly();
          return null;
        });

    // Assert
    assertThat(repository.events(ref)).hasSize(1);
  }

  @Test
  void givenRowsOfEveryOwnerKind_whenDeleteSpacesAndAirlocks_thenTheInstallsStays() {
    // Arrange
    String install = ref();
    String space = ref();
    String airlock = ref();
    insert(row(install, "install", ""));
    insert(row(space, "space", "hr"));
    insert(row(airlock, "airlock", "hr/chat"));

    // Act
    List<CredentialRepository.Row> deleted =
        transactions.execute(s -> repository.deleteSpacesAndAirlocks());

    // Assert
    assertThat(deleted).extracting(CredentialRepository.Row::secretRef).contains(space, airlock);
    assertThat(deleted).extracting(CredentialRepository.Row::secretRef).doesNotContain(install);
    assertThat(repository.find(install)).isPresent();
    assertThat(repository.find(space)).isEmpty();
    assertThat(repository.find(airlock)).isEmpty();
  }
}
