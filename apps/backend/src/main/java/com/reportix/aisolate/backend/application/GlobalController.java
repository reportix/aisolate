package com.reportix.aisolate.backend.application;

import com.reportix.aisolate.backend.domain.Creator;
import com.reportix.aisolate.backend.domain.airlock.DataSourceFormDTO;
import com.reportix.aisolate.backend.domain.airlock.LlmFormDTO;
import com.reportix.aisolate.backend.domain.global.GlobalDataSource;
import com.reportix.aisolate.backend.domain.global.GlobalLlm;
import com.reportix.aisolate.backend.domain.global.GlobalService;
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
 * The install's LLMs and data sources, which every space inherits: the admin edits them; a
 * developer of any space reads them, to name them from an airlock's code. Every write is applied to
 * the cluster as the Global before it answers ({@link ClusterHeader}).
 */
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class GlobalController {

  private final GlobalService globalService;
  private final ClusterSyncService clusterSync;

  @GetMapping("/llms")
  @PreAuthorize(Access.DEVELOPER_ANYWHERE)
  public List<GlobalLlm> llms() {
    return globalService.llms();
  }

  @GetMapping("/llms/{id}")
  @PreAuthorize(Access.DEVELOPER_ANYWHERE)
  public GlobalLlm llm(@PathVariable String id) {
    return globalService.llm(id);
  }

  /** {@code api_key} is write-only: an OpenAI-compatible model's, it becomes the row's Secret. */
  @PostMapping("/llms")
  @PreAuthorize(Access.ADMIN)
  public ResponseEntity<GlobalLlm> createLlm(
      @AuthenticationPrincipal Jwt jwt, @RequestBody LlmFormDTO form) {
    return ClusterHeader.globalSynced(
        clusterSync,
        HttpStatus.CREATED,
        globalService.createLlm(form, Creator.of(jwt.getClaims())));
  }

  @PutMapping("/llms/{id}")
  @PreAuthorize(Access.ADMIN)
  public ResponseEntity<GlobalLlm> updateLlm(
      @PathVariable String id,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody LlmFormDTO form) {
    return ClusterHeader.globalSynced(
        clusterSync, HttpStatus.OK, globalService.updateLlm(id, form, ifMatch));
  }

  @DeleteMapping("/llms/{id}")
  @PreAuthorize(Access.ADMIN)
  public ResponseEntity<Void> deleteLlm(@PathVariable String id) {
    globalService.deleteLlm(id);
    return ClusterHeader.globalSynced(clusterSync, HttpStatus.NO_CONTENT, null);
  }

  @GetMapping("/data-sources")
  @PreAuthorize(Access.DEVELOPER_ANYWHERE)
  public List<GlobalDataSource> dataSources() {
    return globalService.dataSources();
  }

  @GetMapping("/data-sources/{id}")
  @PreAuthorize(Access.DEVELOPER_ANYWHERE)
  public GlobalDataSource dataSource(@PathVariable String id) {
    return globalService.dataSource(id);
  }

  /** {@code username} and {@code password} are write-only: they become the row's Secret. */
  @PostMapping("/data-sources")
  @PreAuthorize(Access.ADMIN)
  public ResponseEntity<GlobalDataSource> createDataSource(
      @AuthenticationPrincipal Jwt jwt, @RequestBody DataSourceFormDTO form) {
    return ClusterHeader.globalSynced(
        clusterSync,
        HttpStatus.CREATED,
        globalService.createDataSource(form, Creator.of(jwt.getClaims())));
  }

  @PutMapping("/data-sources/{id}")
  @PreAuthorize(Access.ADMIN)
  public ResponseEntity<GlobalDataSource> updateDataSource(
      @PathVariable String id,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody DataSourceFormDTO form) {
    return ClusterHeader.globalSynced(
        clusterSync, HttpStatus.OK, globalService.updateDataSource(id, form, ifMatch));
  }

  @DeleteMapping("/data-sources/{id}")
  @PreAuthorize(Access.ADMIN)
  public ResponseEntity<Void> deleteDataSource(@PathVariable String id) {
    globalService.deleteDataSource(id);
    return ClusterHeader.globalSynced(clusterSync, HttpStatus.NO_CONTENT, null);
  }
}
