package com.reportix.aisolate.controller.reconciler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.reportix.aisolate.controller.render.Renderer;
import com.reportix.aisolate.controller.support.TestSettings;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.fabric8.mockwebserver.http.RecordedRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The real server's write path: expectations, not the CRUD mock, which speaks no apply-patch. */
@EnableKubernetesMockClient
class ReleasesApplyTest {

  KubernetesMockServer server;
  KubernetesClient client;

  @Test
  void givenMissingObject_whenApplied_thenOneApplyPatchUnderTheControllerCreatesIt()
      throws InterruptedException {
    // Arrange
    ConfigMap map =
        new ConfigMapBuilder()
            .withNewMetadata()
            .withName("aisolate-config")
            .endMetadata()
            .addToData("k", "v")
            .build();
    server
        .expect()
        .patch()
        .withPath(
            "/api/v1/namespaces/aisolate/configmaps/aisolate-config"
                + "?fieldManager=aisolate-controller&force=true")
        .andReturn(200, map)
        .once();
    Releases releases =
        new Releases(
            client,
            new Renderer(TestSettings.SETTINGS),
            "aisolate",
            Releases.BY_SERVER_SIDE_APPLY,
            TestSettings.SETTINGS);

    // Act
    Throwable thrown = catchThrowable(() -> releases.apply(List.of(map)));

    // Assert: a create first would leave its fields to the client's own manager, out of reach of
    // every later apply, so a field the renderer stops emitting would stay on the object
    RecordedRequest first = server.takeRequest();
    assertThat(first.getMethod()).isEqualTo("PATCH");
    assertThat(first.getHeader("Content-Type")).startsWith("application/apply-patch+yaml");
    assertThat(thrown).isNull();
    assertThat(server.getRequestCount()).isEqualTo(1);
  }
}
