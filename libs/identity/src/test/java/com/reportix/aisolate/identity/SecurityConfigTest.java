package com.reportix.aisolate.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class SecurityConfigTest {

  private static List<String> authoritiesOf(String... roles) {
    Jwt jwt =
        Jwt.withTokenValue("t")
            .header("alg", "RS256")
            .claim("realm_access", Map.of("roles", List.of(roles)))
            .build();
    return SecurityConfig.rolesFromClaimPath("realm_access.roles")
        .convert(jwt)
        .getAuthorities()
        .stream()
        .map(GrantedAuthority::getAuthority)
        // Spring Security adds the factor the token proves; the roles are what is under test.
        .filter(authority -> authority.startsWith("ROLE_"))
        .toList();
  }

  @Test
  void givenTheAIsolateRolesAndTheDroppedDeveloperRole_whenConverted_thenTheirAuthoritiesOnly() {
    // Arrange / Act: a space names its developers; the old realm role grants nothing
    List<String> authorities =
        authoritiesOf("aisolate-developer", "aisolate-admin", "aisolate-auditor", "ceo");

    // Assert
    assertThat(authorities)
        .containsExactlyInAnyOrder("ROLE_AISOLATE_ADMIN", "ROLE_AISOLATE_AUDITOR");
  }

  @ParameterizedTest
  @ValueSource(strings = {"AIsolate-Admin", "aisolate_auditor", "AISOLATE_ADMIN"})
  void givenARoleThatOnlyNormalisesToAnAIsolateRole_whenConverted_thenNoAuthority(String role) {
    // Arrange / Act
    List<String> authorities = authoritiesOf(role);

    // Assert
    assertThat(authorities).isEmpty();
  }

  static Stream<Arguments> subjects() {
    return Stream.of(
        Arguments.of(null, true),
        Arguments.of(" ", true),
        Arguments.of(42, true),
        Arguments.of("u-1", false));
  }

  @ParameterizedTest
  @MethodSource("subjects")
  void givenASubClaim_whenTheTokenIsValidated_thenRefusedUnlessANonBlankString(
      Object sub, boolean refused) {
    // Arrange
    Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "RS256").claim("aud", "aisolate");
    if (sub != null) {
      builder.claim("sub", sub);
    }

    // Act
    boolean hasErrors = SecurityConfig.subjectRequired().validate(builder.build()).hasErrors();

    // Assert
    assertThat(hasErrors).isEqualTo(refused);
  }

  @Test
  void givenNoAudience_whenTheSecurityIsBuilt_thenTheAppRefusesToStart() {
    // Arrange
    OAuth2ResourceServerProperties resourceServer = new OAuth2ResourceServerProperties();

    // Act
    Throwable thrown = catchThrowable(() -> SecurityConfig.requireAudience(resourceServer));

    // Assert
    assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessageContaining("audiences");
  }
}
