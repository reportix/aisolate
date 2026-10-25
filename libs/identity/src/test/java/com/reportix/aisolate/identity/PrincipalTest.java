package com.reportix.aisolate.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PrincipalTest {

  private static final Map<String, Object> KEYCLOAK_CLAIMS =
      Map.of(
          "sub", "u-1",
          "email", "eric@example.com",
          "azp", "acme-chat",
          "realm_access", Map.of("roles", List.of("employee", "offline_access")),
          "roles", "single");

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
}
