package com.reportix.aisolate.identity;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The end user as the validated token describes them, as far as AIsolate needs: who is calling
 * ({@code azp}), the roles an airlock admits by, and the e-mail a developer handler may filter with
 * and the audit trail records. Built only from verified claims (plus, on the tool server, the
 * plugin's identity join), never from the request body, tool arguments or the prompt.
 *
 * @param attributes what a row filter may compare with as {@code principal.<name>}: {@code sub},
 *     {@code email}, and the plugin's identity attribute when the join found a row
 */
public record Principal(
    String sub, Set<String> roles, String azp, String email, Map<String, Object> attributes) {

  /** Where Keycloak puts realm roles: the default of {@code aisolate.roles-claim}. */
  public static final String DEFAULT_ROLES_CLAIM = "realm_access.roles";

  public Principal {
    attributes = Map.copyOf(attributes);
  }

  /**
   * Reads identity from validated token claims. The roles claim is a dot path, {@code
   * aisolate.roles-claim}; nothing here is IdP-specific.
   */
  public static Principal of(Map<String, Object> claims, String rolesClaim) {
    return of(claims, rolesClaim, null, null);
  }

  /**
   * @param identityAttribute the plugin's name for the identity join's value; null without a join
   * @param identityValue the joined business key; null when the user has no row
   */
  public static Principal of(
      Map<String, Object> claims,
      String rolesClaim,
      String identityAttribute,
      Object identityValue) {
    if (!(claims.get("sub") instanceof String sub) || sub.isBlank()) {
      throw new IllegalArgumentException("token has no sub claim");
    }
    String email = claims.get("email") instanceof String address ? address : null;
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("sub", sub);
    if (email != null) {
      attributes.put("email", email);
    }
    if (identityAttribute != null && identityValue != null) {
      attributes.put(identityAttribute, identityValue);
    }
    return new Principal(
        sub,
        Set.copyOf(roles(claims, rolesClaim)),
        claims.get("azp") instanceof String azp ? azp : null,
        email,
        attributes);
  }

  public static List<String> roles(Map<String, Object> claims, String rolesClaim) {
    Object node = claims;
    for (String segment : rolesClaim.split("\\.")) {
      if (!(node instanceof Map<?, ?> map)) {
        return List.of();
      }
      node = map.get(segment);
    }
    if (node instanceof List<?> list) {
      return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }
    return node instanceof String single ? List.of(single) : List.of();
  }

  /** Null when the user has no such attribute; the evaluator then denies. */
  public Object attribute(String name) {
    return name == null ? null : attributes.get(name);
  }

  /** What gate 2 found wrong, for the audit trail; the client is told only "forbidden". */
  public enum AdmissionFailure {
    ROLE,
    CLIENT
  }

  /**
   * Gate 2, the one place it is decided. A token without an e-mail whose calling client is one of
   * the airlock's machine clients is admitted (exact, {@code *} is no wildcard; roles and callers
   * are not consulted). Any other token is admitted when one of its roles is one of the airlock's
   * space's roles or its own (exact: the identity provider names roles, {@code *} is no wildcard)
   * and the calling client one of its callers ({@code *} admits any client registered at the
   * identity provider); else why not, the role looked at first. Who holds a role is the identity
   * provider's to decide, so no e-mail is needed.
   */
  public Optional<AdmissionFailure> admissionFailure(
      Collection<String> allowedRoles,
      Collection<String> allowedCallers,
      Collection<String> allowedClients) {
    if (email == null && azp != null && allowedClients.contains(azp)) {
      return Optional.empty();
    }
    if (roles.stream().noneMatch(allowedRoles::contains)) {
      return Optional.of(AdmissionFailure.ROLE);
    }
    boolean callerListed =
        azp != null && (allowedCallers.contains("*") || allowedCallers.contains(azp));
    return callerListed ? Optional.empty() : Optional.of(AdmissionFailure.CLIENT);
  }
}
