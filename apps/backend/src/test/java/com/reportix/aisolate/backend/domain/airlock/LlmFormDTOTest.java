package com.reportix.aisolate.backend.domain.airlock;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class LlmFormDTOTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final TypeReference<List<LlmFormDTO>> ROWS = new TypeReference<>() {};

  private static List<LlmFormDTO> rows(String json) {
    return JSON.readValue(json, ROWS);
  }

  @Test
  void givenAnOpenAiRowWithAKey_whenStored_thenValidAndTheKeyLeavesNoTrace() {
    // Arrange
    LlmFormDTO row =
        rows("""
        [{"id":"gpt","provider":"openai","url":"https://api.openai.com/v1",
          "model":"gpt-4o-mini","api_key":"sk-s3cret"}]
        """)
            .getFirst();

    // Act
    List<String> errors = LlmFormDTO.validate(List.of(row));
    String stored = JSON.writeValueAsString(row);

    // Assert
    assertThat(errors).isEmpty();
    assertThat(row.bringsCredentials()).isTrue();
    assertThat(row.secretData()).containsExactly(Map.entry("apiKey", "sk-s3cret"));
    assertThat(stored).doesNotContain("sk-s3cret", "api_key");
    assertThat(row.toString()).doesNotContain("sk-s3cret");
    assertThat(row.toLlm().secretRef()).isNull();
  }

  @Test
  void givenAnOllamaRowNamingASecret_whenRead_thenItNamesNone() {
    // Arrange: a model switched from OpenAI-compatible to Ollama, sent back with its old Secret
    String json =
        """
        [{"id":"gpt","provider":"ollama","url":"http://ollama:11434","model":"gemma3",
          "secret_ref":"acme-llm-gpt-a1b2c3"}]
        """;

    // Act
    LlmFormDTO row = rows(json).getFirst();

    // Assert: its Secret goes once the save commits
    assertThat(row.secretRef()).isEmpty();
    assertThat(row.toLlm().secretRef()).isNull();
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "{\"id\":\"gpt\",\"provider\":\"openai\",\"url\":\"https://api.openai.com/v1\","
            + "\"model\":\"gpt-4o-mini\"}|",
        "{\"id\":\"gpt\",\"provider\":\"openai\",\"url\":\"http://ollama:11434/v1\","
            + "\"model\":\"granite4.1:8b\",\"api_key\":\"ollama\"}|",
        "{\"id\":\"titan\",\"provider\":\"bedrock\",\"url\":\"https://bedrock.example\","
            + "\"model\":\"c\"}|llms[0].provider: bedrock is not one of [ollama, openai]",
        "{\"id\":\"granite\",\"provider\":\"ollama\",\"url\":\"http://ollama:11434\",\"model\":\"g\",\"api_key\":\"sk\"}|llms[0].api_key:"
            + " an Ollama model takes no API key",
        "{\"id\":\"gpt\",\"provider\":\"openai\",\"url\":\"https://api.openai.com/v1?key=sk\",\"model\":\"m\"}|llms[0].url:"
            + " carries the parameter key; give the credentials in their own fields",
        "{\"id\":\"granite\",\"provider\":\"ollama\",\"url\":\"http://ollama:11434\","
            + "\"model\":\" \"}|llms[0].model: required",
        "{\"id\":\"granite\",\"provider\":\"ollama\",\"url\":\"http://o:11434\","
            + "\"model\":\"m\"},{\"id\":\"granite\",\"provider\":\"ollama\","
            + "\"url\":\"http://o:11434\",\"model\":\"m\"}|llms[1].id: granite is listed twice",
      })
  void givenRows_whenValidated_thenTheirProblemOrNone(String row, String expected) {
    // Arrange: one row, or several separated by commas
    List<LlmFormDTO> rows = rows("[" + row + "]");

    // Act
    List<String> errors = LlmFormDTO.validate(rows);

    // Assert
    assertThat(errors).isEqualTo(expected == null ? List.of() : List.of(expected));
  }

  @Test
  void givenUrlWithACredential_whenValidate_thenTheRowIsNamedAndTheUrlNeverEchoed() {
    // Arrange: which URLs carry one is UrlsTest's
    List<LlmFormDTO> rows =
        rows(
            """
            [{"id":"granite","provider":"ollama","url":"http://user:hunter2@ollama:11434",
              "model":"g"}]
            """);

    // Act
    List<String> errors = LlmFormDTO.validate(rows);

    // Assert
    assertThat(errors).singleElement().asString().startsWith("llms[0].url: carries");
    assertThat(errors.getFirst()).doesNotContain("hunter2");
  }
}
