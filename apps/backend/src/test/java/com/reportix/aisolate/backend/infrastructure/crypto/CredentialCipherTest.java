package com.reportix.aisolate.backend.infrastructure.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reportix.aisolate.backend.infrastructure.cluster.ClusterProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;

class CredentialCipherTest {

  private static final String KEY = "test-credentials-key-of-at-least-32-chars";

  private static CredentialCipher cipher(String key, String namespace) {
    return new CredentialCipher(
        new CredentialProperties(key, Duration.ofMinutes(5), List.of("aisolate-ui")),
        new ClusterProperties(namespace));
  }

  static Stream<String> values() {
    return Stream.of("hunter2", "pässwörd-☃", "k".repeat(4096));
  }

  @ParameterizedTest
  @MethodSource("values")
  void givenAValue_whenSealedAndOpened_thenTheSameBytesComeBack(String value) {
    // Arrange
    CredentialCipher cipher = cipher(KEY, null);
    byte[] plaintext = value.getBytes(StandardCharsets.UTF_8);

    // Act
    CredentialCipher.Sealed sealed = cipher.seal("ref-a", plaintext);
    byte[] opened = cipher.open("ref-a", sealed);

    // Assert
    assertThat(opened).isEqualTo(plaintext);
    assertThat(sealed.keyId()).matches("[0-9a-f]{8}");
    assertThat(sealed.nonce()).hasSize(12);
  }

  @Test
  void givenASealedValue_whenOpenedWithAnotherAad_thenUnreadable() {
    // Arrange
    CredentialCipher cipher = cipher(KEY, null);
    CredentialCipher.Sealed sealed = cipher.seal("ref-a", new byte[] {1, 2, 3});

    // Act
    var opened = assertThatThrownBy(() -> cipher.open("ref-b", sealed));

    // Assert
    opened.isInstanceOf(CredentialCipher.Unreadable.class);
  }

  @Test
  void givenASealedValue_whenAByteIsFlipped_thenUnreadable() {
    // Arrange
    CredentialCipher cipher = cipher(KEY, null);
    CredentialCipher.Sealed sealed = cipher.seal("ref-a", new byte[] {1, 2, 3});
    byte[] flipped = sealed.ciphertext().clone();
    flipped[0] ^= 1;
    CredentialCipher.Sealed tampered =
        new CredentialCipher.Sealed(sealed.keyId(), sealed.nonce(), flipped);

    // Act
    var opened = assertThatThrownBy(() -> cipher.open("ref-a", tampered));

    // Assert
    opened.isInstanceOf(CredentialCipher.Unreadable.class);
  }

  @ParameterizedTest
  @NullSource
  @MethodSource("damagedNonces")
  void givenADamagedNonce_whenOpened_thenUnreadable(byte[] nonce) {
    // Arrange
    CredentialCipher cipher = cipher(KEY, null);
    CredentialCipher.Sealed sealed = cipher.seal("ref-a", new byte[] {1, 2, 3});
    CredentialCipher.Sealed damaged =
        new CredentialCipher.Sealed(sealed.keyId(), nonce, sealed.ciphertext());

    // Act
    var opened = assertThatThrownBy(() -> cipher.open("ref-a", damaged));

    // Assert
    opened.isInstanceOf(CredentialCipher.Unreadable.class);
  }

  static Stream<byte[]> damagedNonces() {
    return Stream.of(new byte[0], new byte[8], new byte[16]);
  }

  @Test
  void givenTheSameValueSealedTwice_thenNoncesAndCiphertextsDiffer() {
    // Arrange
    CredentialCipher cipher = cipher(KEY, null);
    byte[] plaintext = {1, 2, 3};

    // Act
    CredentialCipher.Sealed first = cipher.seal("ref-a", plaintext);
    CredentialCipher.Sealed second = cipher.seal("ref-a", plaintext);

    // Assert
    assertThat(first.nonce()).isNotEqualTo(second.nonce());
    assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
  }

  @Test
  void givenAClusterAndNoKey_whenConstructed_thenRefused() {
    // Arrange
    String noKey = "";

    // Act
    var constructed = assertThatThrownBy(() -> cipher(noKey, "aisolate"));

    // Assert
    constructed
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("AISOLATE_CREDENTIALS_KEY");
  }

  @Test
  void givenAKeyUnder32Chars_whenConstructed_thenRefused() {
    // Arrange
    String shortKey = "short";

    // Act
    var constructed = assertThatThrownBy(() -> cipher(shortKey, null));

    // Assert
    constructed.isInstanceOf(IllegalStateException.class);
  }

  @Test
  void givenNoClusterAndNoKey_whenConstructed_thenDisabled() {
    // Arrange
    CredentialCipher cipher = cipher("", null);

    // Act
    boolean enabled = cipher.enabled();
    var sealed = assertThatThrownBy(() -> cipher.seal("ref-a", new byte[] {1}));

    // Assert
    assertThat(enabled).isFalse();
    sealed.isInstanceOf(IllegalStateException.class);
  }

  @Test
  void givenTheSameKeyTwice_whenKeyId_thenEqual() {
    // Arrange
    String another = KEY + "-another";

    // Act
    String first = cipher(KEY, null).keyId();
    String second = cipher(KEY, null).keyId();
    String other = cipher(another, null).keyId();

    // Assert
    assertThat(first).isEqualTo(second);
    assertThat(first).isNotEqualTo(other);
  }
}
