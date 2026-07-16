package com.reportix.aisolate.controller.reconciler;

import static org.assertj.core.api.Assertions.assertThat;

import com.reportix.aisolate.controller.render.Renderer;
import com.reportix.aisolate.controller.support.TestSettings;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Ready means the new pods serve, not that some pod of the old template still does; a prune lists
 * one airlock's objects, not the namespace's.
 */
@EnableKubernetesMockClient(crud = true)
class ReleasesTest {

  KubernetesClient client;

  @ParameterizedTest
  @CsvSource({"0, 1, 0, false", "1, 1, 0, true", "1, 0, 1, false"})
  void givenDeploymentStatus_whenRolloutRead_thenCompleteOnlyWhenTheNewPodsServe(
      int updated, int ready, int unavailable, boolean complete) {
    // Arrange
    Releases releases = releases();
    Deployment deployment =
        new DeploymentBuilder()
            .withNewMetadata()
            .withName("airlock-x")
            .withNamespace("aisolate")
            .withGeneration(1L)
            .endMetadata()
            .withNewSpec()
            .withReplicas(1)
            .withNewSelector()
            .addToMatchLabels("a", "b")
            .endSelector()
            .withNewTemplate()
            .withNewMetadata()
            .addToLabels("a", "b")
            .endMetadata()
            .withNewSpec()
            .addNewContainer()
            .withName("c")
            .withImage("i")
            .endContainer()
            .endSpec()
            .endTemplate()
            .endSpec()
            .withNewStatus()
            .withObservedGeneration(1L)
            .withUpdatedReplicas(updated)
            .withReadyReplicas(ready)
            .withUnavailableReplicas(unavailable)
            .endStatus()
            .build();
    client.resource(deployment).create();

    // Act
    Releases.Rollout rollout = releases.readRollout("airlock-x");

    // Assert
    assertThat(rollout.complete()).isEqualTo(complete);
    assertThat(rollout.readyReplicas()).isEqualTo(ready);
  }

  @Test
  void givenNoDeployment_whenRolloutRead_thenNotCompleteAndNoReplicas() {
    // Arrange / Act
    Releases.Rollout rollout = releases().readRollout("airlock-missing");

    // Assert
    assertThat(rollout.complete()).isFalse();
    assertThat(rollout.readyReplicas()).isNull();
  }

  @Test
  void givenObjectsOfOneOwner_whenPruned_thenOnlyThoseLabelledForThatAirlockAndTheControllerGo() {
    // Arrange: three objects owned by Airlock hr-assistant, none of them in its render
    Releases releases = releases();
    client
        .resource(
            ownedConfigMap(
                "stale",
                Map.of(Renderer.MANAGED_BY, "controller", Renderer.AIRLOCK, "hr-assistant")))
        .create();
    client
        .resource(
            ownedConfigMap(
                "other-airlock",
                Map.of(Renderer.MANAGED_BY, "controller", Renderer.AIRLOCK, "founders-finance")))
        .create();
    client
        .resource(ownedConfigMap("not-the-controllers", Map.of(Renderer.AIRLOCK, "hr-assistant")))
        .create();

    // Act
    releases.deleteOwnedExcept("Airlock", "hr-assistant", List.of());

    // Assert: the list takes both labels, the controller's and the airlock's
    assertThat(client.configMaps().inNamespace("aisolate").list().getItems())
        .extracting(m -> m.getMetadata().getName())
        .containsExactlyInAnyOrder("other-airlock", "not-the-controllers");
  }

  private static ConfigMap ownedConfigMap(String name, Map<String, String> labels) {
    return new ConfigMapBuilder()
        .withNewMetadata()
        .withName(name)
        .withNamespace("aisolate")
        .withLabels(labels)
        .addNewOwnerReference()
        .withApiVersion("aisolate.dev/v1")
        .withKind("Airlock")
        .withName("hr-assistant")
        .withUid("uid-hr")
        .endOwnerReference()
        .endMetadata()
        .build();
  }

  private Releases releases() {
    return new Releases(
        client,
        new Renderer(TestSettings.SETTINGS),
        "aisolate",
        Releases.BY_UPDATE,
        TestSettings.SETTINGS);
  }
}
