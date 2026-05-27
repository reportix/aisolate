package com.reportix.aisolate.sdk;

import java.util.Set;

/** The asking user: subject (the user id), e-mail when the token carries one, roles. */
public record Principal(String sub, String email, Set<String> roles) {
  public Principal {
    roles = roles == null ? Set.of() : Set.copyOf(roles);
  }
}
