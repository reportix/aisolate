package com.reportix.aisolate.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class PrincipalTest {

  private static final Map<String, Object> KEYCLOAK_CLAIMS =
      Map.of(
          "sub", "u-1",
          "email", "eric@example.com",
          "azp", "acme-chat",
          "realm_access", Map.of("roles", List.of("employee", "offline_access")),
          "roles", "single");

  private static final List<String> USERS = List.of("eric@example.com", "nora@example.com");

  @ParameterizedTest
  @CsvSource({
    "realm_access.roles, 'employee,offline_access'",
    "roles, single",
    "missing.path, ''",
  })
  void givenClaims_whenRolesAtPath_thenListOrEmpty(String path, String expected) {
    // Arrange
    List<String> want = expected.isEmpty() ? List.of() : List.of(expected.split(","));

    // Act
    List<String> roles = Principal.roles(KEYCLOAK_CLAIMS, path);

    // Assert
    assertThat(roles).containsExactlyElementsOf(want);
  }

  @Test
  void givenKeycloakClaims_whenOf_thenSubRolesCallerAndEmail() {
    // Arrange / Act
    Principal principal = Principal.of(KEYCLOAK_CLAIMS, "realm_access.roles");

    // Assert
    assertThat(principal.sub()).isEqualTo("u-1");
    assertThat(principal.roles()).containsExactlyInAnyOrder("employee", "offline_access");
    assertThat(principal.azp()).isEqualTo("acme-chat");
    assertThat(principal.email()).isEqualTo("eric@example.com");
  }

  @Test
  void givenNoSub_whenOf_thenRejected() {
    // Arrange / Act
    Throwable thrown =
        catchThrowable(() -> Principal.of(Map.of("email", "x"), "realm_access.roles"));

    // Assert
    assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void givenIdentityJoin_whenOf_thenAttributesComeOnlyFromClaimsAndJoin() {
    // Arrange / Act
    Principal principal = Principal.of(KEYCLOAK_CLAIMS, "realm_access.roles", "employee_id", 2);

    // Assert
    assertThat(principal.attribute("employee_id")).isEqualTo(2);
    assertThat(principal.attribute("email")).isEqualTo("eric@example.com");
    assertThat(principal.attribute("badge")).isNull();
    assertThat(principal.attribute(null)).isNull();
  }

  /** Gate 2's whole decision: the airlock pod and the tool server only call it. */
  static Stream<Arguments> givenClaimsUsersAndCallers_whenAdmissionFailure_thenExpected() {
    List<String> anyCaller = List.of("*");
    // the identity provider keeps the case the user typed
    Map<String, Object> upperCase =
        Map.of("sub", "u-1", "email", "Eric@Example.Com", "azp", "acme-chat");
    Map<String, Object> withoutEmail =
        Map.of(
            "sub", "u-1",
            "azp", "acme-chat",
            "realm_access", Map.of("roles", List.of("employee")));
    Map<String, Object> withoutAzp =
        Map.of(
            "sub", "u-1",
            "email", "eric@example.com",
            "realm_access", Map.of("roles", List.of("employee")));
    return Stream.of(
        arguments(named("caller listed", KEYCLOAK_CLAIMS), USERS, List.of("acme-chat"), null),
        arguments(named("any caller", KEYCLOAK_CLAIMS), USERS, anyCaller, null),
        arguments(named("e-mail in another case", upperCase), USERS, anyCaller, null),
        arguments(
            named("email not among users", KEYCLOAK_CLAIMS),
            List.of("nora@example.com"),
            anyCaller,
            Principal.AdmissionFailure.USER),
        arguments(
            named("no email claim", withoutEmail),
            USERS,
            anyCaller,
            Principal.AdmissionFailure.USER),
        arguments(
            named("caller not listed", KEYCLOAK_CLAIMS),
            USERS,
            List.of("other-client"),
            Principal.AdmissionFailure.CLIENT),
        arguments(
            named("no azp claim", withoutAzp), USERS, anyCaller, Principal.AdmissionFailure.CLIENT),
        // the user is looked at first: when both fail, it is the user
        arguments(
            named("neither listed", KEYCLOAK_CLAIMS),
            List.of("nora@example.com"),
            List.of("other-client"),
            Principal.AdmissionFailure.USER));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource
  void givenClaimsUsersAndCallers_whenAdmissionFailure_thenExpected(
      Map<String, Object> claims,
      List<String> users,
      List<String> callers,
      Principal.AdmissionFailure expected) {
    // Arrange
    Principal principal = Principal.of(claims, "realm_access.roles");

    // Act
    Optional<Principal.AdmissionFailure> failure =
        principal.admissionFailure(users, callers, List.of());

    // Assert
    assertThat(failure).isEqualTo(Optional.ofNullable(expected));
  }
}
