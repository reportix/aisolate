package com.reportix.aisolate.backend.domain.airlock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.reportix.aisolate.backend.infrastructure.cluster.ClusterProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class DataSourceSecretsTest {

  private static final String URL = "jdbc:postgresql://acme-db:5432/hr";

  private final DataSourceSecrets withCluster =
      new DataSourceSecrets(null, new ClusterProperties("aisolate"), event -> {});

  private static DataSourceFormDTO row(String id, String url, String ref) {
    return new DataSourceFormDTO(id, url, ref, null, null);
  }

  private List<String> errorsOf(
      DataSourceSecrets.Owner owner, DataSourceFormDTO row, List<DataSourceFormDTO> stored) {
    InvalidFormException refused =
        catchThrowableOfType(
            InvalidFormException.class,
            () -> withCluster.requireValid(owner, List.of(), List.of(row), stored));
    return refused == null ? List.of() : refused.getErrors();
  }

  @Test
  void givenAClusterAndAnotherOwnersSecretWhoseNameFitsThisOwner_whenSaved_thenRefused() {
    // Arrange: space p, id q-ds-r and space p-ds-q, id r both give p-ds-q-ds-r-<6>
    DataSourceFormDTO row = row("q-ds-r", URL, "p-ds-q-ds-r-abc123");

    // Act
    List<String> errors = errorsOf(DataSourceSecrets.Owner.ofSpace("p"), row, List.of());

    // Assert
    assertThat(errors).containsExactly("data_sources[0].secret_ref: not a Secret of this space");
  }

  @Test
  void givenAClusterAndAKeptSecretWithANewUrl_whenSaved_thenThePasswordIsAskedAgain() {
    // Arrange: a host the developer controls would be sent the stored password
    DataSourceFormDTO stored = row("hr", URL, "p-ds-hr-abc123");
    DataSourceFormDTO moved = row("hr", "jdbc:postgresql://elsewhere:5432/hr", "p-ds-hr-abc123");

    // Act
    List<String> errors = errorsOf(DataSourceSecrets.Owner.ofSpace("p"), moved, List.of(stored));

    // Assert
    assertThat(errors).containsExactly("data_sources[0].password: required again with a new url");
  }

  @Test
  void givenAClusterAndAModelsKeptKeyWithANewUrl_whenSaved_thenTheKeyIsAskedAgain() {
    // Arrange: the stored key would reach a new host
    LlmFormDTO stored =
        new LlmFormDTO("gpt", "openai", "https://api.openai.com/v1", "m", "p-llm-gpt-abc123", null);
    LlmFormDTO moved =
        new LlmFormDTO(
            "gpt", "openai", "https://elsewhere.example/v1", "m", "p-llm-gpt-abc123", null);

    // Act
    List<String> errors =
        withCluster.problems(DataSourceSecrets.Owner.ofSpace("p"), List.of(moved), List.of(stored));

    // Assert
    assertThat(errors).containsExactly("llms[0].api_key: required again with a new url");
  }

  @Test
  void givenNoClusterAndAModelsSecretOfThisOwner_whenSaved_thenKeptByItsName() {
    // Arrange: a backend without a cluster checks the name alone
    DataSourceSecrets headless =
        new DataSourceSecrets(null, new ClusterProperties(null), event -> {});
    LlmFormDTO row =
        new LlmFormDTO("gpt", "openai", "https://api.openai.com/v1", "m", "p-llm-gpt-abc123", null);
    LlmFormDTO dataSourcesName =
        new LlmFormDTO("gpt", "openai", "https://api.openai.com/v1", "m", "p-ds-gpt-abc123", null);

    // Act
    List<String> kept =
        headless.problems(DataSourceSecrets.Owner.ofSpace("p"), List.of(row), List.of());
    List<String> refused =
        headless.problems(
            DataSourceSecrets.Owner.ofSpace("p"), List.of(dataSourcesName), List.of());

    // Assert
    assertThat(kept).isEmpty();
    assertThat(refused).containsExactly("llms[0].secret_ref: not a Secret of this space");
  }

  @Test
  void givenAClusterAndTheStoredSecretAtTheSameUrl_whenSaved_thenKept() {
    // Arrange
    DataSourceFormDTO stored = row("hr", URL, "p-ds-hr-abc123");

    // Act
    List<String> errors = errorsOf(DataSourceSecrets.Owner.ofSpace("p"), stored, List.of(stored));

    // Assert
    assertThat(errors).isEmpty();
  }
}
