package com.reportix.aisolate.backend.domain.airlock;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.reportix.aisolate.config.AirlockDocument;
import com.reportix.aisolate.config.Llm;
import com.reportix.aisolate.config.Names;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;
import tools.jackson.databind.json.JsonMapper;

/**
 * One model of a space, an airlock or the install: {@code {id, provider, url, model}}, every field
 * required. An {@code openai} one may have an API key: write-only, as a data source's password, it
 * becomes a Secret and {@code secret_ref} its name ({@link DataSourceSecrets}). Only an {@code
 * openai} row names one: another provider's drops it, and its Secret goes once the save commits.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record LlmFormDTO(
    String id,
    String provider,
    String url,
    String model,
    String secretRef,
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String apiKey)
    implements SecretRow<LlmFormDTO> {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  public LlmFormDTO {
    id = strip(id);
    provider = strip(provider);
    url = strip(url);
    model = strip(model);
    secretRef = AirlockDocument.OPENAI.equals(provider) ? strip(secretRef) : "";
  }

  /** A document's model as a row: its Secret's name, if any, and no key. */
  public static LlmFormDTO of(Llm llm) {
    return new LlmFormDTO(llm.id(), llm.provider(), llm.url(), llm.model(), llm.secretRef(), null);
  }

  /** The rows as sent, none null. */
  public static List<LlmFormDTO> clean(List<LlmFormDTO> rows) {
    return rows == null ? List.of() : rows.stream().filter(Objects::nonNull).toList();
  }

  public static List<Llm> toLlms(List<LlmFormDTO> rows) {
    return rows.stream().map(LlmFormDTO::toLlm).toList();
  }

  /** The row as a document holds it: the key stays in its Secret. */
  public Llm toLlm() {
    return new Llm(id, provider, url, model, secretRef.isEmpty() ? null : secretRef);
  }

  @Override
  public boolean bringsCredentials() {
    return apiKey != null && !apiKey.isBlank();
  }

  @Override
  public LlmFormDTO withSecretRef(String ref) {
    return new LlmFormDTO(id, provider, url, model, ref, apiKey);
  }

  @Override
  public Map<String, String> secretData() {
    return Map.of("apiKey", apiKey.strip());
  }

  @Override
  public String listField() {
    return "llms";
  }

  @Override
  public String credentialField() {
    return "api_key";
  }

  @Override
  public String secretKind() {
    return "llm";
  }

  /** One message per problem, each naming its row and field: {@code llms[1].id: …}. */
  public static List<String> validate(List<LlmFormDTO> rows) {
    List<String> errors = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    for (int i = 0; i < rows.size(); i++) {
      LlmFormDTO row = rows.get(i);
      String at = "llms[" + i + "].";
      if (row.id().isEmpty()) {
        errors.add(at + "id: required");
      } else if (!Names.LLM_ID.matcher(row.id()).matches()) {
        errors.add(at + "id: " + Names.LLM_ID_RULE);
      } else if (!ids.add(row.id())) {
        errors.add(at + "id: " + row.id() + " is listed twice");
      }
      String provider = AirlockDocument.providerProblem(row.provider());
      if (row.provider().isEmpty()) {
        errors.add(at + "provider: required");
      } else if (provider != null) {
        errors.add(at + "provider: " + provider);
      } else if (row.bringsCredentials() && !AirlockDocument.OPENAI.equals(row.provider())) {
        errors.add(at + "api_key: an Ollama model takes no API key");
      }
      if (row.url().isEmpty()) {
        errors.add(at + "url: required");
      }
      DataSourceFormDTO.checkUrl(at + "url", row.url(), errors);
      if (row.model().isEmpty()) {
        errors.add(at + "model: required");
      }
    }
    return errors;
  }

  /** The row as the API returns it, so no key reaches a log line through it. */
  @Override
  public String toString() {
    return "LlmFormDTO" + JSON.writeValueAsString(this);
  }

  private static String strip(String s) {
    return s == null ? "" : s.strip();
  }
}
