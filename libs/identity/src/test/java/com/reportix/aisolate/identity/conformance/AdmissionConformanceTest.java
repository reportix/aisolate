package com.reportix.aisolate.identity.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.reportix.aisolate.identity.Principal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code conformance/cases/admission/airlock.json}: gate 2 and gate 5's admission over the
 * validated token's claims, shared with the Python runtimes.
 */
class AdmissionConformanceTest {

  private static final JsonMapper JSON =
      JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

  private static final JsonNode FILE = read();

  static Stream<Arguments> cases() {
    List<Arguments> cases = new ArrayList<>();
    FILE.path("cases").forEach(c -> cases.add(arguments(named(c.path("id").asString(), c))));
    return cases.stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void givenClaimsAndAirlockLists_whenAdmitted_thenAdmittedOrRefusedForTheExpectedReason(
      JsonNode admission) {
    // Arrange
    JsonNode airlock = FILE.path("airlocks").path(admission.path("airlock").asString());
    Set<String> roles = new LinkedHashSet<>(strings(FILE.path("space").path("roles")));
    roles.addAll(strings(airlock.path("roles")));
    List<String> callers = strings(airlock.path("callers"));
    List<String> clients = strings(airlock.path("clients"));
    Map<String, Object> claims =
        JSON.treeToValue(admission.path("claims"), new TypeReference<Map<String, Object>>() {});
    Principal principal = Principal.of(claims, Principal.DEFAULT_ROLES_CLAIM);

    // Act
    Optional<Principal.AdmissionFailure> failure =
        principal.admissionFailure(List.copyOf(roles), callers, clients);

    // Assert
    JsonNode expect = admission.path("expect");
    assertThat(failure.isEmpty()).isEqualTo(expect.path("admitted").asBoolean());
    if (failure.isPresent()) {
      assertThat(failure.get().name().toLowerCase(Locale.ROOT))
          .isEqualTo(expect.path("reason").asString());
    }
  }

  private static List<String> strings(JsonNode array) {
    List<String> strings = new ArrayList<>();
    array.forEach(item -> strings.add(item.asString()));
    return strings;
  }

  private static JsonNode read() {
    String cases = System.getProperty("aisolate.conformance.dir");
    if (cases == null) {
      throw new IllegalStateException("the build passes aisolate.conformance.dir");
    }
    return JSON.readTree(Path.of(cases, "admission", "airlock.json").toFile());
  }
}
