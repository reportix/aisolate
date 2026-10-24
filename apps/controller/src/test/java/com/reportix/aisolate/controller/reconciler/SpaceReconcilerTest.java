package com.reportix.aisolate.controller.reconciler;

import static com.reportix.aisolate.controller.support.TestResources.FIXTURES;
import static com.reportix.aisolate.controller.support.TestResources.NS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.reportix.aisolate.config.SpaceDocument;
import com.reportix.aisolate.controller.api.v1.ResourceStatus;
import com.reportix.aisolate.controller.api.v1.Space;
import com.reportix.aisolate.controller.reconciler.space.SpaceReconciler;
import com.reportix.aisolate.controller.render.Renderer;
import com.reportix.aisolate.controller.support.TestResources;
import com.reportix.aisolate.controller.support.TestSettings;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The space reconciler reports its release and is the one writer of discovery. */
@EnableKubernetesMockClient(crud = true)
class SpaceReconcilerTest {

  KubernetesClient client;
  private SpaceReconciler reconciler;

  @BeforeEach
  void reconciler() {
    reconciler =
        new SpaceReconciler(
            new Releases(
                client,
                new Renderer(TestSettings.SETTINGS),
                NS,
                Releases.BY_UPDATE,
                TestSettings.SETTINGS));
  }

  @Test
  void givenRelease_whenReconciled_thenReadyAndDiscoveryListsTheSpaceFromItsOwnPodsOwnedByIt() {
    // Arrange
    TestResources.applyFixtures(client);

    // Act
    UpdateControl<Space> control = reconciler.reconcile(get("hr-acme"), context());

    // Assert: no airlock's pod; discovery's own, beside what it lists
    ResourceStatus status = control.getResource().orElseThrow().getStatus();
    assertThat(status.phase()).isEqualTo(ResourceStatus.READY);
    assertThat(status.revision()).isEqualTo(FIXTURES.revision());
    assertThat(status.problems()).isEmpty();
    assertThat(client.apps().deployments().inNamespace(NS).list().getItems())
        .extracting(d -> d.getMetadata().getName())
        .containsExactly(Renderer.DISCOVERY);
    ConfigMap discovery = client.configMaps().inNamespace(NS).withName(Renderer.DISCOVERY).get();
    assertThat(discovery.getData()).containsOnlyKeys("hr-acme.json");
    // The cluster deletes them with the last Space: no pass would run to empty them
    List<HasMetadata> owned =
        List.of(
            discovery,
            client.apps().deployments().inNamespace(NS).withName(Renderer.DISCOVERY).get(),
            client
                .network()
                .v1()
                .networkPolicies()
                .inNamespace(NS)
                .withName(Renderer.DISCOVERY)
                .get());
    assertThat(owned)
        .allSatisfy(
            o ->
                assertThat(o.getMetadata().getOwnerReferences())
                    .singleElement()
                    .satisfies(
                        owner -> {
                          assertThat(owner.getKind()).isEqualTo("Space");
                          assertThat(owner.getName()).isEqualTo("hr-acme");
                          assertThat(owner.getController()).isNotEqualTo(Boolean.TRUE);
                        }));
  }

  @Test
  void givenOneAirlockInvalid_whenSpaceReconciled_thenReadyNamingTheAirlockHeldBack() {
    // Arrange
    TestResources.applyFixtures(client);
    TestResources.breakJarDigest(client, "founders-finance");

    // Act
    UpdateControl<Space> control = reconciler.reconcile(get("hr-acme"), context());

    // Assert
    ResourceStatus status = control.getResource().orElseThrow().getStatus();
    assertThat(status.phase()).isEqualTo(ResourceStatus.READY);
    assertThat(status.problems())
        .containsExactly("Airlock founders-finance is held back: " + Releases.HELD_BACK);
    assertThat(status.conditions().getFirst().getMessage()).contains("founders-finance");
  }

  @Test
  void givenBrokenSecondSpace_whenEachReconciled_thenItIsInvalidAndLeavesDiscoveryAlone() {
    // Arrange: a second space that lists a blank role
    TestResources.applyFixtures(client);
    client
        .resource(
            TestResources.space(
                "broken",
                new SpaceDocument.Spec(
                    "Broken", null, new SpaceDocument.Access(List.of(" ")), null, List.of())))
        .create();

    // Act
    UpdateControl<Space> broken = reconciler.reconcile(get("broken"), context());
    UpdateControl<Space> fine = reconciler.reconcile(get("hr-acme"), context());

    // Assert
    assertThat(broken.getResource().orElseThrow().getStatus().problems())
        .singleElement()
        .satisfies(p -> assertThat(p).contains("spec.access.roles must not contain a blank role"));
    assertThat(fine.getResource().orElseThrow().getStatus().phase())
        .isEqualTo(ResourceStatus.READY);
    assertThat(client.configMaps().inNamespace(NS).withName(Renderer.DISCOVERY).get().getData())
        .containsKey("hr-acme.json")
        .doesNotContainKey("broken.json");
  }

  @SuppressWarnings("unchecked")
  private static Context<Space> context() {
    return mock(Context.class);
  }

  private Space get(String name) {
    return client.resources(Space.class).inNamespace(NS).withName(name).get();
  }
}
