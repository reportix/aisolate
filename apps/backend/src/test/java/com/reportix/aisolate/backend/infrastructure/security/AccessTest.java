package com.reportix.aisolate.backend.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.reportix.aisolate.backend.domain.space.NotDeveloperException;
import com.reportix.aisolate.backend.domain.space.Space;
import com.reportix.aisolate.backend.domain.space.SpaceFormDTO;
import com.reportix.aisolate.backend.domain.space.SpaceRepository;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class AccessTest {

  private static final String DEVELOPER = "dora@example.com";

  private final SpaceRepository spaces = mock(SpaceRepository.class);
  private final Access access = new Access(spaces);
  private final Space hr = space("hr-acme", DEVELOPER);
  private final Space legal = space("legal-acme", "someone@example.com");

  @AfterEach
  void clearHolders() {
    SecurityContextHolder.clearContext();
    RequestContextHolder.resetRequestAttributes();
  }

  private static Space space(String id, String developer) {
    return new Space(
        id,
        id,
        "",
        List.of(new SpaceFormDTO.Member(developer, SpaceFormDTO.DEVELOPER)),
        List.of(),
        List.of(),
        "jasmine",
        null,
        null,
        null,
        null);
  }

  private static void signIn(String email, String... authorities) {
    Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").claim("email", email).build();
    SecurityContextHolder.getContext()
        .setAuthentication(
            new JwtAuthenticationToken(
                jwt, Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
  }

  private static void startRequest() {
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
  }

  @Test
  void givenARequest_whenTheAuditChecksRunInARow_thenTheSpacesAreListedOnce() {
    signIn(DEVELOPER);
    startRequest();
    when(spaces.list()).thenReturn(List.of(hr, legal));
    when(spaces.find("hr-acme")).thenReturn(Optional.of(hr));

    // Act: what GET /v1/audit/events costs a developer: the @PreAuthorize, requireAudited, the
    // handler's own read; developer(...) is the space check of a space-scoped endpoint.
    boolean reader = access.auditReader();
    List<String> audited = access.auditedSpaces();
    boolean developer = access.developer("hr-acme");
    List<String> developed = access.developedSpaces();

    assertThat(reader).isTrue();
    assertThat(audited).containsExactly("hr-acme");
    assertThat(developer).isTrue();
    assertThat(developed).containsExactly("hr-acme");
    verify(spaces, times(1)).list();
    verify(spaces, never()).find(anyString());
  }

  @Test
  void givenARequest_whenDeveloperRunsFirst_thenItReadsOneSpaceAndNoList() {
    signIn(DEVELOPER);
    startRequest();
    when(spaces.find("hr-acme")).thenReturn(Optional.of(hr));

    boolean developer = access.developer("hr-acme");

    assertThat(developer).isTrue();
    verify(spaces, times(1)).find("hr-acme");
    verify(spaces, never()).list();
  }

  @Test
  void givenNoRequest_whenDeveloperRuns_thenItReadsOneSpaceAndNoList() {
    signIn(DEVELOPER);
    when(spaces.find("hr-acme")).thenReturn(Optional.of(hr));

    boolean developer = access.developer("hr-acme");

    assertThat(developer).isTrue();
    verify(spaces, times(1)).find("hr-acme");
    verify(spaces, never()).list();
  }

  @Test
  void givenNoRequest_whenTheListChecksRun_thenEachListsAgain() {
    signIn(DEVELOPER);
    when(spaces.list()).thenReturn(List.of(hr, legal));

    access.auditReader();
    access.developedSpaces();

    verify(spaces, times(2)).list();
  }

  @Test
  void givenTheSpacesAreListed_whenDeveloperRuns_thenItAnswersFromTheListWithoutAFind() {
    signIn(DEVELOPER);
    startRequest();
    when(spaces.list()).thenReturn(List.of(hr, legal));
    when(spaces.find("hr-acme")).thenReturn(Optional.of(hr));
    when(spaces.find("legal-acme")).thenReturn(Optional.of(legal));

    boolean listed = access.developerAnywhere();
    boolean developer = access.developer("hr-acme");

    assertThat(listed).isTrue();
    assertThat(developer).isTrue();
    assertThatThrownBy(() -> access.developer("legal-acme"))
        .isInstanceOf(NotDeveloperException.class);
    assertThatThrownBy(() -> access.developer("no-such-space"))
        .isInstanceOf(NotDeveloperException.class);
    verify(spaces, times(1)).list();
    verify(spaces, never()).find(anyString());
  }

  @Test
  void givenNoSpaceAtAll_whenTheListChecksRun_thenTheEmptyListIsKeptToo() {
    signIn(DEVELOPER);
    startRequest();
    when(spaces.list()).thenReturn(List.of());

    boolean reader = access.auditReader();
    List<String> developed = access.developedSpaces();

    assertThat(reader).isFalse();
    assertThat(developed).isEmpty();
    verify(spaces, times(1)).list();
  }

  @Test
  void givenANewRequest_whenTheListChecksRun_thenTheSpacesAreListedAgain() {
    signIn(DEVELOPER);
    when(spaces.list()).thenReturn(List.of(hr, legal));

    startRequest();
    access.developedSpaces();
    startRequest();
    access.developedSpaces();

    verify(spaces, times(2)).list();
  }

  @Test
  void givenTheAdmin_whenAnyCheckRuns_thenNoSpaceIsRead() {
    signIn("admin@example.com", "ROLE_AISOLATE_ADMIN");
    startRequest();

    assertThat(access.developer("hr-acme")).isTrue();
    assertThat(access.developerAnywhere()).isTrue();
    assertThat(access.auditReader()).isTrue();
    assertThat(access.auditedSpaces()).isNull();
    assertThat(access.developedSpaces()).isNull();

    verifyNoInteractions(spaces);
  }

  @Test
  void givenTheAuditor_whenTheAuditChecksRun_thenNoSpaceIsRead() {
    signIn("auditor@example.com", "ROLE_AISOLATE_AUDITOR");
    startRequest();

    assertThat(access.auditReader()).isTrue();
    assertThat(access.auditedSpaces()).isNull();

    verifyNoInteractions(spaces);
  }
}
