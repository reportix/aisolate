package com.reportix.aisolate.config;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The credential one airlock version's pod hands its audit lines over with: HMAC-SHA256 of {@code
 * <space>/<airlock>/<version>} under the install's ingest key, which only the controller (to render
 * each version's Secret) and the backend (to check a line against the version it names) hold. One
 * version's token says nothing about another's, and neither side keeps a table of them.
 *
 * <p>The version's code token is the same scheme over {@code code/<space>/<airlock>/<version>}: its
 * airlock and tool-server pods fetch their artifact with it, and it admits no audit line, nor the
 * code of another version.
 */
public final class AuditIngestToken {

  /** The Secret key, and the file name under the pod's mount. */
  public static final String KEY = "token";

  public static String of(String key, String space, String airlock, String version) {
    // Ids and labels carry no slash, so the joined name is unambiguous.
    return sign(key, space + "/" + airlock + "/" + version);
  }

  /** The version's code token: four parts, so never equal to an ingest token's three. */
  public static String ofCode(String key, String space, String airlock, String version) {
    return sign(key, "code/" + space + "/" + airlock + "/" + version);
  }

  /** In constant time; false without a key, so an install without one admits no line. */
  public static boolean matches(
      String key, String presented, String space, String airlock, String version) {
    return key != null && !key.isBlank() && equal(of(key, space, airlock, version), presented);
  }

  /** {@link #matches} for the code token: false without a key, so no code leaves. */
  public static boolean matchesCode(
      String key, String presented, String space, String airlock, String version) {
    return key != null && !key.isBlank() && equal(ofCode(key, space, airlock, version), presented);
  }

  private static boolean equal(String expected, String presented) {
    return presented != null
        && MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
  }

  private static String sign(String key, String message) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private AuditIngestToken() {}
}
