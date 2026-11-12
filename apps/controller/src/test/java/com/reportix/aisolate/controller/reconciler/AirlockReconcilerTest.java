package com.reportix.aisolate.controller.reconciler;

import static com.reportix.aisolate.controller.support.TestResources.FIXTURES;
import static com.reportix.aisolate.controller.support.TestResources.NS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.reportix.aisolate.config.Llm;
import com.reportix.aisolate.controller.api.v1.Airlock;
import com.reportix.aisolate.controller.api.v1.ResourceStatus;
import com.reportix.aisolate.controller.reconciler.airlock.AirlockReconciler;
import com.reportix.aisolate.controller.render.Renderer;
import com.reportix.aisolate.controller.support.TestResources;
import com.reportix.aisolate.controller.support.TestSettings;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The airlock reconciler reports its space's release and writes nothing else, except that it sweeps
 * the pod pair an airlock ran by itself before versions.
 */
@EnableKubernetesMockClient(crud = true)
class AirlockReconcilerTest {

  KubernetesClient client;
  private AirlockReconciler reconciler;

  @BeforeEach
  void reconciler() {
    reconciler =
        new AirlockReconciler(
            new Releases(
                client,
                new Renderer(TestSettings.SETTINGS),
                NS,
                Releases.BY_UPDATE,
                TestSettings.SETTINGS));
  }

  @Test
  void givenValidRelease_whenReconciled_thenReadyAtTheReleaseRevisionAndNothingApplied() {
    // Arrange
    TestResources.applyFixtures(client);

    // Act
    UpdateControl<Airlock> control = reconciler.reconcile(get("hr-assistant"), context());

    // Assert
    ResourceStatus status = control.getResource().orElseThrow().getStatus();
    assertThat(status.phase()).isEqualTo(ResourceStatus.READY);
    assertThat(status.revision()).isEqualTo(FIXTURES.revision());
    assertThat(client.apps().deployments().inNamespace(NS).list().getItems()).isEmpty();
    assertThat(client.configMaps().inNamespace(NS).list().getItems()).isEmpty();
  }

  @Test
  void givenItsLlmNamesAnUnknownProvider_whenReconciled_thenInvalidNamingIt() {
    // Arrange
    TestResources.applyFixtures(client);
    TestResources.updateAirlock(
        client,
        "founders-finance",
        s ->
            TestResources.withLlms(
                s,
                List.of(new Llm("gemma", "gpt", "http://host.minikube.internal:11434", "gemma3"))));

    // Act
    UpdateControl<Airlock> control = reconciler.reconcile(get("founders-finance"), context());

    // Assert
    ResourceStatus status = control.getResource().orElseThrow().getStatus();
    assertThat(status.phase()).isEqualTo(ResourceStatus.INVALID);
    assertThat(status.problems())
        .singleElement()
        .satisfies(p ->
            assertThat(p).contains("spec.llms[0].provider gpt is not one of [ollama, openai]"));
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "founders-finance, AirlockVersion founders-finance-v1 is held back",
    "hr-assistant, ''"
  })
  void givenOneVersionInvalid_whenItsAirlockReconciled_thenReadyNamingItThereOnly(
      String airlock, String heldBack) {
    // Arrange
    TestResources.applyFixtures(client);
    TestResources.breakJarDigest(client, "founders-finance-v1");

    // Act
    UpdateControl<Airlock> control = reconciler.reconcile(get(airlock), context());

    // Assert: the sibling airlock is not told about another airlock's version
    ResourceStatus status = control.getResource().orElseThrow().getStatus();
    assertThat(status.phase()).isEqualTo(ResourceStatus.READY);
    if (heldBack.isEmpty()) {
      assertThat(status.problems()).isEmpty();
    } else {
      assertThat(status.problems()).singleElement().asString().startsWith(heldBack);
    }
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "a Space that does not exist, its Space hr-acme does not exist",
    "no spec, spec.space is required"
  })
  void givenItNamesNoExistingSpace_whenReconciled_thenPendingOrphaned(
      String names, String problem) {
    // Arrange
    Airlock airlock = TestResources.airlocks().getFirst();
    if (names.equals("no spec")) {
      airlock.setSpec(null);
    }
    client.resource(airlock).create();

    // Act
    UpdateControl<Airlock> control = reconciler.reconcile(get("hr-assistant"), context());

    // Assert
    ResourceStatus status = control.getResource().orElseThrow().getStatus();
    assertThat(status.phase()).isEqualTo(ResourceStatus.PENDING);
    assertThat(status.problems()).containsExactly(problem);
  }

  @Test
  void givenObjectsOfThePreVersionPodPair_whenReconciled_thenOnlyWhatTheAirlockOwnsIsSwept() {
    // Arrange: an install upgraded keeps the pair the airlock itself owned; a version's ConfigMap
    // and a sibling's stay
    TestResources.applyFixtures(client);
    client.resource(ownedBy("Airlock", "hr-assistant", "hr-acme-hr-assistant-config")).create();
    client
        .resource(ownedBy("AirlockVersion", "hr-assistant-v1", "hr-acme-hr-assistant-v1-config"))
        .create();
    client
        .resource(ownedBy("Airlock", "founders-finance", "hr-acme-founders-finance-config"))
        .create();

    // Act
    reconciler.reconcile(get("hr-assistant"), context());

    // Assert
    assertThat(client.configMaps().inNamespace(NS).list().getItems())
        .extracting(m -> m.getMetadata().getName())
        .containsExactlyInAnyOrder(
            "hr-acme-hr-assistant-v1-config", "hr-acme-founders-finance-config");
  }

  private static ConfigMap ownedBy(String kind, String owner, String name) {
    String airlock = kind.equals("Airlock") ? owner : owner.replaceAll("-v\\d+$", "");
    return new ConfigMapBuilder()
        .withNewMetadata()
        .withName(name)
        .withNamespace(NS)
        .withLabels(Map.of(Renderer.MANAGED_BY, "controller", Renderer.AIRLOCK, airlock))
        .addNewOwnerReference()
        .withApiVersion("aisolate.dev/v1")
        .withKind(kind)
        .withName(owner)
        .withUid("uid-" + owner)
        .endOwnerReference()
        .endMetadata()
        .build();
  }

  @SuppressWarnings("unchecked")
  private static Context<Airlock> context() {
    return mock(Context.class);
  }

  private Airlock get(String name) {
    return client.resources(Airlock.class).inNamespace(NS).withName(name).get();
  }
}
