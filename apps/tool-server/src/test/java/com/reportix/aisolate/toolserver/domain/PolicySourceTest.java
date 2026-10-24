package com.reportix.aisolate.toolserver.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.reportix.aisolate.config.Configuration;
import com.reportix.aisolate.config.ConfigurationLoader;
import com.reportix.aisolate.identity.Principal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PolicySourceTest {

  private static final Path FIXTURES = Path.of("src/test/resources/documents");
  private static final Configuration CONFIGURATION = ConfigurationLoader.load(FIXTURES);

  private final PolicySource source = new PolicySource(CONFIGURATION);

  @Test
  void givenLoadedRevision_whenFound_thenSpaceAndAirlockUsersAndItsTools() {
    // Arrange: the space lists ceo, hr-assistant employee
    String loadedRevision = CONFIGURATION.revision();

    // Act
    Policy policy = source.findPolicy("hr-assistant", loadedRevision).orElseThrow();

    // Assert
    assertThat(policy.id()).isEqualTo("hr-assistant");
    assertThat(policy.revision()).isEqualTo(CONFIGURATION.revision());
    assertThat(policy.ruleVersion()).isEqualTo(CONFIGURATION.revision());
    assertThat(policy.access().roles()).containsExactly("ceo", "employee");
    assertThat(policy.access().callers()).containsExactly("*");
    assertThat(policy.tools()).containsExactly("read_records", "aggregate_records");
  }

  @ParameterizedTest
  @CsvSource({"acme-chat, true", "partner-app, false"})
  void givenAirlockListingOneClient_whenAskedThroughAClient_thenAdmittedThroughThatOneAlone(
      String azp, boolean expected) {
    // Arrange: founders-finance admits the space's ceo, asking through the Acme chat only
    Policy policy = source.findPolicy("founders-finance", CONFIGURATION.revision()).orElseThrow();

    // Act
    boolean admitted = admitted(policy, azp, "ceo");

    // Assert: refused, the gate returns zero rows whatever the rules would give
    assertThat(policy.access().callers()).containsExactly("acme-chat");
    assertThat(admitted).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource({
    "ceo, founders-finance, true",
    "employee, hr-assistant, true",
    "employee, founders-finance, false",
    "founder, hr-assistant, false",
    "CEO, hr-assistant, false"
  })
  void givenRoleOfTheSpaceOrOfOneAirlock_whenPolicyChecked_thenAdmittedWhereTheUnionLists(
      String role, String airlock, boolean expected) {
    // Arrange: ceo is the space's role, employee hr-assistant's own, founder nobody's, roles match
    // exactly
    Policy policy = source.findPolicy(airlock, CONFIGURATION.revision()).orElseThrow();

    // Act
    boolean admitted = admitted(policy, "acme-chat", role);

    // Assert
    assertThat(admitted).isEqualTo(expected);
  }

  @ParameterizedTest
  @ValueSource(strings = {"ceo", "employee", "founder"})
  void givenNoRoleOnTheSpaceNorTheAirlock_whenPolicyChecked_thenRefused(String role)
      throws IOException {
    // Arrange: the space drops ceo; founders-finance lists no role of its own
    Map<String, String> files = new LinkedHashMap<>();
    try (Stream<Path> paths = Files.list(FIXTURES)) {
      for (Path file : paths.sorted().toList()) {
        files.put(
            file.getFileName().toString(),
            Files.readString(file).replace("roles: [ceo]", "roles: []"));
      }
    }
    Configuration closed = ConfigurationLoader.parse(files);
    Policy policy =
        new PolicySource(closed).findPolicy("founders-finance", closed.revision()).orElseThrow();

    // Act
    boolean admitted = admitted(policy, "acme-chat", role);

    // Assert: an empty union admits no one, not anyone
    assertThat(policy.access().roles()).isEmpty();
    assertThat(admitted).isFalse();
  }

  @Test
  void givenConfiguration_whenListPolicies_thenEveryServedAirlockOnce() {
    // Arrange: the fixtures hold three airlocks

    // Act
    var listed = source.listPolicies();

    // Assert
    assertThat(listed)
        .extracting(Policy::id)
        .containsExactlyInAnyOrder("hr-assistant", "founders-finance", "hr-archive");
  }

  @ParameterizedTest
  @CsvSource({"hr-assistant, other-revision", "nowhere, REVISION"})
  void givenOtherRevisionOrUnknownAirlock_whenFound_thenEmpty(String airlock, String revision) {
    // Arrange: a stale pod or a stranger names what this release does not hold
    String asked = revision.equals("REVISION") ? CONFIGURATION.revision() : revision;

    // Act
    var found = source.findPolicy(airlock, asked);

    // Assert: silent deny, exactly like an airlock AIsolate never served
    assertThat(found).isEmpty();
  }

  /** Gate 2 as the service runs it: the token's roles, through its client, against the policy. */
  private static boolean admitted(Policy policy, String azp, String role) {
    return Principal.of(
            Map.of("sub", "u-1", "azp", azp, "realm_access", Map.of("roles", List.of(role))),
            Principal.DEFAULT_ROLES_CLAIM)
        .admissionFailure(
            policy.access().roles(), policy.access().callers(), policy.access().clients())
        .isEmpty();
  }
}
