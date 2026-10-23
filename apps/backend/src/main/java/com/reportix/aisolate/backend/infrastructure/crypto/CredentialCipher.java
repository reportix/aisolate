package com.reportix.aisolate.backend.infrastructure.crypto;

import com.reportix.aisolate.backend.infrastructure.cluster.ClusterProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Seals a typed credential with AES-GCM under the install's key. The secret's name is the AAD, so a
 * ciphertext copied onto another row does not open.
 */
@Component
public class CredentialCipher {

  private static final int KEY_MIN_CHARS = 32;
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final SecureRandom RANDOM = new SecureRandom();

  /** What the database keeps; {@code keyId} tells a changed key from a damaged row. */
  public record Sealed(String keyId, byte[] nonce, byte[] ciphertext) {}

  /** A bad tag or another AAD; never carries the value. */
  public static class Unreadable extends RuntimeException {
    Unreadable(Throwable cause) {
      super("the credential cannot be opened", cause);
    }
  }

  private final SecretKeySpec key;
  private final String keyId;

  public CredentialCipher(CredentialProperties properties, ClusterProperties cluster) {
    String secret = properties.key();
    if (secret == null || secret.isBlank()) {
      if (cluster.enabled()) {
        throw new IllegalStateException(
            "AISOLATE_CREDENTIALS_KEY is required when aisolate.cluster.namespace is set:"
                + " credentials are stored encrypted");
      }
      key = null;
      keyId = null;
      return;
    }
    if (secret.length() < KEY_MIN_CHARS) {
      throw new IllegalStateException(
          "AISOLATE_CREDENTIALS_KEY must be at least " + KEY_MIN_CHARS + " characters");
    }
    byte[] aesKey = sha256(secret.getBytes(StandardCharsets.UTF_8));
    key = new SecretKeySpec(aesKey, "AES");
    keyId = HexFormat.of().formatHex(sha256(aesKey), 0, 4);
  }

  public boolean enabled() {
    return key != null;
  }

  public String keyId() {
    return keyId;
  }

  public Sealed seal(String aad, byte[] plaintext) {
    requireKey();
    byte[] nonce = new byte[NONCE_BYTES];
    RANDOM.nextBytes(nonce);
    try {
      return new Sealed(keyId, nonce, run(Cipher.ENCRYPT_MODE, aad, nonce, plaintext));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("the credential cannot be sealed", e);
    }
  }

  public byte[] open(String aad, Sealed sealed) {
    requireKey();
    try {
      return run(Cipher.DECRYPT_MODE, aad, sealed.nonce(), sealed.ciphertext());
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      // a null nonce (a damaged row) throws the latter
      throw new Unreadable(e);
    }
  }

  private byte[] run(int mode, String aad, byte[] nonce, byte[] input)
      throws GeneralSecurityException {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
    cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
    return cipher.doFinal(input);
  }

  private void requireKey() {
    if (key == null) {
      throw new IllegalStateException("no credentials key");
    }
  }

  private static byte[] sha256(byte[] in) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(in);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
