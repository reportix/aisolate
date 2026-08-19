package com.reportix.aisolate.airlock.domain.airlock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;

import com.reportix.aisolate.airlock.domain.chat.ExchangeTracing;
import com.reportix.aisolate.airlock.domain.chat.audit.AuditLog;
import com.reportix.aisolate.airlock.domain.chat.audit.AuditSink;
import com.reportix.aisolate.airlock.support.Documents;
import com.reportix.aisolate.identity.IdentityProperties;
import com.reportix.aisolate.identity.Principal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.oauth2.jwt.Jwt;
import tools.jackson.databind.json.JsonMapper;

/**
 * Discovery: each token sees the airlocks that admit its user (a user of their space or one of
 * their own) and its client, each with its endpoint and tools, none before its first deploy. In the
 * fixtures the space lists nora; hr-assistant lists eric as its own user; founders-finance lists no
 * user of its own.
 */
class AirlockAdmissionServiceTest {

  /** An airlock of hr-acme created before its first deploy, listing eric as its own user. */
  private static final String TRAVEL_DESK =
      """
      ---
      apiVersion: aisolate.dev/v1
      kind: Airlock
      metadata: {name: travel-desk}
      spec:
        space: hr-acme
        displayName: Travel desk
        access: {users: [eric@example.com], callers: ["*"]}
      """;

  /** An airlock of hr-acme listing eric, asked through the Acme chat alone. */
  private static final String CHAT_ONLY =
      """
      ---
      apiVersion: aisolate.dev/v1
      kind: Airlock
      metadata: {name: chat-only}
      spec:
        space: hr-acme
        displayName: Chat only
        access: {users: [eric@example.com], callers: [acme-chat]}
      """;

  /** An airlock of hr-acme a workflow tool asks as itself, with no user behind its token. */
  private static final String MACHINE =
      """
      ---
      apiVersion: aisolate.dev/v1
      kind: Airlock
      metadata: {name: machine}
      spec:
        space: hr-acme
        displayName: Machine
        access: {users: [], callers: [acme-chat], clients: [knime]}
        llm: qwen
        prompt: You answer the workflow tool.
      """;

  @Test
  void givenTheAuditBufferFullAndFailingClosed_whenAdmitting_thenAuditUnavailableBeforeAnyCheck(
      @TempDir Path buffer) throws Exception {
    // Arrange: the backend never answers, and one line fills the buffer
    AuditSink full =
        new AuditSink(
            URI.create("http://127.0.0.1:9/v1/audit/events"),
            buffer.resolve("token"),
            buffer,
            1,
            true,
            Duration.ofSeconds(1),
            Duration.ofSeconds(1));
    full.start();
    full.offer("{}");
    AirlockAdmissionService service =
        service(new AuditLog(JsonMapper.builder().build(), new SimpleMeterRegistry(), full, true));
    Jwt nora = jwt(token("nora@example.com"));

    try {
      // Act / Assert: an admitted question's line could not be kept
      assertThatThrownBy(() -> service.admit("hr-acme", "hr-assistant", nora, "q"))
          .isInstanceOf(AuditUnavailableException.class);
    } finally {
      full.close();
    }
  }

  private static AirlockAdmissionService service(String... moreDocuments) {
    return service(
        new AuditLog(JsonMapper.builder().build(), new SimpleMeterRegistry()), moreDocuments);
  }

  private static AirlockAdmissionService service(AuditLog auditLog, String... moreDocuments) {
    AirlockCatalog catalog = new DocumentsAirlockCatalog(Documents.parseWith(moreDocuments), null);
    IdentityProperties properties =
        new IdentityProperties("https://aisolate.example", Principal.DEFAULT_ROLES_CLAIM, null);
    return new AirlockAdmissionService(
        catalog, properties, auditLog, new ExchangeTracing(ObservationRegistry.NOOP));
  }

  private static Map<String, Object> token(String email) {
    return Map.of("sub", "u-1", "azp", "acme-chat", "email", email);
  }

  private static Jwt jwt(Map<String, Object> claims) {
    return Jwt.withTokenValue("t").header("alg", "none").claims(c -> c.putAll(claims)).build();
  }

  @Test
  void givenEmployeeOnOneAirlock_whenListed_thenThatAirlockAlone() {
    // Arrange: eric is hr-assistant's own user, not the space's
    AirlockAdmissionService admission = service();
    Map<String, Object> eric = token("eric@example.com");

    // Act
    List<AirlockSummaryDTO> airlocks = admission.listAdmittedAirlocks(eric);

    // Assert: the airlock admits him, so its endpoint and tools are his to see
    assertThat(airlocks)
        .containsExactly(
            new AirlockSummaryDTO(
                "hr-assistant",
                "hr-acme",
                "Acme HR assistant",
                "Answers people, pay and HR document questions.",
                "https://aisolate.example/v1/spaces/hr-acme/airlocks/hr-assistant/chat",
                List.of("read_records", "aggregate_records")));
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "eric@example.com|hr-assistant,travel-desk",
        "Nora@Example.Com|hr-assistant,founders-finance,travel-desk",
        "jasmine@example.com|''"
      })
  void givenUsersOfTheSpaceAndOfOneAirlock_whenListed_thenExactlyTheAirlocksTheUnionAdmits(
      String email, String expected) {
    // Arrange: nora inherits every airlock from the space, eric is listed on two of them
    AirlockAdmissionService admission = service(TRAVEL_DESK);

    // Act
    List<AirlockSummaryDTO> airlocks = admission.listAdmittedAirlocks(token(email));

    // Assert
    assertThat(airlocks)
        .extracting(AirlockSummaryDTO::id)
        .containsExactlyElementsOf(expected.isEmpty() ? List.of() : List.of(expected.split(",")));
  }

  @Test
  void givenClientTheAirlockDoesNotList_whenListed_thenHidden() {
    // Arrange: eric asks through partner-app; chat-only lists him but not that client
    AirlockAdmissionService admission = service(CHAT_ONLY);
    Map<String, Object> viaPartner =
        Map.of("sub", "u-1", "azp", "partner-app", "email", "eric@example.com");

    // Act
    List<AirlockSummaryDTO> airlocks = admission.listAdmittedAirlocks(viaPartner);

    // Assert: hidden from that client, listed through the chat, without tools before its deploy
    assertThat(airlocks).extracting(AirlockSummaryDTO::id).containsExactly("hr-assistant");
    assertThat(admission.listAdmittedAirlocks(token("eric@example.com")))
        .extracting(AirlockSummaryDTO::id, AirlockSummaryDTO::tools)
        .containsExactly(
            tuple("hr-assistant", List.of("read_records", "aggregate_records")),
            tuple("chat-only", List.of()));
  }

  @ParameterizedTest
  @CsvSource({"knime,machine", "n8n,''"})
  void givenTokenWithoutEmail_whenListed_thenOnlyTheAirlockListingItsClient(
      String azp, String expected) {
    // Arrange: no user behind the token, only its client
    AirlockAdmissionService admission = service(MACHINE);

    // Act
    List<AirlockSummaryDTO> airlocks =
        admission.listAdmittedAirlocks(Map.of("sub", "sa-1", "azp", azp));

    // Assert
    assertThat(airlocks)
        .extracting(AirlockSummaryDTO::id)
        .containsExactlyElementsOf(expected.isEmpty() ? List.of() : List.of(expected));
  }

  @Test
  void givenListedMachineClient_whenAdmitting_thenAdmitted() {
    // Arrange
    AirlockAdmissionService admission = service(MACHINE);

    // Act
    AirlockDefinition airlock = admission.admit("hr-acme", "machine", machine("knime"), "q");

    // Assert
    assertThat(airlock.access().clients()).containsExactly("knime");
  }

  @Test
  void givenUnlistedMachineClient_whenAdmitting_thenRefusedNamingTheClient() {
    // Arrange
    AirlockAdmissionService admission = service(MACHINE);

    // Act
    Throwable thrown =
        catchThrowable(() -> admission.admit("hr-acme", "machine", machine("n8n"), "q"));

    // Assert: the log line names the client, not a missing user
    assertThat(thrown)
        .isInstanceOf(ForbiddenException.class)
        .hasMessage("hr-acme/machine does not admit client n8n");
  }

  private static Jwt machine(String azp) {
    return jwt(Map.of("sub", "sa-1", "azp", azp));
  }
}
