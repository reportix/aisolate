package com.reportix.aisolate.backend.application;

import com.reportix.aisolate.backend.domain.Creator;
import com.reportix.aisolate.backend.domain.airlock.Airlock;
import com.reportix.aisolate.backend.domain.airlock.AirlockFormDTO;
import com.reportix.aisolate.backend.domain.airlock.AirlockService;
import com.reportix.aisolate.backend.domain.directory.DirectoryService;
import com.reportix.aisolate.backend.domain.space.SpaceService;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The airlocks of a space as the AIsolate UI edits them, under {@code /spaces/{space}/airlocks}.
 * Every write is applied to the cluster before it answers ({@link ClusterHeader}). Airlock ids are
 * unique across spaces, which keeps the airlock pods' paths flat. What runs ({@code llm}, {@code
 * prompt}, the handlers, the tools, the code) comes from the deploy, never from here.
 */
@Tag(name = "Airlocks", description = "The AIsolate UI's airlocks under /spaces/{space}/airlocks.")
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class AirlockController {

  private final AirlockService airlockService;
  private final SpaceService spaceService;
  private final DirectoryService directory;
  private final ClusterSyncService clusterSync;

  // AIsolate UI: every method is the space's developers' (and the admin's), scoped to one space.

  @GetMapping("/spaces/{spaceId}/airlocks")
  @PreAuthorize(Access.DEVELOPER)
  public List<Airlock> list(@PathVariable String spaceId) {
    return airlockService.listAirlocks(spaceId);
  }

  @Operation(
      summary = "Read an airlock",
      description =
          "Its form, the LLM that answers and, read-only, the code the last deploy brought; jar is"
              + " null before.",
      security = @SecurityRequirement(name = SwaggerApiConfig.BEARER_SCHEME))
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "The airlock"),
    @ApiResponse(
        responseCode = "403",
        description = "Not a developer of the space",
        content = @Content),
    @ApiResponse(
        responseCode = "404",
        description = "No such airlock in the space",
        content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE))
  })
  @GetMapping("/spaces/{spaceId}/airlocks/{id}")
  @PreAuthorize(Access.DEVELOPER)
  public Airlock get(@PathVariable String spaceId, @PathVariable String id) {
    return airlockService.findAirlock(spaceId, id);
  }

  /**
   * Its own users and those it inherits from the space, with who they are in the directory, when
   * one is configured.
   */
  @GetMapping("/spaces/{spaceId}/airlocks/{id}/users")
  @PreAuthorize(Access.DEVELOPER)
  public DirectoryService.UsersDTO users(@PathVariable String spaceId, @PathVariable String id) {
    return directory.users(
        airlockService.findAirlock(spaceId, id).users(), spaceService.findSpace(spaceId).users());
  }

  @Operation(
      summary = "Create an airlock",
      description =
          "Its id is its endpoint path and is fixed from then on; it runs nothing until a deploy.",
      security = @SecurityRequirement(name = SwaggerApiConfig.BEARER_SCHEME))
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Created, on its own endpoint"),
    @ApiResponse(
        responseCode = "400",
        description = "Nothing written; one problem per entry of errors",
        content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE)),
    @ApiResponse(
        responseCode = "403",
        description = "Not a developer of the space",
        content = @Content),
    @ApiResponse(
        responseCode = "404",
        description = "No such space",
        content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE)),
    @ApiResponse(
        responseCode = "409",
        description = "The id is taken, or would collide",
        content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE))
  })
  @PostMapping("/spaces/{spaceId}/airlocks")
  @PreAuthorize(Access.DEVELOPER)
  public ResponseEntity<Airlock> create(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String spaceId,
      @RequestBody AirlockFormDTO form) {
    Airlock created = airlockService.createAirlock(spaceId, form, Creator.of(jwt.getClaims()));
    return ClusterHeader.synced(clusterSync, HttpStatus.CREATED, spaceId, created);
  }

  @Operation(
      summary = "Save an airlock in place",
      description = "Its id, what runs and its code stay; each save counts one revision.",
      security = @SecurityRequirement(name = SwaggerApiConfig.BEARER_SCHEME))
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Saved; its revision counts the save"),
    @ApiResponse(
        responseCode = "400",
        description = "Nothing written; one problem per entry of errors",
        content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE)),
    @ApiResponse(
        responseCode = "403",
        description = "Not a developer of the space",
        content = @Content),
    @ApiResponse(
        responseCode = "404",
        description = "No such airlock in the space",
        content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE)),
    @ApiResponse(
        responseCode = "409",
        description = "Saved by someone else since If-Match",
        content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE))
  })
  @PutMapping("/spaces/{spaceId}/airlocks/{id}")
  @PreAuthorize(Access.DEVELOPER)
  public ResponseEntity<Airlock> update(
      @PathVariable String spaceId,
      @PathVariable String id,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody AirlockFormDTO form) {
    Airlock updated = airlockService.updateAirlock(spaceId, id, form, ifMatch);
    return ClusterHeader.synced(clusterSync, HttpStatus.OK, spaceId, updated);
  }
}
