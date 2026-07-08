package com.reportix.aisolate.backend.domain;

import java.util.Map;

/**
 * Who created a space or an airlock, as its row records them.
 *
 * @param username the IdP's username, the subject when there is none
 * @param name what the AIsolate UI shows: the token's {@code name}, else its username; null when
 *     neither is in it
 */
public record Creator(String username, String name) {

  /** From validated token claims. */
  public static Creator of(Map<String, Object> claims) {
    String preferred = text(claims, "preferred_username");
    String name = text(claims, "name");
    return new Creator(
        preferred != null ? preferred : text(claims, "sub"), name != null ? name : preferred);
  }

  private static String text(Map<String, Object> claims, String claim) {
    return claims.get(claim) instanceof String value && !value.isBlank() ? value : null;
  }
}
