package com.reportix.aisolate.controller.reconciler;

import static com.reportix.aisolate.controller.support.TestResources.FIXTURES;
import static com.reportix.aisolate.controller.support.TestResources.NS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.reportix.aisolate.config.GlobalDocument;
import com.reportix.aisolate.config.Llm;
import com.reportix.aisolate.config.SpaceDocument;
import com.reportix.aisolate.controller.api.v1.Airlock;
import com.reportix.aisolate.controller.api.v1.Global;
import com.reportix.aisolate.controller.api.v1.ResourceStatus;
import com.reportix.aisolate.controller.api.v1.Space;
import com.reportix.aisolate.controller.reconciler.airlock.AirlockReconciler;
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
 * The airlock reconciler against a CRUD-mode mock API server: what one pass applies, what it
 * deletes, what it reports. The JOSDK context is a mock: the reconciler reaches the API through the
 * client it was built with.
 */
@EnableKubernetesMockClient(crud = true)
class AirlockReconcilerTest {

  private static final String HR = "hr-assistant";
  private static final String HR_POD = "hr-acme-hr-assistant-airlock";
  private static final String HR_MCP = "hr-acme-hr-assistant-mcp-server";
  private static final String HR_CONFIG = "hr-acme-hr-assistant-config";
  private static final String FF = "founders-finance";
  private static final String FF_POD = "hr-acme-founders-finance-airlock";

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
  void givenRelease_whenAirlockReconciled_thenItsObjectsExistOwnedByItAndStatusPending() {
    // Arrange
    TestResources.applyFixtures(client);

    // Act
    UpdateControl<Airlock> control = reconciler.reconcile(get(HR), context());

    // Assert: this airlock's pair and ConfigMap, nothing of its sibling
    Deployment deployment = deployment(HR_POD);
    assertThat(deployment.getMetadata().getOwnerReferences())
        .singleElement()
        .satisfies(
            owner -> {
              assertThat(owner.getKind()).isEqualTo("Airlock");
              assertThat(owner.getName()).isEqualTo(HR);
            });
    assertThat(deployment(HR_MCP)).isNotNull();
    assertThat(client.services().inNamespace(NS).withName(HR_MCP).get()).isNotNull();
    assertThat(client.network().v1().ingresses().inNamespace(NS).withName(HR_POD).get())
        .isNotNull();
    assertThat(client.configMaps().inNamespace(NS).withName(HR_CONFIG).get().getData())
        .containsOnlyKeys(
            "Global-aisolate.yaml", "Space-hr-acme.yaml", "Airlock-hr-assistant.yaml");
    assertThat(deployment(FF_POD)).isNull();
    ResourceStatus status = control.getResource().orElseThrow().getStatus();
    assertThat(status.phase()).isEqualTo(ResourceStatus.PENDING);
    assertThat(status.revision())
        .isEqualTo(Renderer.selectServedDocuments(FIXTURES, HR).revision());
    assertThat(status.conditions())
        .singleElement()
        .satisfies(c -> assertThat(c.getType()).isEqualTo("Ready"));
  }

  @Test
  void givenAirlockDropsItsTools_whenReconciled_thenItsMcpServerGoesAndTheRestStays() {
    // Arrange: a first pass, a sibling's pass, the jar ConfigMap of the backend's
    TestResources.applyFixtures(client);
    reconciler.reconcile(get(HR), context());
    reconciler.reconcile(get(FF), context());
    client
        .resource(
            new ConfigMapBuilder()
                .withNewMetadata()
                .withName("hr-acme-hr-assistant-jar")
                .withNamespace(NS)
                .withLabels(Map.of(Renderer.SPACE, "hr-acme"))
                .endMetadata()
                .build())
        .create();
    // The code stays deployed; the model may call none of its tools any more
    TestResources.updateAirlock(client, HR, s -> TestResources.withTools(s, List.of()));

    // Act
    reconciler.reconcile(get(HR), context());

    // Assert: the render no longer produces the MCP server, so its objects are deleted; its
    // airlock pod, its jar and the sibling's pod stay
    assertThat(deployment(HR_MCP)).isNull();
    assertThat(client.services().inNamespace(NS).withName(HR_MCP).get()).isNull();
    assertThat(client.network().v1().networkPolicies().inNamespace(NS).withName(HR_MCP).get())
        .isNull();
    assertThat(deployment(HR_POD)).isNotNull();
    assertThat(deployment(FF_POD)).isNotNull();
    assertThat(client.configMaps().inNamespace(NS).withName("hr-acme-hr-assistant-jar").get())
        .isNotNull();
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "its space deleted, its Space hr-acme does not exist",
    "its spec removed, spec.space is required"
  })
  void givenItNamesNoExistingSpace_whenReconciled_thenPendingAndItsObjectsDeleted(
      String change, String problem) {
    // Arrange: a first pass, then hr-acme goes before its airlocks do, or the spec goes
    TestResources.applyFixtures(client);
    reconciler.reconcile(get(HR), context());
    if (change.equals("its space deleted")) {
      client.resources(Space.class).inNamespace(NS).withName("hr-acme").delete();
    } else {
      Airlock airlock = get(HR);
      airlock.setSpec(null);
      client.resource(airlock).update();
    }

    // Act
    UpdateControl<Airlock> control = reconciler.reconcile(get(HR), context());

    // Assert: an airlock of no space is not an error, and it runs nothing
    ResourceStatus status = control.getResource().orElseThrow().getStatus();
    assertThat(status.phase()).isEqualTo(ResourceStatus.PENDING);
    assertThat(status.problems()).containsExactly(problem);
    assertNothingRuns(HR_POD, HR_MCP, HR_CONFIG);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "no llm, '', prompt, no llm: aisolate deploy brings it",
    "no prompt, granite, '', no prompt: aisolate deploy brings it"
  })
  void givenNoLlmOrPromptYet_whenReconciled_thenPendingSayingADeployBringsItAndNothingRuns(
      String change, String llm, String prompt, String problem) {
    // Arrange: a first pass, then the airlock as the AIsolate UI saves one before its first deploy
    TestResources.applyFixtures(client);
    reconciler.reconcile(get(HR), context());
    TestResources.updateAirlock(client, HR, s -> TestResources.withLlmAndPrompt(s, llm, prompt));

    // Act
    UpdateControl<Airlock> control = reconciler.reconcile(get(HR), context());

    // Assert: valid, not an error; its sibling's release is untouched
    ResourceStatus status = control.getResource().orElseThrow().getStatus();
    assertThat(status.phase()).isEqualTo(ResourceStatus.PENDING);
    assertThat(status.problems()).containsExactly(problem);
    assertNothingRuns(HR_POD, HR_MCP, HR_CONFIG);
    assertThat(reconciler.reconcile(get(FF), context()).getResource().orElseThrow().getStatus())
        .extracting(ResourceStatus::phase)
        .isEqualTo(ResourceStatus.PENDING);
    assertThat(deployment(FF_POD)).isNotNull();
  }

  @Test
  void givenReleaseBecomesInvalid_whenReconciled_thenInvalidWithItsProblemsAndNothingOfItRuns() {
    // Arrange: the space comes to list a blank role
    TestResources.applyFixtures(client);
    reconciler.reconcile(get(HR), context());
    SpaceDocument.Spec s = FIXTURES.spaces().get("hr-acme").spec();
    Space space = client.resources(Space.class).inNamespace(NS).withName("hr-acme").get();
    space.setSpec(
        new SpaceDocument.Spec(
            s.displayName(),
            s.description(),
            new SpaceDocument.Access(List.of(" ")),
            s.llms(),
            s.dataSources()));
    client.resource(space).update();

    // Act
    UpdateControl<Airlock> control = reconciler.reconcile(get(HR), context());

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
  void givenItsLlmNamesAnUnknownProvider_whenReconciled_thenInvalidNamingItAndNothingOfItRuns() {
    // Arrange: a first pass, then the loader's problem
    TestResources.applyFixtures(client);
    reconciler.reconcile(get(FF), context());
    TestResources.updateAirlock(
        client,
        FF,
        s ->
            TestResources.withLlms(
                s,
                List.of(new Llm("gemma", "gpt", "http://host.minikube.internal:11434", "gemma3"))));

    // Act
    UpdateControl<Airlock> control = reconciler.reconcile(get(FF), context());

    // Assert
    ResourceStatus status = control.getResource().orElseThrow().getStatus();
    assertThat(status.phase()).isEqualTo(ResourceStatus.INVALID);
    assertThat(status.problems()).hasSize(2).last().isEqualTo(Releases.HELD_BACK);
    assertThat(status.problems().getFirst())
        .contains("spec.llms[0].provider gpt is not one of [ollama, openai]");
    assertThat(deployment(FF_POD)).isNull();
  }

  @Test
  void givenAirlockBreaksAndAUserIsRemovedFromTheSpace_whenBothReconciled_thenNeitherAdmitsThem() {
    // Arrange: both airlocks run; then founders-finance breaks and eric leaves the space, the
    // only list that let him ask founders-finance
    TestResources.applyFixtures(client);
    reconciler.reconcile(get(HR), context());
    reconciler.reconcile(get(FF), context());
    assertThat(deployment(FF_POD)).isNotNull();
    TestResources.breakJarDigest(client, FF);
    SpaceDocument.Spec s = FIXTURES.spaces().get("hr-acme").spec();
    Space space = client.resources(Space.class).inNamespace(NS).withName("hr-acme").get();
    space.setSpec(
        new SpaceDocument.Spec(
            s.displayName(),
            s.description(),
            new SpaceDocument.Access(List.of("nora@example.com")),
            s.llms(),
            s.dataSources()));
    client.resource(space).update();

    // Act
    UpdateControl<Airlock> sibling = reconciler.reconcile(get(HR), context());
    UpdateControl<Airlock> held = reconciler.reconcile(get(FF), context());

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
    assertThat(served).contains("nora@example.com").doesNotContain("eric@");
    ResourceStatus status = held.getResource().orElseThrow().getStatus();
    assertThat(status.phase()).isEqualTo(ResourceStatus.INVALID);
    assertThat(status.problems()).last().isEqualTo(Releases.HELD_BACK);
    assertNothingRuns(
        FF_POD, "hr-acme-founders-finance-mcp-server", "hr-acme-founders-finance-config");
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
    ErrorStatusUpdateControl<Airlock> control =
        reconciler.updateErrorStatus(get(HR), context(), rejected);

    // Assert: the status says the apply failed and why, instead of keeping the last good one
    ResourceStatus status = control.getResource().orElseThrow().getStatus();
    assertThat(status.phase()).isEqualTo(ResourceStatus.INVALID);
    assertThat(status.problems())
        .singleElement()
        .satisfies(p -> assertThat(p).contains("apply failed").contains("field is immutable"));
  }

  @Test
  void givenGlobalLlmChanged_whenReconciled_thenTheAirlocksRevisionAndCatalogFollow() {
    // Arrange: hr-assistant runs the global granite; the admin moves it to another model
    TestResources.applyFixtures(client);
    String before =
        reconciler.reconcile(get(HR), context()).getResource().orElseThrow().getStatus().revision();
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
        reconciler.reconcile(get(HR), context()).getResource().orElseThrow().getStatus();

    // Assert: a global change rolls the airlock's pods
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

  @SuppressWarnings("unchecked")
  private static Context<Airlock> context() {
    return mock(Context.class);
  }

  private Airlock get(String name) {
    return client.resources(Airlock.class).inNamespace(NS).withName(name).get();
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
