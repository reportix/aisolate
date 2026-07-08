package com.reportix.aisolate.backend.domain.airlock;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class DataSourceFormDTOTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final TypeReference<List<DataSourceFormDTO>> ROWS = new TypeReference<>() {};

  private static List<DataSourceFormDTO> rows(String json) {
    return JSON.readValue(json, ROWS);
  }

  @Test
  void givenRowWithCredentials_whenStored_thenValidAndNoCredentialLeavesIt() {
    // Arrange
    DataSourceFormDTO row =
        rows("""
        [{"id":"acme_hr","url":"jdbc:postgresql://acme-db:5432/acme_hr",
          "username":"reader","password":"s3cret"}]
        """)
            .getFirst();

    // Act
    List<String> errors = DataSourceFormDTO.validate(List.of(row));
    String stored = JSON.writeValueAsString(row);

    // Assert
    assertThat(errors).isEmpty();
    assertThat(row.bringsCredentials()).isTrue();
    assertThat(stored).doesNotContain("reader", "s3cret", "username", "password");
    assertThat(row.toString()).doesNotContain("reader", "s3cret");
    assertThat(row.toDataSource().secretRef()).isNull();
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "[{\"url\":\"jdbc:postgresql://db/hr\",\"username\":\"r\",\"password\":\"p\"}]"
            + "|data_sources[0].id: required",
        "[{\"id\":\"Acme HR\",\"url\":\"jdbc:postgresql://db/hr\",\"username\":\"r\","
            + "\"password\":\"p\"}]|data_sources[0].id: 1 to 41 lowercase letters, digits, _ or -,"
            + " starting with a letter or digit",
        "[{\"id\":\"hr\",\"url\":\"jdbc:postgresql://db/hr\",\"secret_ref\":\"s-ds-hr-abc123\"},"
            + "{\"id\":\"hr\",\"url\":\"jdbc:postgresql://db/hr\",\"secret_ref\":\"s-ds-hr-def456\"}]|data_sources[1].id:"
            + " hr is listed twice",
        "[{\"id\":\"hr\",\"username\":\"r\",\"password\":\"p\"}]|data_sources[0].url: required",
        "[{\"id\":\"hr\",\"url\":\"db:5432/hr\",\"username\":\"r\",\"password\":\"p\"}]"
            + "|data_sources[0].url: has no host: expected scheme://host[:port]",
        "[{\"id\":\"hr\",\"url\":\"jdbc:postgresql://db/hr\",\"password\":\"p\"}]"
            + "|data_sources[0].username: required with password",
        "[{\"id\":\"hr\",\"url\":\"jdbc:postgresql://db/hr\",\"username\":\"r\"}]"
            + "|data_sources[0].password: required with url"
      })
  void givenOneBrokenRow_whenValidate_thenExactlyItsError(String json, String expected) {
    // Arrange
    List<DataSourceFormDTO> rows = rows(json);

    // Act
    List<String> errors = DataSourceFormDTO.validate(rows);

    // Assert
    assertThat(errors).containsExactly(expected);
  }

  @Test
  void givenUrlWithACredential_whenValidate_thenTheRowIsNamedAndTheUrlNeverEchoed() {
    // Arrange: which URLs carry one is UrlsTest's
    String url = "jdbc:postgresql://reader:hunter2@acme-db:5432/hr";
    List<DataSourceFormDTO> rows =
        List.of(new DataSourceFormDTO("hr", url, null, "reader", "s3cret"));

    // Act
    List<String> errors = DataSourceFormDTO.validate(rows);

    // Assert
    assertThat(errors).singleElement().asString().startsWith("data_sources[0].url: carries");
    assertThat(errors.getFirst()).doesNotContain("hunter2");
  }

  @Test
  void givenSecretAlreadyThere_whenSavedWithoutCredentials_thenValid() {
    // Arrange: an edit with a blank password keeps the Secret
    DataSourceFormDTO row =
        new DataSourceFormDTO(
            "acme_hr",
            "jdbc:postgresql://acme-db:5432/acme_hr",
            "hr-acme-ds-acme-hr-a1b2c3",
            null,
            " ");

    // Act
    List<String> errors = DataSourceFormDTO.validate(List.of(row));

    // Assert
    assertThat(errors).isEmpty();
    assertThat(row.bringsCredentials()).isFalse();
  }
}
