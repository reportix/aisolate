package com.reportix.aisolate.backend.application;

import com.reportix.aisolate.backend.domain.Creator;
import com.reportix.aisolate.backend.domain.airlock.Airlock;
import com.reportix.aisolate.backend.domain.airlock.AirlockFormDTO;
import com.reportix.aisolate.backend.domain.airlock.AirlockService;
import com.reportix.aisolate.backend.domain.airlock.AirlockVersion;
import com.reportix.aisolate.backend.domain.airlock.VersionFormDTO;
import com.reportix.aisolate.backend.infrastructure.cluster.ClusterSyncService;
import com.reportix.aisolate.backend.infrastructure.security.Access;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
 * The airlocks of a space and their versions as the AIsolate UI edits them, under {@code
 * /spaces/{space}/airlocks}, a version by its name. Every write is applied to the cluster before it
 * answers ({@link ClusterHeader}). Airlock ids are unique across spaces, which keeps the airlock
 * pods' paths flat. A version's code comes from {@code aisolate deploy}, never from here.
 */
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class AirlockController {

  private final AirlockService airlockService;
  private final ClusterSyncService clusterSync;

  // AIsolate UI: every method is the space's developers' (and the admin's), scoped to one space.

  @GetMapping("/spaces/{spaceId}/airlocks")
  @PreAuthorize(Access.DEVELOPER)
  public List<Airlock> list(@PathVariable String spaceId) {
    return airlockService.listAirlocks(spaceId);
  }

  @GetMapping("/spaces/{spaceId}/airlocks/{id}")
  @PreAuthorize(Access.DEVELOPER)
  public Airlock get(@PathVariable String spaceId, @PathVariable String id) {
    return airlockService.findAirlock(spaceId, id);
  }

  @PostMapping("/spaces/{spaceId}/airlocks")
  @PreAuthorize(Access.DEVELOPER)
  public ResponseEntity<Airlock> create(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String spaceId,
      @RequestBody AirlockFormDTO form) {
    Airlock created = airlockService.createAirlock(spaceId, form, Creator.of(jwt.getClaims()));
    return ClusterHeader.synced(clusterSync, HttpStatus.CREATED, spaceId, created);
  }

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

  @GetMapping("/spaces/{spaceId}/airlocks/{id}/versions")
  @PreAuthorize(Access.DEVELOPER)
  public List<AirlockVersion> listVersions(@PathVariable String spaceId, @PathVariable String id) {
    return airlockService.listVersions(spaceId, id);
  }

  /** Its name is fixed from then on; it has no code until a deploy. */
  @PostMapping("/spaces/{spaceId}/airlocks/{id}/versions")
  @PreAuthorize(Access.DEVELOPER)
  public ResponseEntity<AirlockVersion> createVersion(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable String spaceId,
      @PathVariable String id,
      @RequestBody VersionFormDTO form) {
    AirlockVersion created =
        airlockService.createVersion(spaceId, id, form, Creator.of(jwt.getClaims()));
    return ClusterHeader.synced(clusterSync, HttpStatus.CREATED, spaceId, created);
  }

  @GetMapping("/spaces/{spaceId}/airlocks/{id}/versions/{version}")
  @PreAuthorize(Access.DEVELOPER)
  public AirlockVersion getVersion(
      @PathVariable String spaceId, @PathVariable String id, @PathVariable String version) {
    return airlockService.findVersion(spaceId, id, version);
  }

  /** Its name and code stay; each save counts one revision. */
  @PutMapping("/spaces/{spaceId}/airlocks/{id}/versions/{version}")
  @PreAuthorize(Access.DEVELOPER)
  public ResponseEntity<AirlockVersion> updateVersion(
      @PathVariable String spaceId,
      @PathVariable String id,
      @PathVariable String version,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody VersionFormDTO form) {
    AirlockVersion updated = airlockService.updateVersion(spaceId, id, version, form, ifMatch);
    return ClusterHeader.synced(clusterSync, HttpStatus.OK, spaceId, updated);
  }
}
