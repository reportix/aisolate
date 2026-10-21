package com.reportix.aisolate.backend.application;

import com.reportix.aisolate.backend.domain.Creator;
import com.reportix.aisolate.backend.domain.deploy.DeployResultDTO;
import com.reportix.aisolate.backend.domain.deploy.DeployService;
import com.reportix.aisolate.backend.infrastructure.config.SwaggerApiConfig;
import com.reportix.aisolate.backend.infrastructure.security.Access;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Deploy", description = "The CLI's deploy: one airlock, its jar and its aisolate.yaml.")
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class DeployController {

  private final DeployService deployService;

  // Every part optional here, so a missing jar is a field error, not a bare 400.
  @Operation(
      summary = "Deploy one airlock",
      description =
          "Multipart `jar` (a jar, or a Python project's zip with `language` python),"
              + " `documents` (aisolate.yaml) and `variables`; the parts, the checks and the"
              + " answer: docs/api.md, The deploy call.",
      security = @SecurityRequirement(name = SwaggerApiConfig.BEARER_SCHEME))
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Deployed"),
    @ApiResponse(
        responseCode = "400",
        description = "Nothing written; one problem per entry of errors",
        content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE)),
    @ApiResponse(
        responseCode = "403",
        description = "Not a developer of the space",
        content = @Content),
    @ApiResponse(
        responseCode = "409",
        description = "The airlock id is another space's",
        content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE)),
    @ApiResponse(
        responseCode = "404",
        description = "No such space",
        content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE))
  })
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
