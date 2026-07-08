package com.reportix.aisolate.backend.infrastructure.cluster;

import static org.assertj.core.api.Assertions.assertThat;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Status;
import io.fabric8.kubernetes.api.model.StatusBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import java.util.Map;
import org.junit.jupiter.api.Test;

@EnableKubernetesMockClient
class ClusterSyncServiceTest {

  static KubernetesClient client; // injected by the mock server extension
  static KubernetesMockServer server;

  private static final String PATH = "/apis/aisolate.dev/v1/namespaces/aisolate/spaces";

  private static GenericKubernetesResource space(String resourceVersion) {
    GenericKubernetesResource space = new GenericKubernetesResource();
    space.setApiVersion("aisolate.dev/v1");
    space.setKind("Space");
    space.setMetadata(
        new ObjectMetaBuilder()
            .withName("acme")
            .withNamespace("aisolate")
            .withResourceVersion(resourceVersion)
            .build());
    space.setAdditionalProperty("spec", Map.of("displayName", "Acme"));
    return space;
  }

  private static Status conflict(String reason) {
    return new StatusBuilder().withCode(409).withReason(reason).withStatus("Failure").build();
  }

  @Test
  void givenStatusWriteBetweenReadAndPut_whenCreateOrUpdate_thenRetriesOverLatestVersion()
      throws InterruptedException {
    // Arrange: the Space exists; the controller bumps it once between the update's GET and PUT
    server.expect().post().withPath(PATH).andReturn(409, conflict("AlreadyExists")).always();
    server.expect().get().withPath(PATH + "/acme").andReturn(200, space("1")).once();
    server.expect().get().withPath(PATH + "/acme").andReturn(200, space("2")).always();
    server.expect().put().withPath(PATH + "/acme").andReturn(409, conflict("Conflict")).once();
    server.expect().put().withPath(PATH + "/acme").andReturn(200, space("3")).once();

    // Act
    ClusterSyncService.createOrUpdate(
        client
            .genericKubernetesResources(ClusterSyncService.SPACES)
            .inNamespace("aisolate")
            .resource(space(null)));

    // Assert: create, read, PUT refused; create, read again, PUT applied
    assertThat(server.getRequestCount()).isEqualTo(6);
    assertThat(server.getLastRequest().getMethod()).isEqualTo("PUT");
    assertThat(server.getLastRequest().getBody().readUtf8()).contains("\"resourceVersion\":\"2\"");
  }
}
