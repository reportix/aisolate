package com.reportix.aisolate.backend.application;

import com.reportix.aisolate.backend.infrastructure.cluster.ClusterSyncService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * An AIsolate UI write's answer: the saved row, and with a cluster {@value #NAME}: {@code applied}
 * or {@code not applied}, so the UI can say "saved, not applied" rather than let a revoked role
 * keep asking unnoticed.
 */
final class ClusterHeader {

  static final String NAME = "AIsolate-Cluster";

  static <T> ResponseEntity<T> synced(
      ClusterSyncService clusterSync, HttpStatus status, String spaceId, T body) {
    ResponseEntity.BodyBuilder response = ResponseEntity.status(status);
    clusterSync
        .syncAfterWrite(spaceId)
        .ifPresent(applied -> response.header(NAME, applied ? "applied" : "not applied"));
    return response.body(body);
  }

  /** {@link #synced} for a write of the install's Global. */
  static <T> ResponseEntity<T> globalSynced(
      ClusterSyncService clusterSync, HttpStatus status, T body) {
    ResponseEntity.BodyBuilder response = ResponseEntity.status(status);
    clusterSync
        .syncGlobalAfterWrite()
        .ifPresent(applied -> response.header(NAME, applied ? "applied" : "not applied"));
    return response.body(body);
  }

  private ClusterHeader() {}
}
