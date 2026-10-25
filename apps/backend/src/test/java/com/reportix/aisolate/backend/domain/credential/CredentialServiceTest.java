package com.reportix.aisolate.backend.domain.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.reportix.aisolate.backend.domain.airlock.AirlockRepository;
import com.reportix.aisolate.backend.infrastructure.cluster.ClusterProperties;
import com.reportix.aisolate.backend.infrastructure.crypto.CredentialCipher;
import com.reportix.aisolate.backend.infrastructure.crypto.CredentialProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class CredentialServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");
  private static final String REF = "space-a-ds-b";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final CredentialRepository rows = mock(CredentialRepository.class);
  private final AirlockRepository airlocks = mock(AirlockRepository.class);
  private final CredentialProperties properties =
      new CredentialProperties(
          "test-credentials-key-of-at-least-32-chars",
          Duration.ofMinutes(5),
          List.of("aisolate-ui"));
  private final CredentialCipher cipher =
      new CredentialCipher(properties, new ClusterProperties(null));
  private final CredentialService service =
      new CredentialService(
          rows, cipher, properties, airlocks, JSON, Clock.fixed(NOW, ZoneOffset.UTC));

  private static Map<String, Object> claims(Object authTime) {
    return Jwt.withTokenValue("t")
        .header("alg", "none")
        .claim("email", "andrew@example.com")
        .claim("azp", "aisolate-ui")
        .claim("auth_time", authTime)
        .build()
        .getClaims();
  }

  private static Map<String, Object> claimsFrom(Object azp) {
    return Jwt.withTokenValue("t")
        .header("alg", "none")
        .claim("email", "andrew@example.com")
        .claim("azp", azp)
        .claim("auth_time", NOW.minusSeconds(10).getEpochSecond())
        .build()
        .getClaims();
  }

  private static Map<String, Object> claims() {
    return Jwt.withTokenValue("t")
        .header("alg", "none")
        .claim("email", "andrew@example.com")
        .claim("azp", "aisolate-ui")
        .build()
        .getClaims();
  }

  private static Map<String, Object> signedInSecondsAgo(long seconds) {
    return claims(NOW.minusSeconds(seconds).getEpochSecond());
  }

  private CredentialRepository.Row sealedRow(String aad, Map<String, String> values) {
    return sealedRow(aad, JSON.writeValueAsBytes(new TreeMap<>(values)));
  }

  private CredentialRepository.Row sealedRow(String aad, byte[] plain) {
    CredentialCipher.Sealed sealed = cipher.seal(aad, plain);
    return new CredentialRepository.Row(
        REF,
        "space",
        "space-a",
        "ds",
        "b",
        sealed.keyId(),
        sealed.nonce(),
        sealed.ciphertext(),
        "andrew");
  }

  private CredentialException refused(String ref, Map<String, Object> claims) {
    return catchThrowableOfType(CredentialException.class, () -> service.reveal(ref, claims));
  }

  private void verifyRefusal(String reason) {
    verify(rows)
        .record(
            argThat(
                e ->
                    e.action().equals("reveal_refused")
                        && e.reason().equals(reason)
                        && e.actor().equals("andrew@example.com")));
  }

  @Test
  void givenAnUnreferencedRef_whenRevealed_then404AndRefusalRecorded() {
    // Arrange
    when(airlocks.isSecretReferenced(REF)).thenReturn(false);

    // Act
    CredentialException e = refused(REF, signedInSecondsAgo(10));

    // Assert
    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(e.getReason()).isEqualTo("unknown");
    assertThat(e.getMessage()).isEqualTo("unknown credential");
    verifyRefusal("unknown");
  }

  @ParameterizedTest
  @ValueSource(strings = {"x' OR 1=1", "a b", ""})
  void givenAnOddRef_whenRevealed_then404(String ref) {
    // Arrange: no row names it, the mock's default

    // Act
    CredentialException e = refused(ref, signedInSecondsAgo(10));

    // Assert
    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(e.getReason()).isEqualTo("unknown");
  }

  static Stream<Arguments> tokensNotFromTheUi() {
    Map<String, Object> noAzp =
        Jwt.withTokenValue("t")
            .header("alg", "none")
            .claim("email", "andrew@example.com")
            .claim("auth_time", NOW.minusSeconds(10).getEpochSecond())
            .build()
            .getClaims();
    return Stream.of(
        arguments(named("the CLI's", claimsFrom("aisolate-cli"))),
        arguments(named("one without azp", noAzp)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("tokensNotFromTheUi")
  void givenATokenNotFromTheUi_whenRevealed_then403WrongClientRecorded(Map<String, Object> claims) {
    // Arrange
    when(airlocks.isSecretReferenced(REF)).thenReturn(true);

    // Act
    CredentialException e = refused(REF, claims);

    // Assert
    assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(e.getReason()).isEqualTo("wrong_client");
    assertThat(e.getMessage()).isEqualTo("not from the AIsolate UI");
    verifyRefusal("wrong_client");
  }

  static Stream<Arguments> loginsTooOldOrUnknown() {
    return Stream.of(
        arguments(named("no auth_time", claims()), "no_auth_time"),
        arguments(named("auth_time as a string", claims("1761300000")), "no_auth_time"),
        arguments(
            named("a non-finite auth_time", claims(Double.POSITIVE_INFINITY)), "no_auth_time"),
        arguments(named("a login 301 s ago", signedInSecondsAgo(301)), "stale_login"),
        arguments(named("auth_time beyond Instant's range", claims(Long.MAX_VALUE)), "stale_login"),
        arguments(named("ten minutes in the future", signedInSecondsAgo(-600)), "stale_login"),
        arguments(named("61 s in the future", signedInSecondsAgo(-61)), "stale_login"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("loginsTooOldOrUnknown")
  void givenALoginTooOldOrUnknown_whenRevealed_then403ForItsReasonRecorded(
      Map<String, Object> claims, String reason) {
    // Arrange
    when(airlocks.isSecretReferenced(REF)).thenReturn(true);

    // Act
    CredentialException e = refused(REF, claims);

    // Assert
    assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(e.getReason()).isEqualTo(reason);
    assertThat(e.getMessage())
        .isEqualTo(
            Map.of(
                    "no_auth_time", "the identity provider sends no auth_time claim",
                    "stale_login", "sign in again to reveal")
                .get(reason));
    verifyRefusal(reason);
  }

  static Stream<Arguments> freshLogins() {
    return Stream.of(
        arguments(named("a login 10 s ago", signedInSecondsAgo(10))),
        arguments(named("60 s in the future, within the skew", signedInSecondsAgo(-60))),
        arguments(
            named(
                "a fractional auth_time 30 s ago",
                claims((double) NOW.minusSeconds(30).getEpochSecond()))));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("freshLogins")
  void givenAFreshLoginAndARow_whenRevealed_thenValuesAndRevealedRecorded(
      Map<String, Object> claims) {
    // Arrange
    when(airlocks.isSecretReferenced(REF)).thenReturn(true);
    when(rows.find(REF))
        .thenReturn(
            Optional.of(sealedRow(REF, Map.of("username", "reader", "password", "hunter2"))));

    // Act
    CredentialService.Revealed revealed = service.reveal(REF, claims);

    // Assert
    assertThat(revealed.values()).isEqualTo(Map.of("username", "reader", "password", "hunter2"));
    verify(rows)
        .record(
            argThat(
                e ->
                    e.action().equals("revealed")
                        && e.actor().equals("andrew@example.com")
                        && e.secretRef().equals(REF)));
  }

  @Test
  void givenARowUnderAnotherKey_whenRevealed_then409KeyChanged(CapturedOutput output) {
    // Arrange
    when(airlocks.isSecretReferenced(REF)).thenReturn(true);
    CredentialRepository.Row row = sealedRow(REF, Map.of("apiKey", "sk-1"));
    when(rows.find(REF))
        .thenReturn(
            Optional.of(
                new CredentialRepository.Row(
                    REF,
                    row.ownerKind(),
                    row.owner(),
                    row.kind(),
                    row.itemId(),
                    "00000000",
                    row.nonce(),
                    row.ciphertext(),
                    row.createdBy())));

    // Act
    CredentialException e = refused(REF, signedInSecondsAgo(10));

    // Assert
    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(e.getReason()).isEqualTo("key_changed");
    assertThat(e.getMessage()).isEqualTo("stored under another key");
    assertThat(output).contains("00000000").contains(cipher.keyId()).doesNotContain("sk-1");
    verifyRefusal("key_changed");
  }

  static Stream<Arguments> rowsThatDoNotOpen() {
    return Stream.of(
        arguments(
            named(
                "no row",
                (Function<CredentialServiceTest, Optional<CredentialRepository.Row>>)
                    t -> Optional.empty()),
            "no_ciphertext",
            "entered before encryption — enter it again"),
        arguments(
            named(
                "a row sealed for another ref",
                (Function<CredentialServiceTest, Optional<CredentialRepository.Row>>)
                    t -> Optional.of(t.sealedRow("other-ref", Map.of("apiKey", "sk-1")))),
            "unreadable",
            "cannot be read"),
        arguments(
            named(
                "a row whose plaintext is not JSON",
                (Function<CredentialServiceTest, Optional<CredentialRepository.Row>>)
                    t -> Optional.of(t.sealedRow(REF, "not json: hunter2".getBytes()))),
            "unreadable",
            "cannot be read"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("rowsThatDoNotOpen")
  void givenAFreshLoginAndARowThatDoesNotOpen_whenRevealed_then409NothingLeaked(
      Function<CredentialServiceTest, Optional<CredentialRepository.Row>> row,
      String reason,
      String message,
      CapturedOutput output) {
    // Arrange: 299 s is still within the five minutes
    when(airlocks.isSecretReferenced(REF)).thenReturn(true);
    when(rows.find(REF)).thenReturn(row.apply(this));

    // Act
    CredentialException e = refused(REF, signedInSecondsAgo(299));

    // Assert
    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(e.getReason()).isEqualTo(reason);
    assertThat(e.getMessage()).isEqualTo(message);
    assertThat(output).doesNotContain("hunter2");
    verifyRefusal(reason);
  }

  @Test
  void givenARevealedResult_whenToString_thenNoValuePrinted() {
    // Arrange
    CredentialService.Revealed revealed =
        new CredentialService.Revealed(Map.of("password", "hunter2"));

    // Act
    String text = revealed.toString();

    // Assert
    assertThat(text).contains("password").doesNotContain("hunter2");
  }
}
