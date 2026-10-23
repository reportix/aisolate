package com.reportix.aisolate.backend.domain.airlock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.reportix.aisolate.backend.domain.credential.CredentialRepository;
import com.reportix.aisolate.backend.infrastructure.cluster.ClusterProperties;
import com.reportix.aisolate.backend.infrastructure.cluster.Credentials;
import com.reportix.aisolate.backend.infrastructure.crypto.CredentialCipher;
import com.reportix.aisolate.backend.infrastructure.crypto.CredentialProperties;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.json.JsonMapper;

class DataSourceSecretsTest {

  private static final String URL = "jdbc:postgresql://acme-db:5432/hr";

  private final DataSourceSecrets withCluster =
      new DataSourceSecrets(null, new ClusterProperties("aisolate"), event -> {}, null, null, null);

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
        new DataSourceSecrets(null, new ClusterProperties(null), event -> {}, null, null, null);
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
  void givenARowThatCannotBeInserted_whenStored_thenTheSecretGoesAndTheFailureIsNotAFormError() {
    // Arrange
    Credentials credentials = mock(Credentials.class);
    CredentialRepository sealedStore = mock(CredentialRepository.class);
    CredentialProperties props =
        new CredentialProperties(
            "test-credentials-key-of-at-least-32-chars", Duration.ofMinutes(5), List.of());
    DataSourceSecrets secrets =
        new DataSourceSecrets(
            credentials,
            new ClusterProperties("aisolate"),
            event -> {},
            sealedStore,
            new CredentialCipher(props, new ClusterProperties("aisolate")),
            JsonMapper.builder().build());
    when(credentials.create(any(), anyString(), anyMap())).thenReturn("p-ds-hr-abc123");
    DataAccessException down = new DataAccessResourceFailureException("db down");
    doThrow(down).when(sealedStore).insert(any());
    DataSourceFormDTO typed = new DataSourceFormDTO("hr", URL, "", "reader", "hunter2");

    // Act
    Throwable failed =
        catchThrowable(
            () ->
                secrets.storeIfAny(
                    DataSourceSecrets.Owner.ofSpace("p"),
                    List.of(typed),
                    "saved",
                    () ->
                        new ConfigMapBuilder()
                            .withNewMetadata()
                            .withName("p")
                            .endMetadata()
                            .build(),
                    refs -> "written"));

    // Assert
    assertThat(failed).isSameAs(down);
    verify(credentials).delete("p-ds-hr-abc123");
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
