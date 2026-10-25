package com.reportix.aisolate.backend.application;

import com.reportix.aisolate.backend.domain.Creator;
import com.reportix.aisolate.backend.domain.space.Space;
import com.reportix.aisolate.backend.domain.space.SpaceFormDTO;
import com.reportix.aisolate.backend.domain.space.SpaceService;
import com.reportix.aisolate.backend.domain.space.SpacesDeletedDTO;
import com.reportix.aisolate.backend.infrastructure.cluster.ClusterSyncService;
import com.reportix.aisolate.backend.infrastructure.config.SwaggerApiConfig;
import com.reportix.aisolate.backend.infrastructure.security.Access;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Spaces as the AIsolate UI edits them: the admin creates a space and names its developers (by
 * e-mail), the roles that may ask its airlocks, and its LLM and data sources, which those who ask
 * never see. A developer sees the spaces they develop, each read-only here. Every write is applied
 * to the cluster before it answers ({@link ClusterHeader}).
 */
@Tag(
    name = "Spaces",
    description =
        "Spaces for the AIsolate UI: the developers, roles, LLM, data sources and airlocks of one"
            + " team or product.")
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class SpaceController {

  private final SpaceService spaceService;
  private final ClusterSyncService clusterSync;
  private final Access access;

  /** Those the caller develops, every space for the admin; none for anyone else. */
  @GetMapping("/spaces")
  @PreAuthorize(Access.SIGNED_IN)
  public List<Space> list() {
    return access.visible(spaceService.listSpaces());
  }

  @GetMapping("/spaces/{spaceId}")
  @PreAuthorize(Access.DEVELOPER)
  public Space get(@PathVariable String spaceId) {
    return access.withCallerRole(spaceService.findSpace(spaceId));
  }

  @PostMapping("/spaces")
  @PreAuthorize(Access.ADMIN)
  public ResponseEntity<Space> create(
      @AuthenticationPrincipal Jwt jwt, @RequestBody SpaceFormDTO form) {
    Space created = access.withCallerRole(spaceService.create(form, Creator.of(jwt.getClaims())));
    return ClusterHeader.synced(clusterSync, HttpStatus.CREATED, created.id(), created);
  }

  @PutMapping("/spaces/{spaceId}")
  @PreAuthorize(Access.ADMIN)
  public ResponseEntity<Space> update(
      @PathVariable String spaceId,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody SpaceFormDTO form) {
    Space updated = access.withCallerRole(spaceService.update(spaceId, form, ifMatch));
    return ClusterHeader.synced(clusterSync, HttpStatus.OK, spaceId, updated);
  }

  @Operation(
      summary = "Delete every space",
      description =
          "The cluster's clean slate: removes every space with its airlocks and the controller's"
              + " objects. See docs/api.md.",
      security = @SecurityRequirement(name = SwaggerApiConfig.BEARER_SCHEME))
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Deleted; cluster is null without one"),
    @ApiResponse(responseCode = "403", description = "No admin role", content = @Content)
  })
  @DeleteMapping("/spaces")
  @PreAuthorize(Access.ADMIN)
  public SpacesDeletedDTO deleteAll() {
    return spaceService.deleteAll();
  }
}
