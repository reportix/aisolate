package com.reportix.aisolate.config;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The credential one airlock's pod hands its audit lines over with: HMAC-SHA256 of {@code
 * <space>/<airlock>} under the install's ingest key, which only the controller (to render each
 * airlock's Secret) and the backend (to check a line against the airlock it names) hold. One
 * airlock's token says nothing about another's, and neither side keeps a table of them.
 *
 * <p>The airlock's code token is the same scheme over {@code code/<space>/<airlock>}: its airlock
 * and tool-server pods fetch their artifact with it, and it admits no audit line, nor the code of
 * another airlock.
 */
public final class AuditIngestToken {

  /** The Secret key, and the file name under the pod's mount. */
  public static final String KEY = "token";

  public static String of(String key, String space, String airlock) {
    // Ids are slugs without a slash, so the joined name is unambiguous.
    return sign(key, space + "/" + airlock);
  }

  /** The airlock's code token: three parts, so never equal to an ingest token's two. */
  public static String ofCode(String key, String space, String airlock) {
    return sign(key, "code/" + space + "/" + airlock);
  }

  /** In constant time; false without a key, so an install without one admits no line. */
  public static boolean matches(String key, String presented, String space, String airlock) {
    return key != null && !key.isBlank() && equal(of(key, space, airlock), presented);
  }

  /** {@link #matches} for the code token: false without a key, so no code leaves. */
  public static boolean matchesCode(String key, String presented, String space, String airlock) {
    return key != null && !key.isBlank() && equal(ofCode(key, space, airlock), presented);
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
