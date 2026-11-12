package com.reportix.aisolate.controller.reconciler;

import static com.reportix.aisolate.controller.support.TestResources.FIXTURES;
import static com.reportix.aisolate.controller.support.TestResources.NS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.reportix.aisolate.config.GlobalDocument;
import com.reportix.aisolate.config.Llm;
import com.reportix.aisolate.config.SpaceDocument;
import com.reportix.aisolate.controller.api.v1.Airlock;
import com.reportix.aisolate.controller.api.v1.AirlockVersion;
import com.reportix.aisolate.controller.api.v1.Global;
import com.reportix.aisolate.controller.api.v1.ResourceStatus;
import com.reportix.aisolate.controller.api.v1.Space;
import com.reportix.aisolate.controller.reconciler.airlockversion.AirlockVersionReconciler;
import com.reportix.aisolate.controller.render.Renderer;
import com.reportix.aisolate.controller.support.TestResources;
import com.reportix.aisolate.controller.support.TestSettings;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.StatusBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ErrorStatusUpdateControl;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The version reconciler against a CRUD-mode mock API server: what one pass applies, what it
 * deletes, what it reports. The JOSDK context is a mock: the reconciler reaches the API through the
 * client it was built with.
 */
@EnableKubernetesMockClient(crud = true)
class AirlockVersionReconcilerTest {

  private static final String HR_V1 = "hr-assistant-v1";
  private static final String HR_POD = "hr-acme-hr-assistant-v1-airlock";
  private static final String HR_MCP = "hr-acme-hr-assistant-v1-mcp-server";
  private static final String HR_CONFIG = "hr-acme-hr-assistant-v1-config";
  private static final String FF_V1 = "founders-finance-v1";
  private static final String FF_POD = "hr-acme-founders-finance-v1-airlock";

  KubernetesClient client;
  private AirlockVersionReconciler reconciler;

  @BeforeEach
  void reconciler() {
    reconciler =
        new AirlockVersionReconciler(
            new Releases(
                client,
                new Renderer(TestSettings.SETTINGS),
                NS,
                Releases.BY_UPDATE,
                TestSettings.SETTINGS));
  }

  @Test
  void givenRelease_whenVersionReconciled_thenItsObjectsExistOwnedByItAndStatusPending() {
    // Arrange
    TestResources.applyFixtures(client);

    // Act
    UpdateControl<AirlockVersion> control = reconciler.reconcile(get(HR_V1), context());

    // Assert: this version's pair and ConfigMap, nothing of its sibling
    Deployment deployment = deployment(HR_POD);
    assertThat(deployment.getMetadata().getOwnerReferences())
        .singleElement()
        .satisfies(owner -> {
          assertThat(owner.getKind()).isEqualTo("AirlockVersion");
          assertThat(owner.getName()).isEqualTo(HR_V1);
        });
    assertThat(deployment(HR_MCP)).isNotNull();
    assertThat(client.services().inNamespace(NS).withName(HR_MCP).get()).isNotNull();
    assertThat(client.network().v1().ingresses().inNamespace(NS).withName(HR_POD).get())
        .isNotNull();
    assertThat(client.configMaps().inNamespace(NS).withName(HR_CONFIG).get().getData())
        .containsOnlyKeys(
            "Global-aisolate.yaml",
            "Space-hr-acme.yaml",
            "Airlock-hr-assistant.yaml",
            "AirlockVersion-hr-assistant-v1.yaml");
    assertThat(deployment(FF_POD)).isNull();
    ResourceStatus status = control.getResource().orElseThrow().getStatus();
    assertThat(status.phase()).isEqualTo(ResourceStatus.PENDING);
    assertThat(status.revision())
        .isEqualTo(Renderer.selectServedDocuments(FIXTURES, HR_V1).revision());
    assertThat(status.conditions())
        .singleElement()
        .satisfies(c -> assertThat(c.getType()).isEqualTo("Ready"));
  }

  @Test
  void givenVersionDropsItsTools_whenReconciled_thenItsMcpServerGoesAndTheRestStays() {
    // Arrange: a first pass, a sibling's pass, the jar ConfigMap of the backend's
    TestResources.applyFixtures(client);
    reconciler.reconcile(get(HR_V1), context());
    reconciler.reconcile(get(FF_V1), context());
    client
        .resource(
            new ConfigMapBuilder()
                .withNewMetadata()
                .withName("hr-acme-hr-assistant-v1-jar")
                .withNamespace(NS)
                .withLabels(Map.of(Renderer.SPACE, "hr-acme"))
                .endMetadata()
                .build())
        .create();
    // The code stays deployed; the model may call none of its tools any more
    TestResources.updateVersion(client, HR_V1, s -> TestResources.withTools(s, List.of()));

    // Act
    reconciler.reconcile(get(HR_V1), context());

    // Assert: the render no longer produces the MCP server, so its objects are deleted; its
    // airlock pod, its jar and the sibling's pod stay
    assertThat(deployment(HR_MCP)).isNull();
    assertThat(client.services().inNamespace(NS).withName(HR_MCP).get()).isNull();
    assertThat(client.network().v1().networkPolicies().inNamespace(NS).withName(HR_MCP).get())
        .isNull();
    assertThat(deployment(HR_POD)).isNotNull();
    assertThat(deployment(FF_POD)).isNotNull();
    assertThat(client.configMaps().inNamespace(NS).withName("hr-acme-hr-assistant-v1-jar").get())
        .isNotNull();
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "its airlock deleted, its Airlock hr-assistant does not exist",
    "its space deleted, the Space of its Airlock hr-assistant does not exist",
    "its spec removed, spec.airlock is required"
  })
  void givenItNamesNoExistingAirlock_whenReconciled_thenPendingOrphanedAndItsObjectsDeleted(
      String change, String problem) {
    // Arrange: a first pass, then hr-assistant or hr-acme goes before its version does, or the
    // spec goes
    TestResources.applyFixtures(client);
    reconciler.reconcile(get(HR_V1), context());
    switch (change) {
      case "its airlock deleted" ->
          client.resources(Airlock.class).inNamespace(NS).withName("hr-assistant").delete();
      case "its space deleted" ->
          client.resources(Space.class).inNamespace(NS).withName("hr-acme").delete();
      default -> {
        AirlockVersion version = get(HR_V1);
        version.setSpec(null);
        client.resource(version).update();
      }
    }

    // Act
    UpdateControl<AirlockVersion> control = reconciler.reconcile(get(HR_V1), context());

    // Assert: a version of no airlock is not an error, and it runs nothing
    ResourceStatus status = control.getResource().orElseThrow().getStatus();
    assertThat(status.phase()).isEqualTo(ResourceStatus.PENDING);
    assertThat(status.problems()).containsExactly(problem);
    assertNothingRuns(HR_POD, HR_MCP, HR_CONFIG);
  }

  @Test
  void givenReleaseBecomesInvalid_whenReconciled_thenInvalidWithItsProblemsAndNothingOfItRuns() {
    // Arrange: the space comes to list a blank role
    TestResources.applyFixtures(client);
    reconciler.reconcile(get(HR_V1), context());
    narrowSpaceRoles(List.of(" "));

    // Act
    UpdateControl<AirlockVersion> control = reconciler.reconcile(get(HR_V1), context());

    // Assert: fails closed, since its pods would keep the last good access lists
    ResourceStatus status = control.getResource().orElseThrow().getStatus();
    assertThat(status.phase()).isEqualTo(ResourceStatus.INVALID);
    assertThat(status.problems()).hasSize(2).last().isEqualTo(Releases.HELD_BACK);
    assertThat(status.problems().getFirst())
        .contains("release invalid: Space/hr-acme")
        .contains("spec.access.roles");
    assertNothingRuns(HR_POD, HR_MCP, HR_CONFIG);
  }

  @Test
  void givenItsAirlockNamesAnUnknownProvider_whenReconciled_thenInvalidNamingItAndNothingRuns() {
    // Arrange: a first pass, then the loader's problem on its airlock
    TestResources.applyFixtures(client);
    reconciler.reconcile(get(FF_V1), context());
    TestResources.updateAirlock(
        client,
        "founders-finance",
        s ->
            TestResources.withLlms(
                s,
                List.of(new Llm("gemma", "gpt", "http://host.minikube.internal:11434", "gemma3"))));

    // Act
    UpdateControl<AirlockVersion> control = reconciler.reconcile(get(FF_V1), context());

    // Assert: the version is left out with its airlock
    ResourceStatus status = control.getResource().orElseThrow().getStatus();
    assertThat(status.phase()).isEqualTo(ResourceStatus.INVALID);
    assertThat(status.problems())
        .containsExactly("its Airlock founders-finance is invalid", Releases.HELD_BACK);
    assertThat(deployment(FF_POD)).isNull();
  }

  @Test
  void givenVersionBreaksAndARoleLeavesTheSpace_whenBothReconciled_thenNeitherAdmitsItsHolders() {
    // Arrange: both versions run; then founders-finance-v1 breaks and the ceo role leaves the
    // space, the only list that let its holders ask founders-finance
    TestResources.applyFixtures(client);
    reconciler.reconcile(get(HR_V1), context());
    reconciler.reconcile(get(FF_V1), context());
    assertThat(deployment(FF_POD)).isNotNull();
    TestResources.breakJarDigest(client, FF_V1);
    narrowSpaceRoles(List.of("hr"));

    // Act
    UpdateControl<AirlockVersion> sibling = reconciler.reconcile(get(HR_V1), context());
    UpdateControl<AirlockVersion> held = reconciler.reconcile(get(FF_V1), context());

    // Assert: the valid sibling serves the narrowed list; the broken one serves no one
    assertThat(sibling.getResource().orElseThrow().getStatus().phase())
        .isNotEqualTo(ResourceStatus.INVALID);
    String served =
        client
            .configMaps()
            .inNamespace(NS)
            .withName(HR_CONFIG)
            .get()
            .getData()
            .get("Space-hr-acme.yaml");
    assertThat(served).contains("- hr").doesNotContain("ceo");
    ResourceStatus status = held.getResource().orElseThrow().getStatus();
    assertThat(status.phase()).isEqualTo(ResourceStatus.INVALID);
    assertThat(status.problems()).last().isEqualTo(Releases.HELD_BACK);
    assertNothingRuns(
        FF_POD, "hr-acme-founders-finance-v1-mcp-server", "hr-acme-founders-finance-v1-config");
  }

  @Test
  void givenApplyRejected_whenErrorStatusUpdated_thenInvalidWithTheServersMessage() {
    // Arrange
    TestResources.applyFixtures(client);
    KubernetesClientException rejected =
        new KubernetesClientException(
            new StatusBuilder()
                .withCode(422)
                .withMessage(
                    "Deployment.apps \""
                        + HR_POD
                        + "\" is invalid: spec.selector: field is immutable")
                .build());

    // Act
    ErrorStatusUpdateControl<AirlockVersion> control =
        reconciler.updateErrorStatus(get(HR_V1), context(), rejected);

    // Assert: the status says the apply failed and why, instead of keeping the last good one
    ResourceStatus status = control.getResource().orElseThrow().getStatus();
    assertThat(status.phase()).isEqualTo(ResourceStatus.INVALID);
    assertThat(status.problems())
        .singleElement()
        .satisfies(p -> assertThat(p).contains("apply failed").contains("field is immutable"));
  }

  @Test
  void givenGlobalLlmChanged_whenReconciled_thenTheVersionsRevisionAndCatalogFollow() {
    // Arrange: hr-assistant-v1 runs the global granite; the admin moves it to another model
    TestResources.applyFixtures(client);
    String before =
        reconciler
            .reconcile(get(HR_V1), context())
            .getResource()
            .orElseThrow()
            .getStatus()
            .revision();
    Global global = client.resources(Global.class).inNamespace(NS).withName("aisolate").get();
    GlobalDocument.Spec g = global.getSpec();
    Llm granite = g.llms().getFirst();
    global.setSpec(
        new GlobalDocument.Spec(
            List.of(
                new Llm(granite.id(), granite.provider(), granite.url(), "granite4.1:3b"),
                g.llms().getLast()),
            g.dataSources()));
    client.resource(global).update();

    // Act
    ResourceStatus status =
        reconciler.reconcile(get(HR_V1), context()).getResource().orElseThrow().getStatus();

    // Assert: a global change rolls the version's pods
    assertThat(status.revision()).isNotEqualTo(before);
    assertThat(
            deployment(HR_POD)
                .getSpec()
                .getTemplate()
                .getSpec()
                .getContainers()
                .getFirst()
                .getEnv()
                .stream()
                .filter(e -> e.getName().equals("AISOLATE_LLM_0_MODEL"))
                .findFirst()
                .orElseThrow()
                .getValue())
        .isEqualTo("granite4.1:3b");
  }

  /** The space hr-acme as the cluster holds it, its roles replaced. */
  private void narrowSpaceRoles(List<String> roles) {
    SpaceDocument.Spec s = FIXTURES.spaces().get("hr-acme").spec();
    Space space = client.resources(Space.class).inNamespace(NS).withName("hr-acme").get();
    space.setSpec(
        new SpaceDocument.Spec(
            s.displayName(),
            s.description(),
            new SpaceDocument.Access(roles),
            s.llms(),
            s.dataSources()));
    client.resource(space).update();
  }

  @SuppressWarnings("unchecked")
  private static Context<AirlockVersion> context() {
    return mock(Context.class);
  }

  private AirlockVersion get(String name) {
    return client.resources(AirlockVersion.class).inNamespace(NS).withName(name).get();
  }

  /** No pod, no Service and no Ingress rule: nothing answers for it. */
  private void assertNothingRuns(String airlockPod, String mcpServer, String configMap) {
    assertThat(deployment(airlockPod)).isNull();
    assertThat(deployment(mcpServer)).isNull();
    assertThat(client.services().inNamespace(NS).withName(airlockPod).get()).isNull();
    assertThat(client.network().v1().ingresses().inNamespace(NS).withName(airlockPod).get())
        .isNull();
    assertThat(client.configMaps().inNamespace(NS).withName(configMap).get()).isNull();
  }

  private Deployment deployment(String name) {
    return client.apps().deployments().inNamespace(NS).withName(name).get();
  }
}
