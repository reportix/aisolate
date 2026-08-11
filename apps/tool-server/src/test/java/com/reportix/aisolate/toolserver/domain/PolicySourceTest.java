package com.reportix.aisolate.toolserver.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.reportix.aisolate.config.Configuration;
import com.reportix.aisolate.config.ConfigurationLoader;
import com.reportix.aisolate.identity.Principal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
    // Arrange: the space lists nora, hr-assistant eric
    String loadedRevision = CONFIGURATION.revision();

    // Act
    Policy policy = source.findPolicy("hr-assistant", loadedRevision).orElseThrow();

    // Assert
    assertThat(policy.id()).isEqualTo("hr-assistant");
    assertThat(policy.revision()).isEqualTo(CONFIGURATION.revision());
    assertThat(policy.ruleVersion()).isEqualTo(CONFIGURATION.revision());
    assertThat(policy.access().users()).containsExactly("nora@example.com", "eric@example.com");
    assertThat(policy.access().callers()).containsExactly("*");
    assertThat(policy.tools()).containsExactly("read_records", "aggregate_records");
  }

  @ParameterizedTest
  @CsvSource({"acme-chat, true", "partner-app, false"})
  void givenAirlockListingOneClient_whenAskedThroughAClient_thenAdmittedThroughThatOneAlone(
      String azp, boolean expected) {
    // Arrange: founders-finance admits the space's nora, asking through the Acme chat only
    Policy policy = source.findPolicy("founders-finance", CONFIGURATION.revision()).orElseThrow();

    // Act
    boolean admitted = admitted(policy, azp, "nora@example.com");

    // Assert: refused, the gate returns zero rows whatever the rules would give
    assertThat(policy.access().callers()).containsExactly("acme-chat");
    assertThat(admitted).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource({
    "nora@example.com, founders-finance, true",
    "Eric@Example.Com, hr-assistant, true",
    "eric@example.com, founders-finance, false",
    "leo@example.com, hr-assistant, false"
  })
  void givenUserOfTheSpaceOrOfOneAirlock_whenPolicyChecked_thenAdmittedWhereTheUnionLists(
      String email, String airlock, boolean expected) {
    // Arrange: nora is the space's user, eric hr-assistant's own, leo nobody's
    Policy policy = source.findPolicy(airlock, CONFIGURATION.revision()).orElseThrow();

    // Act
    boolean admitted = admitted(policy, "acme-chat", email);

    // Assert
    assertThat(admitted).isEqualTo(expected);
  }

  @ParameterizedTest
  @ValueSource(strings = {"nora@example.com", "eric@example.com", "leo@example.com"})
  void givenNoUserOnTheSpaceNorTheAirlock_whenPolicyChecked_thenRefused(String email)
      throws IOException {
    // Arrange: the space drops nora; founders-finance lists no user of its own
    Map<String, String> files = new LinkedHashMap<>();
    try (Stream<Path> paths = Files.list(FIXTURES)) {
      for (Path file : paths.sorted().toList()) {
        files.put(
            file.getFileName().toString(),
            Files.readString(file).replace("users: [nora@example.com]", "users: []"));
      }
    }
    Configuration closed = ConfigurationLoader.parse(files);
    Policy policy =
        new PolicySource(closed).findPolicy("founders-finance", closed.revision()).orElseThrow();

    // Act
    boolean admitted = admitted(policy, "acme-chat", email);

    // Assert: an empty union admits no one, not anyone
    assertThat(policy.access().users()).isEmpty();
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

  /** Gate 2 as the service runs it: the token's user, through its client, against the policy. */
  private static boolean admitted(Policy policy, String azp, String email) {
    return Principal.of(
            Map.of("sub", "u-1", "azp", azp, "email", email), Principal.DEFAULT_ROLES_CLAIM)
        .admissionFailure(
            policy.access().users(), policy.access().callers(), policy.access().clients())
        .isEmpty();
  }
}
