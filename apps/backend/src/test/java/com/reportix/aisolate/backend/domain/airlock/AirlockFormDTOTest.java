package com.reportix.aisolate.backend.domain.airlock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.reportix.aisolate.backend.domain.space.SpaceFormDTO;
import com.reportix.aisolate.config.Names;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class AirlockFormDTOTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** Every section filled in, one data source with its credentials. */
  private static final String VALID =
      """
      {"id":"hr-assistant","name":"HR assistant","description":"The HR assistant.",
       "roles":["employee"],"callers":["*"],"clients":["acme-batch"],
       "llms":[{"id":"granite","provider":"ollama","url":"http://ollama:11434",
                "model":"granite4.1:8b"}],
       "data_sources":[{"id":"acme_hr","url":"jdbc:postgresql://acme-db:5432/acme_hr",
                        "username":"reader","password":"s3cret"}]}
      """;

  private static AirlockFormDTO form(String json) {
    return JSON.readValue(json, AirlockFormDTO.class);
  }

  /** {@link #VALID} with one field set to a value, or removed when the value is null. */
  private static AirlockFormDTO with(String field, String value) {
    ObjectNode node = (ObjectNode) JSON.readTree(VALID);
    if (value == null) {
      node.remove(field);
    } else if (value.startsWith("[")) {
      node.set(field, JSON.readTree(value));
    } else {
      node.put(field, value);
    }
    return JSON.treeToValue(node, AirlockFormDTO.class);
  }

  private static AirlockFormDTO form(String id, String name, String description) {
    return form(id, name, description, List.of("employee"), List.of("*"));
  }

  private static AirlockFormDTO form(
      String id, String name, String description, List<String> roles, List<String> callers) {
    return new AirlockFormDTO(id, name, description, roles, callers, null, null, null);
  }

  private static AirlockFormDTO withClients(List<String> clients) {
    return new AirlockFormDTO(
        "hr-app-1", "HR app 1", null, List.of(), List.of("*"), clients, null, null);
  }

  @Test
  void givenValidForm_whenSerialised_thenNoErrorsAndNoCredentialLeaves() {
    // Arrange
    AirlockFormDTO form = form(VALID);

    // Act
    List<String> errors = form.validate();
    JsonNode written = JSON.valueToTree(form);

    // Assert: snake_case throughout; the credentials are write-only
    assertThat(errors).isEmpty();
    assertThat(written.path("roles").valueStream().map(JsonNode::asString))
        .containsExactly("employee");
    assertThat(written.path("data_sources").path(0).path("secret_ref").asString()).isEmpty();
    assertThat(written.toString()).doesNotContain("s3cret", "reader", "username", "password");
    assertThat(form.toString()).doesNotContain("s3cret", "reader");
  }

  @Test
  void givenBadSlugAndBrokenRows_whenValidate_thenTheRowErrorsComeBackPrefixed() {
    // Arrange
    AirlockFormDTO form =
        new AirlockFormDTO(
            "HR App!",
            "",
            null,
            List.of("employee"),
            List.of("*"),
            null,
            List.of(
                new LlmFormDTO(
                    "granite", "mistral", "http://ollama:11434", "granite4.1:8b", null, null)),
            List.of(
                new DataSourceFormDTO("acme_hr", "jdbc:postgresql://db/hr", null, "reader", null)));

    // Act
    List<String> errors = form.validate();

    // Assert: the form's own error, then one error each from the row validators, prefixed with
    // their place; what the rows say is the row tests'
    assertThat(errors)
        .satisfiesExactly(
            id ->
                assertThat(id)
                    .isEqualTo(
                        "id: use 2 to 41 lowercase letters, digits or hyphens, starting with a"
                            + " letter and not ending with a hyphen"),
            llm -> assertThat(llm).startsWith("llms[0]."),
            dataSource -> assertThat(dataSource).startsWith("data_sources[0]."));
  }

  static Stream<Arguments> givenMalformedRolesOrCallers_whenValidate_thenExactlyItsError() {
    return Stream.of(
        arguments(List.of("employee", " "), List.of("*"), "roles: must not contain a blank role"),
        arguments(List.of("employee", "employee"), List.of("*"), "roles: lists employee twice"),
        // Among the roles a * admits no one; read as everyone it would widen access.
        arguments(
            List.of("*"),
            List.of("*"),
            "roles: * is no role: list each role; only callers take * for any"),
        arguments(
            List.of("employee"),
            List.of("acme-chat", ""),
            "callers: must not contain a blank client"),
        arguments(
            List.of("employee"),
            List.of("acme-chat", "acme-chat"),
            "callers: lists acme-chat twice"),
        // A role is matched exactly, so padding is refused, never stripped into another role
        arguments(List.of(" employee"), List.of("*"), "roles: \" employee\" has a space in it"),
        arguments(List.of("hr staff"), List.of("*"), "roles: \"hr staff\" has a space in it"),
        arguments(
            List.of("employee"), List.of("acme chat"), "callers: \"acme chat\" has a space in it"));
  }

  @ParameterizedTest
  @MethodSource
  void givenMalformedRolesOrCallers_whenValidate_thenExactlyItsError(
      List<String> roles, List<String> callers, String expected) {
    // Arrange: the loader refuses each of them in the documents
    AirlockFormDTO form = form("hr-app-1", "HR app 1", null, roles, callers);

    // Act
    List<String> errors = form.validate();

    // Assert
    assertThat(errors).containsExactly(expected);
  }

  static Stream<Arguments> givenMalformedClients_whenValidate_thenExactlyItsError() {
    return Stream.of(
        arguments(List.of("acme-batch", " "), "clients: must not contain a blank client id"),
        arguments(List.of("acme batch"), "clients: \"acme batch\" has a space in it"),
        arguments(List.of("acme-batch", "acme-batch"), "clients: lists acme-batch twice"),
        // Matched exactly: read as any client, a * would admit every token without an e-mail.
        arguments(List.of("*"), "clients: * is no wildcard: list each client id"));
  }

  @ParameterizedTest
  @MethodSource
  void givenMalformedClients_whenValidate_thenExactlyItsError(
      List<String> clients, String expected) {
    // Arrange
    AirlockFormDTO form = withClients(clients);

    // Act
    List<String> errors = form.validate();

    // Assert
    assertThat(errors).containsExactly(expected);
  }

  static Stream<Arguments> givenNoOrPaddedClients_whenFormRead_thenStrippedAndValid() {
    // None: a form saved before machine clients, or one that names none.
    return Stream.of(
        arguments(null, List.of()), arguments(List.of(" acme-batch\t"), List.of("acme-batch")));
  }

  @ParameterizedTest
  @MethodSource
  void givenNoOrPaddedClients_whenFormRead_thenStrippedAndValid(
      List<String> clients, List<String> expected) {
    // Arrange
    AirlockFormDTO form = withClients(clients);

    // Act
    List<String> errors = form.validate();

    // Assert
    assertThat(errors).isEmpty();
    assertThat(form.clients()).isEqualTo(expected);
  }

  @Test
  void givenLongNameInALongSpace_whenWithIdFromName_thenCutSoItsObjectsStillFit() {
    // Arrange: 22 characters of space leave 26 for the airlock, its v1 still fitting
    AirlockFormDTO form = form(null, "Acme HR assistant for employees in Europe", null);

    // Act
    String id = form.withIdFromName("acme-financial-holding", taken -> false).id();

    // Assert
    assertThat(id).isEqualTo("acme-hr-assistant-for-empl");
    assertThat(Names.clusterNameProblem("acme-financial-holding", id, "v1")).isNull();
    assertThat(Names.clusterNameProblem("acme-financial-holding", id + "o", "v1")).isNotNull();
  }

  @Test
  void givenRolesAndCallers_whenWithDefaultCallers_thenKept() {
    // Arrange
    AirlockFormDTO form = form("hr-app-1", "HR app 1", null, List.of("ceo"), List.of("hr-chat"));

    // Act
    AirlockFormDTO defaulted = form.withDefaultCallers();

    // Assert
    assertThat(defaulted).isEqualTo(form);
  }

  @Test
  void givenNoCallers_whenWithDefaultCallers_thenAnyCallerAndNothingElseChanged() {
    // Arrange: the form lists roles but says nothing of which clients may ask
    AirlockFormDTO form = form("open-app", "Open app", null, List.of("ceo"), List.of());

    // Act
    AirlockFormDTO defaulted = form.withDefaultCallers();

    // Assert
    assertThat(defaulted.callers()).containsExactly("*");
    assertThat(defaulted).isEqualTo(form.withCallers(List.of("*")));
  }

  @ParameterizedTest
  @CsvSource({
    "'  Payroll (v2)!  ', payroll-v2",
    "2026 payroll, airlock-2026-payroll",
    "HR, hr",
    "X, airlock-x",
    "Ü, airlock",
    "Payroll and compensation tools for Acme Financial Group,"
        + " payroll-and-compensation-tools-for-acme-f"
  })
  void givenName_whenIdFrom_thenAValidSlugOfIt(String name, String expected) {
    // Arrange / Act
    String id = AirlockFormDTO.deriveId(name, "airlock", AirlockFormDTO.MAX_ID, taken -> false);

    // Assert
    assertThat(id).isEqualTo(expected).matches(Names.SLUG);
  }

  @ParameterizedTest
  @CsvSource({"New, new-2", "  new!, new-2", "New space, new-space"})
  void givenNameNew_whenIdFrom_thenStepsPastTheCreatePagesPath(String name, String expected) {
    // Arrange: /spaces/new and /airlocks/new are the UI's create pages

    // Act
    String id = AirlockFormDTO.deriveId(name, "airlock", AirlockFormDTO.MAX_ID, taken -> false);

    // Assert
    assertThat(id).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource({
    "HR tools, hr-tools-3",
    "Payroll and compensation tools for Acme Financial Group,"
        + " payroll-and-compensation-tools-for-acme-2"
  })
  void givenTakenIds_whenIdFrom_thenTheFirstFreeSuffix(String name, String expected) {
    // Arrange
    Set<String> taken =
        Set.of("hr-tools", "hr-tools-2", "payroll-and-compensation-tools-for-acme-f");

    // Act
    String id = AirlockFormDTO.deriveId(name, "airlock", AirlockFormDTO.MAX_ID, taken::contains);

    // Assert
    assertThat(id).isEqualTo(expected).matches(Names.SLUG);
  }

  @ParameterizedTest
  @CsvSource({"hr-app-1, HR app", "'', ' '"})
  void givenIdOrBlankName_whenWithIdFromName_thenUnchanged(String id, String name) {
    // Arrange
    AirlockFormDTO form = form(id.isEmpty() ? null : id, name, null);

    // Act / Assert
    assertThat(form.withIdFromName("hr-acme", taken -> true)).isSameAs(form);
  }

  @ParameterizedTest
  @CsvSource({
    "space, Acme Financial Group, Acme Financial Group",
    "space, '', acme",
    "space, '  ', acme",
    "airlock, HR, HR",
    "airlock, '', hr",
    "airlock, '  ', hr"
  })
  void givenLabelOrNone_whenFormRead_thenValidAndNamedByItOrTheId(
      String kind, String label, String expected) {
    // Arrange / Act
    String name;
    List<String> errors;
    if (kind.equals("space")) {
      // The same rule on create and on update: both read the form.
      SpaceFormDTO space = new SpaceFormDTO("acme", label, null, null, null, null, null);
      name = space.name();
      errors = space.validate();
    } else {
      AirlockFormDTO airlock = form("hr", label, null);
      name = airlock.name();
      errors = airlock.validate();
    }

    // Assert
    assertThat(errors).isEmpty();
    assertThat(name).isEqualTo(expected);
  }
}
