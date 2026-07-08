package com.reportix.aisolate.backend.infrastructure.cluster;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The cluster the backend applies into. {@code namespace} empty means no cluster: the laptop, where
 * a deploy ends in the database.
 *
 * @param namespace the data-plane namespace (the release's)
 */
@ConfigurationProperties("aisolate.cluster")
public record ClusterProperties(String namespace) {
  public boolean enabled() {
    return namespace != null && !namespace.isBlank();
  }
}
