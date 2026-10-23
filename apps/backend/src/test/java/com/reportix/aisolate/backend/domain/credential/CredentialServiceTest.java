package com.reportix.aisolate.backend.domain.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
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

  @Test
  void givenATokenFromAnotherClient_whenRevealed_then403WrongClientRecorded() {
    // Arrange
    when(airlocks.isSecretReferenced(REF)).thenReturn(true);

    // Act
    CredentialException e = refused(REF, claimsFrom("aisolate-cli"));

    // Assert
    assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(e.getReason()).isEqualTo("wrong_client");
    assertThat(e.getMessage()).isEqualTo("not from the AIsolate UI");
    verifyRefusal("wrong_client");
  }

  @Test
  void givenATokenWithoutAzp_whenRevealed_then403WrongClient() {
    // Arrange
    when(airlocks.isSecretReferenced(REF)).thenReturn(true);
    Map<String, Object> noAzp =
        Jwt.withTokenValue("t")
            .header("alg", "none")
            .claim("email", "andrew@example.com")
            .claim("auth_time", NOW.minusSeconds(10).getEpochSecond())
            .build()
            .getClaims();

    // Act
    CredentialException e = refused(REF, noAzp);

    // Assert
    assertThat(e.getReason()).isEqualTo("wrong_client");
    verifyRefusal("wrong_client");
  }

  @Test
  void givenNoAuthTime_whenRevealed_then403NoAuthTime() {
    // Arrange
    when(airlocks.isSecretReferenced(REF)).thenReturn(true);

    // Act
    CredentialException e = refused(REF, claims());

    // Assert
    assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(e.getReason()).isEqualTo("no_auth_time");
    assertThat(e.getMessage()).isEqualTo("the identity provider sends no auth_time claim");
    verifyRefusal("no_auth_time");
  }

  @Test
  void givenAuthTimeAsAString_whenRevealed_then403NoAuthTime() {
    // Arrange
    when(airlocks.isSecretReferenced(REF)).thenReturn(true);

    // Act
    CredentialException e = refused(REF, claims("1761300000"));

    // Assert
    assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(e.getReason()).isEqualTo("no_auth_time");
  }

  @Test
  void givenAStaleLogin_whenRevealed_then403StaleLogin() {
    // Arrange
    when(airlocks.isSecretReferenced(REF)).thenReturn(true);

    // Act
    CredentialException e = refused(REF, signedInSecondsAgo(301));

    // Assert
    assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(e.getReason()).isEqualTo("stale_login");
    assertThat(e.getMessage()).isEqualTo("sign in again to reveal");
    verifyRefusal("stale_login");
  }

  @Test
  void givenAnAuthTimeBeyondInstantsRange_whenRevealed_then403StaleLoginRecorded() {
    // Arrange
    when(airlocks.isSecretReferenced(REF)).thenReturn(true);

    // Act
    CredentialException e = refused(REF, claims(Long.MAX_VALUE));

    // Assert
    assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(e.getReason()).isEqualTo("stale_login");
    verifyRefusal("stale_login");
  }

  @Test
  void givenAnAuthTimeTenMinutesInTheFuture_whenRevealed_then403StaleLogin() {
    // Arrange
    when(airlocks.isSecretReferenced(REF)).thenReturn(true);

    // Act
    CredentialException e = refused(REF, signedInSecondsAgo(-600));

    // Assert
    assertThat(e.getReason()).isEqualTo("stale_login");
  }

  @Test
  void givenAnAuthTimeSixtySecondsInTheFuture_whenRevealed_thenItReveals() {
    // Arrange
    when(airlocks.isSecretReferenced(REF)).thenReturn(true);
    when(rows.find(REF)).thenReturn(Optional.of(sealedRow(REF, Map.of("apiKey", "sk-1"))));

    // Act
    CredentialService.Revealed revealed = service.reveal(REF, signedInSecondsAgo(-60));

    // Assert
    assertThat(revealed.values()).isEqualTo(Map.of("apiKey", "sk-1"));
  }

  @Test
  void givenAnAuthTimeSixtyOneSecondsInTheFuture_whenRevealed_then403StaleLogin() {
    // Arrange
    when(airlocks.isSecretReferenced(REF)).thenReturn(true);

    // Act
    CredentialException e = refused(REF, signedInSecondsAgo(-61));

    // Assert
    assertThat(e.getReason()).isEqualTo("stale_login");
  }

  @Test
  void givenANonFiniteAuthTime_whenRevealed_then403NoAuthTime() {
    // Arrange
    when(airlocks.isSecretReferenced(REF)).thenReturn(true);

    // Act
    CredentialException e = refused(REF, claims(Double.POSITIVE_INFINITY));

    // Assert
    assertThat(e.getReason()).isEqualTo("no_auth_time");
  }

  @Test
  void givenADoubleAuthTimeThirtySecondsAgo_whenRevealed_thenValues() {
    // Arrange
    when(airlocks.isSecretReferenced(REF)).thenReturn(true);
    when(rows.find(REF)).thenReturn(Optional.of(sealedRow(REF, Map.of("apiKey", "sk-1"))));
    double authTime = NOW.minusSeconds(30).getEpochSecond();

    // Act
    CredentialService.Revealed revealed = service.reveal(REF, claims(authTime));

    // Assert
    assertThat(revealed.values()).isEqualTo(Map.of("apiKey", "sk-1"));
  }

  @Test
  void givenAFreshLoginAndNoRow_whenRevealed_then409NoCiphertext() {
    // Arrange
    when(airlocks.isSecretReferenced(REF)).thenReturn(true);
    when(rows.find(REF)).thenReturn(Optional.empty());

    // Act
    CredentialException e = refused(REF, signedInSecondsAgo(299));

    // Assert
    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(e.getReason()).isEqualTo("no_ciphertext");
    assertThat(e.getMessage()).isEqualTo("entered before encryption — enter it again");
    verifyRefusal("no_ciphertext");
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

  @Test
  void givenARowThatDoesNotOpen_whenRevealed_then409Unreadable() {
    // Arrange
    when(airlocks.isSecretReferenced(REF)).thenReturn(true);
    when(rows.find(REF)).thenReturn(Optional.of(sealedRow("other-ref", Map.of("apiKey", "sk-1"))));

    // Act
    CredentialException e = refused(REF, signedInSecondsAgo(10));

    // Assert
    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(e.getReason()).isEqualTo("unreadable");
    assertThat(e.getMessage()).isEqualTo("cannot be read");
    verifyRefusal("unreadable");
  }

  @Test
  void givenARowWhosePlaintextIsNotJson_whenRevealed_then409UnreadableRecorded(
      CapturedOutput output) {
    // Arrange
    when(airlocks.isSecretReferenced(REF)).thenReturn(true);
    when(rows.find(REF)).thenReturn(Optional.of(sealedRow(REF, "not json: hunter2".getBytes())));

    // Act
    CredentialException e = refused(REF, signedInSecondsAgo(10));

    // Assert
    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(e.getReason()).isEqualTo("unreadable");
    assertThat(e.getMessage()).doesNotContain("hunter2");
    assertThat(output).doesNotContain("hunter2");
    verifyRefusal("unreadable");
  }

  @Test
  void givenAFreshLoginAndARow_whenRevealed_thenValuesAndRevealedRecorded() {
    // Arrange
    when(airlocks.isSecretReferenced(REF)).thenReturn(true);
    when(rows.find(REF))
        .thenReturn(
            Optional.of(sealedRow(REF, Map.of("username", "reader", "password", "hunter2"))));

    // Act
    CredentialService.Revealed revealed = service.reveal(REF, signedInSecondsAgo(10));

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
