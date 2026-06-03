package com.reportix.aisolate.config;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The connection URLs of an airlock: the host the controller maps to an egress peer, and the check
 * that a URL carries no credential, since documents end up in ConfigMaps.
 */
public final class Urls {

  // scheme://[userinfo@]host[:port]...; a jdbc: prefix is stripped first. IPv6 hosts in brackets.
  private static final Pattern AUTHORITY =
      Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://(?:([^@/?#;]*)@)?(\\[[^\\]]+]|[^:/?#;]+)");

  /**
   * Query parameters and JDBC properties (`;name=value`) that hold a credential, lower case. The
   * user counts: the docs promise it is neither stored nor shown.
   */
  private static final Set<String> SECRET_PARAMETERS =
      Set.of(
          "user",
          "username",
          "password",
          "pwd",
          "passwd",
          "secret",
          "token",
          "access_token",
          "apikey",
          "api_key",
          "key",
          "sslpassword",
          "sslkey");

  private Urls() {}

  /** The host of {@code url}, without brackets for IPv6; null when it has none. */
  public static String host(String url) {
    Matcher m = AUTHORITY.matcher(stripJdbc(url));
    if (!m.find()) {
      return null;
    }
    String host = m.group(2);
    return host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
  }

  /** Why {@code url} must not be stored, or null: a user or password in it. */
  public static String findCredential(String url) {
    String stripped = stripJdbc(url);
    Matcher m = AUTHORITY.matcher(stripped);
    if (m.find() && m.group(1) != null) {
      return m.group(1).contains(":")
          ? "carries a password in user:password@; give the credentials in their own fields"
          : "carries a user in user@; give the credentials in their own fields";
    }
    for (String part : stripped.split("[?&;]")) {
      int eq = part.indexOf('=');
      if (eq > 0 && SECRET_PARAMETERS.contains(part.substring(0, eq).toLowerCase(Locale.ROOT))) {
        return "carries the parameter "
            + part.substring(0, eq)
            + "; give the credentials in their own fields";
      }
    }
    return null;
  }

  /**
   * Why {@code url} is refused, or null: a credential in it, or no host. The URL itself is never
   * echoed: the sentence would carry the credential it found.
   */
  public static String problem(String url) {
    String credential = findCredential(url);
    if (credential != null) {
      return credential;
    }
    return host(url) == null ? "has no host: expected scheme://host[:port]" : null;
  }

  private static String stripJdbc(String url) {
    String trimmed = url == null ? "" : url.trim();
    return trimmed.regionMatches(true, 0, "jdbc:", 0, 5) ? trimmed.substring(5) : trimmed;
  }
}
