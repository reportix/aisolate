package com.reportix.aisolate.backend.application;

import com.reportix.aisolate.backend.domain.Creator;
import com.reportix.aisolate.backend.domain.deploy.DeployResultDTO;
import com.reportix.aisolate.backend.domain.deploy.DeployService;
import com.reportix.aisolate.backend.infrastructure.security.Access;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** The CLI's one call: the project's jar and its aisolate.yaml into a space. */
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class DeployController {

  private final DeployService deployService;

  // Every part optional here, so a missing jar is a field error, not a bare 400.
  @PostMapping(path = "/spaces/{spaceId}/deploy", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize(Access.DEVELOPER)
  public DeployResultDTO deploy(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String spaceId,
      @RequestPart(required = false) MultipartFile jar,
      @RequestPart(required = false) String language,
      @RequestPart(required = false) String documents,
      @RequestPart(required = false) String variables) {
    return deployService.deploy(
        spaceId, jar, language, documents, variables, Creator.of(jwt.getClaims()));
  }
}
