package com.reportix.aisolate.backend.infrastructure.security;

import com.reportix.aisolate.backend.domain.space.NotDeveloperException;
import com.reportix.aisolate.backend.domain.space.Space;
import com.reportix.aisolate.backend.domain.space.SpaceFormDTO;
import com.reportix.aisolate.backend.domain.space.SpaceRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Who may do what, as GitLab's groups decide it: the platform admin (realm role {@code
 * aisolate-admin}) everything everywhere; a space's developers, named among its members, its
 * airlocks and deploys; the auditor the audit. A space role is the space's list, not the token's:
 * anyone in the directory can be made Developer of a space. Every endpoint names one of these
 * expressions (ArchitectureTest).
 *
 * <p>The checks that read every space ({@link #developerAnywhere}, {@link #developedSpaces}, so the
 * audit reads) list them once per request: a handler runs one such check in its
 * {@code @PreAuthorize} and again in its body.
 */
@Component("access")
@RequiredArgsConstructor
public class Access {

  /** Creating and editing a space, its members, LLM and data sources; resetting the install. */
  public static final String ADMIN = "hasRole('AISOLATE_ADMIN')";

  /** Everything in the space {@code spaceId} but the space's own parts. */
  public static final String DEVELOPER = "@access.developer(#spaceId)";

  /** Searching the directory: the admin, and developers picking an airlock's users. */
  public static final String DEVELOPER_ANYWHERE = "@access.developerAnywhere()";

  /** The admin and the auditor read all of it, a developer the events of their spaces. */
  public static final String AUDIT_READER = "@access.auditReader()";

  /** Anyone signed in; the answer holds only what they may see. */
  public static final String SIGNED_IN = "isAuthenticated()";

  private static final String ADMIN_AUTHORITY = "ROLE_AISOLATE_ADMIN";
  private static final String AUDITOR_AUTHORITY = "ROLE_AISOLATE_AUDITOR";

  /** The request attribute holding the spaces a request has listed. */
  private static final String LISTED = Access.class.getName() + ".spaces";

  private final SpaceRepository spaces;

  public boolean admin() {
    return has(ADMIN_AUTHORITY);
  }

  /**
   * @throws NotDeveloperException neither the admin nor a developer of it, a space that does not
   *     exist included, so a refusal says nothing about which do
   */
  public boolean developer(String spaceId) {
    if (admin() || find(spaceId).map(this::develops).orElse(false)) {
      return true;
    }
    throw new NotDeveloperException(spaceId);
  }

  public boolean developerAnywhere() {
    return admin() || listed().stream().anyMatch(this::develops);
  }

  public boolean auditReader() {
    return has(AUDITOR_AUTHORITY) || developerAnywhere();
  }

  /** The spaces the caller sees, each with their role in it: all for the admin. */
  public List<Space> visible(List<Space> all) {
    return all.stream().map(this::withCallerRole).filter(s -> s.callerRole() != null).toList();
  }

  /** {@code admin}, {@code developer}, or null for whoever may not open it in the AIsolate UI. */
  public Space withCallerRole(Space space) {
    return space.withCallerRole(
        admin() ? Space.ADMIN : develops(space) ? SpaceFormDTO.DEVELOPER : null);
  }

  /** The spaces whose audit the caller reads; null for every space (the admin, the auditor). */
  public List<String> auditedSpaces() {
    return has(AUDITOR_AUTHORITY) ? null : developedSpaces();
  }

  public List<String> developedSpaces() {
    return admin() ? null : listed().stream().filter(this::develops).map(Space::id).toList();
  }

  /**
   * The spaces, listed once per request when a request is bound; a plain query for work outside
   * one. Staleness within a request is harmless: only the admin handlers change spaces, and the
   * admin reads none.
   */
  private List<Space> listed() {
    RequestAttributes request = RequestContextHolder.getRequestAttributes();
    if (request == null) {
      return spaces.list();
    }
    if (request.getAttribute(LISTED, RequestAttributes.SCOPE_REQUEST) instanceof Listed memo) {
      return memo.spaces();
    }
    List<Space> all = spaces.list();
    request.setAttribute(LISTED, new Listed(all), RequestAttributes.SCOPE_REQUEST);
    return all;
  }

  /**
   * One space: from the request's list when it has already listed them, else a query of that one,
   * so a plain developer endpoint stays one lookup instead of reading every space.
   */
  private Optional<Space> find(String spaceId) {
    RequestAttributes request = RequestContextHolder.getRequestAttributes();
    if (request != null
        && request.getAttribute(LISTED, RequestAttributes.SCOPE_REQUEST) instanceof Listed memo) {
      return memo.spaces().stream().filter(s -> s.id().equals(spaceId)).findFirst();
    }
    return spaces.find(spaceId);
  }

  /** The request attribute's value; a record, so an empty list is a value, not an absence. */
  private record Listed(List<Space> spaces) {}

  private boolean develops(Space space) {
    return space.roleOf(email()).filter(SpaceFormDTO.DEVELOPER::equals).isPresent();
  }

  private static String email() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    return auth != null && auth.getPrincipal() instanceof Jwt jwt
        ? jwt.getClaimAsString("email")
        : null;
  }

  private static boolean has(String authority) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    return auth != null
        && auth.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch(authority::equals);
  }
}
