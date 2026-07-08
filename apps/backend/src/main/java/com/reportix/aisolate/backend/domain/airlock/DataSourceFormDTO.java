package com.reportix.aisolate.backend.domain.airlock;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.reportix.aisolate.config.DataSource;
import com.reportix.aisolate.config.Names;
import com.reportix.aisolate.config.Urls;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;
import tools.jackson.databind.json.JsonMapper;

/**
 * One database a space or an airlock defines, by the id its code asks for. Username and password
 * are write-only: Jackson never serialises them, so they reach neither a stored definition nor a
 * response; a password becomes a Secret, {@code secret_ref} its name ({@link DataSourceSecrets}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DataSourceFormDTO(
    String id,
    String url,
    String secretRef,
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String username,
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String password)
    implements SecretRow<DataSourceFormDTO> {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  public DataSourceFormDTO {
    id = strip(id);
    url = strip(url);
    secretRef = strip(secretRef);
  }

  /** A typed password replaces the row's Secret; a blank one keeps it. */
  @Override
  public boolean bringsCredentials() {
    return password != null && !password.isBlank();
  }

  @Override
  public DataSourceFormDTO withSecretRef(String ref) {
    return new DataSourceFormDTO(id, url, ref, username, password);
  }

  @Override
  public Map<String, String> secretData() {
    return Map.of("username", username, "password", password);
  }

  @Override
  public String listField() {
    return "data_sources";
  }

  @Override
  public String credentialField() {
    return "password";
  }

  @Override
  public String secretKind() {
    return "ds";
  }

  /** The row as a document holds it: the credentials stay in its Secret. */
  public DataSource toDataSource() {
    return new DataSource(id, url.isEmpty() ? null : url, secretRef.isEmpty() ? null : secretRef);
  }

  /** One message per problem, each naming its row and field, never a value it found. */
  public static List<String> validate(List<DataSourceFormDTO> rows) {
    List<String> errors = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    for (int i = 0; i < rows.size(); i++) {
      DataSourceFormDTO row = rows.get(i);
      String at = "data_sources[" + i + "].";
      if (row.id().isEmpty()) {
        errors.add(at + "id: required");
      } else if (!Names.DATA_SOURCE_ID.matcher(row.id()).matches()) {
        errors.add(at + "id: " + Names.DATA_SOURCE_ID_RULE);
      } else if (!ids.add(row.id())) {
        errors.add(at + "id: " + row.id() + " is listed twice");
      }
      if (row.url().isEmpty()) {
        errors.add(at + "url: required");
      }
      checkUrl(at + "url", row.url(), errors);
      if (row.bringsCredentials()) {
        if (row.username() == null || row.username().isBlank()) {
          errors.add(at + "username: required with password");
        }
      } else if (row.secretRef().isEmpty() && !row.url().isEmpty()) {
        errors.add(at + "password: required with url");
      }
    }
    return errors;
  }

  public static void checkUrl(String field, String url, List<String> errors) {
    String problem = url.isEmpty() ? null : Urls.problem(url);
    if (problem != null) {
      errors.add(field + ": " + problem);
    }
  }

  /** The row as the API returns it, so no credential reaches a log line through it. */
  @Override
  public String toString() {
    return "DataSourceFormDTO" + JSON.writeValueAsString(this);
  }

  private static String strip(String s) {
    return s == null ? "" : s.strip();
  }
}
