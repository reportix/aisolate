package com.reportix.aisolate.backend.application;

import com.reportix.aisolate.backend.domain.credential.CredentialService;
import com.reportix.aisolate.backend.infrastructure.security.Access;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reads back a typed API key or login. The realm admin alone, and only when their sign-in is
 * younger than the reveal window and whose token is the AIsolate UI's (its {@code azp}); every
 * attempt, allowed or refused, is a line in the credential's events with the admin's e-mail and
 * never the value.
 */
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class CredentialController {

  private final CredentialService service;

  @PostMapping("/credentials/{secretRef}/reveal")
  @PreAuthorize(Access.ADMIN)
  public ResponseEntity<CredentialService.Revealed> reveal(
      @AuthenticationPrincipal Jwt jwt, @PathVariable String secretRef) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(service.reveal(secretRef, jwt.getClaims()));
  }
}
