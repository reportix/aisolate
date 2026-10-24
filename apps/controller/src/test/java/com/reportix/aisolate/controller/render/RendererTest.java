package com.reportix.aisolate.controller.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.reportix.aisolate.config.AirlockDocument;
import com.reportix.aisolate.config.AuditIngestToken;
import com.reportix.aisolate.config.Configuration;
import com.reportix.aisolate.config.ConfigurationLoader;
import com.reportix.aisolate.config.DiscoveredAirlock;
import com.reportix.aisolate.config.Llm;
import com.reportix.aisolate.config.Names;
import com.reportix.aisolate.config.SpaceDocument;
import com.reportix.aisolate.controller.support.TestResources;
import com.reportix.aisolate.controller.support.TestSettings;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.TolerationBuilder;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyEgressRule;
import io.fabric8.kubernetes.client.utils.Serialization;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The objects of each fixture airlock, pinned as golden YAML. */
class RendererTest {

  private static final Path DOCUMENTS = Path.of("src/test/resources/documents");
  private static final Configuration RELEASE = TestResources.FIXTURES;
  private static final Path EXPECTED = Path.of("src/test/resources/expected/release.yaml");
  private static final String HR = "hr-assistant";
  private static final String HR_POD = "hr-acme-hr-assistant-airlock";
  private static final String HR_MCP = "hr-acme-hr-assistant-mcp-server";
  private static final String FF = "founders-finance";
  private static final String FF_POD = "hr-acme-founders-finance-airlock";

  static final Owners OWNERS =
      new Owners(
          Map.of("Airlock/" + HR, owner(HR, "uid-hr"), "Airlock/" + FF, owner(FF, "uid-ff")));

  private final Renderer renderer = new Renderer(TestSettings.SETTINGS);

  @Test
  void givenFixtureRelease_whenEveryAirlockRendered_thenMatchesExpectedYaml() throws IOException {
    // Arrange / Act
    String actual =
        RELEASE.airlocks().keySet().stream()
            .flatMap(airlock -> renderer.render(RELEASE, airlock, OWNERS).stream())
            .map(Serialization::asYaml)
            .collect(Collectors.joining());

    // Assert
    assertGolden(EXPECTED, actual);
  }

  @Test
  void givenFixtureRelease_whenDiscoveryRendered_thenMatchesExpectedYaml() throws IOException {
    // Arrange / Act
    String actual =
        renderer.renderDiscovery(List.of(RELEASE), List.of(spaceOwner("hr-acme"))).stream()
            .map(Serialization::asYaml)
            .collect(Collectors.joining());

    // Assert
    assertGolden(Path.of("src/test/resources/expected/discovery.yaml"), actual);
  }

  /** The golden file is reviewed by a person; a diff here is a deliberate change or a bug. */
  private static void assertGolden(Path expected, String actual) throws IOException {
    if (!Files.exists(expected)) {
      Files.writeString(expected, actual);
      fail("golden file written to " + expected + ": review it, then run again");
    }
    if (!actual.equals(Files.readString(expected))) {
      Files.writeString(Path.of("build/actual-" + expected.getFileName()), actual);
    }
    assertThat(actual).isEqualTo(Files.readString(expected));
  }

  // Survives beside the golden as a regeneration guard: pods stay restricted and revisioned.
  @Test
  void givenAirlock_whenRendered_thenEveryPodIsRestrictedAndCarriesItsRevision() {
    // Arrange / Act
    List<HasMetadata> objects = renderer.render(RELEASE, HR, OWNERS);
    String revision = Renderer.selectServedDocuments(RELEASE, HR).revision();

    // Assert
    for (Deployment deployment : of(objects, Deployment.class)) {
      var pod = deployment.getSpec().getTemplate().getSpec();
      assertThat(pod.getAutomountServiceAccountToken()).isFalse();
      assertThat(pod.getSecurityContext().getRunAsNonRoot()).isTrue();
      assertThat(pod.getSecurityContext().getSeccompProfile().getType())
          .isEqualTo("RuntimeDefault");
      assertThat(pod.getInitContainers()).isEmpty();
      var container = pod.getContainers().getFirst();
      assertThat(container.getSecurityContext().getAllowPrivilegeEscalation()).isFalse();
      assertThat(container.getSecurityContext().getReadOnlyRootFilesystem()).isTrue();
      assertThat(container.getSecurityContext().getCapabilities().getDrop()).containsExactly("ALL");
      assertThat(findEnv(deployment, "AISOLATE_CONFIG_REVISION")).isEqualTo(revision);
      assertThat(deployment.getMetadata().getLabels())
          .containsEntry("aisolate.dev/revision", revision)
          .doesNotContainKey("aisolate.dev/version");
    }
  }

  @Test
  void givenAnLlmWithAnApiKeySecret_whenRendered_thenTheAirlockPodAloneReadsTheKeyFromIt() {
    // Arrange: the space's qwen, OpenAI-compatible with its key in a Secret
    SpaceDocument space = RELEASE.spaces().get("hr-acme");
    Llm qwen = space.spec().llms().getFirst();
    Llm compatible =
        new Llm(qwen.id(), "openai", qwen.url() + "/v1", qwen.model(), "hr-acme-llm-qwen-a1b2c3");
    Map<String, SpaceDocument> spaces = new HashMap<>(RELEASE.spaces());
    spaces.put(
        space.name(),
        new SpaceDocument(
            space.metadata(),
            new SpaceDocument.Spec(
                space.spec().displayName(),
                space.spec().description(),
                space.spec().access(),
                List.of(compatible),
                space.spec().dataSources())));
    Configuration release =
        new Configuration(RELEASE.revision(), RELEASE.global(), spaces, RELEASE.airlocks());

    // Act
    List<HasMetadata> objects = renderer.render(release, HR, OWNERS);

    // Assert: qwen is entry 2 after granite and nomic, which carry no key
    Deployment airlock = findDeployment(objects, HR_POD);
    assertThat(findEnv(airlock, "AISOLATE_LLM_2_ID")).isEqualTo("qwen");
    assertThat(findEnv(airlock, "AISOLATE_LLM_2_PROVIDER")).isEqualTo("openai");
    assertThat(findSecretKey(airlock, "AISOLATE_LLM_2_API_KEY"))
        .isEqualTo("hr-acme-llm-qwen-a1b2c3/apiKey");
    assertThat(listEnvNames(airlock))
        .doesNotContain("AISOLATE_LLM_0_API_KEY", "AISOLATE_LLM_1_API_KEY");
    assertThat(listEnvNames(findDeployment(objects, HR_MCP)))
        .noneMatch(name -> name.startsWith("AISOLATE_LLM"));
  }

  @ParameterizedTest
  @CsvSource({"airlock", "mcp-server"})
  void givenAirlockWithCode_whenRendered_thenEachOfItsPodsFetchesItWithItsCodeToken(String pod) {
    // Arrange
    AirlockDocument.Jar jar = RELEASE.airlocks().get(HR).spec().jar();

    // Act
    List<HasMetadata> objects = renderer.render(RELEASE, HR, OWNERS);

    // Assert: the backend's URL for this airlock and digest, the token read-only, no code mounted
    String name = "hr-acme-" + HR + "-" + pod;
    Deployment deployment = findDeployment(objects, name);
    var spec = deployment.getSpec().getTemplate().getSpec();
    assertThat(findEnv(deployment, "AISOLATE_CODE_URL"))
        .isEqualTo("http://backend.aisolate.svc:8085/v1/code/hr-acme/hr-assistant/" + jar.digest());
    assertThat(findEnv(deployment, "AISOLATE_CODE_DIGEST")).isEqualTo(jar.digest());
    assertThat(findEnv(deployment, "AISOLATE_CODE_TOKEN_FILE"))
        .isEqualTo("/var/run/aisolate/code/token");
    assertThat(spec.getContainers().getFirst().getVolumeMounts())
        .anySatisfy(
            m -> {
              assertThat(m.getName()).isEqualTo("code-token");
              assertThat(m.getMountPath()).isEqualTo("/var/run/aisolate/code");
              assertThat(m.getReadOnly()).isTrue();
            });
    assertThat(secretVolumes(deployment)).contains("hr-acme-hr-assistant-code");
    assertThat(spec.getVolumes())
        .noneMatch(v -> v.getConfigMap() != null && v.getName().equals("plugins"));
    assertThat(listEnvNames(deployment)).noneMatch(n -> n.startsWith("AISOLATE_PLUGIN"));
    assertThat(egressPeers(findPolicy(objects, name)))
        .contains("app=backend:" + Renderer.AUDIT_INGEST_PORT);
    Secret token =
        of(objects, Secret.class).stream()
            .filter(x -> x.getMetadata().getName().equals("hr-acme-hr-assistant-code"))
            .findFirst()
            .orElseThrow();
    assertThat(new String(Base64.getDecoder().decode(token.getData().get("token"))))
        .isEqualTo(AuditIngestToken.ofCode("test-ingest-key", "hr-acme", "hr-assistant"));
  }

  @Test
  void givenAirlockWithCodeAndNoBackend_whenRendered_thenItsPodsCarryTheDigestAlone() {
    // Arrange
    Renderer headless = new Renderer(TestSettings.withoutAuditIngest(TestSettings.SETTINGS));

    // Act
    List<HasMetadata> objects = headless.render(RELEASE, HR, OWNERS);

    // Assert: the pod refuses to start, naming the missing backend
    assertThat(of(objects, Secret.class)).isEmpty();
    assertThat(of(objects, Deployment.class))
        .allSatisfy(
            d -> {
              assertThat(findEnv(d, "AISOLATE_CODE_DIGEST")).startsWith("sha256:");
              assertThat(listEnvNames(d))
                  .doesNotContain("AISOLATE_CODE_URL", "AISOLATE_CODE_TOKEN_FILE");
              assertThat(secretVolumes(d)).isEmpty();
            });
    assertThat(
            egressPeers(findPolicy(objects, HR_MCP))
                .contains("app=backend:" + Renderer.AUDIT_INGEST_PORT))
        .isFalse();
  }

  @ParameterizedTest
  @CsvSource({
    "'', aisolate/airlock:dev, aisolate/tool-server:dev, false",
    "', language: java', aisolate/airlock:dev, aisolate/tool-server:dev, false",
    "', language: python', aisolate/airlock-python:dev, aisolate/tool-server-python:dev, true"
  })
  void givenTheCodesLanguage_whenRendered_thenItsImagePairItsDigestAndItsProxyEnv(
      String language, String airlockImage, String toolServerImage, boolean python)
      throws IOException {
    // Arrange
    Configuration release =
        parse(
            "airlocks.yaml",
            y -> y.replaceFirst("(jar: \\{digest: \"sha256:784c[^}]*)\\}", "$1" + language + "}"));
    Renderer withProxy = new Renderer(TestSettings.withProxy(TestSettings.SETTINGS, true));

    // Act
    List<HasMetadata> objects = withProxy.render(release, HR, OWNERS);

    // Assert: the one pod shape on the language's images; Python reads the proxy from its own env
    Deployment airlock = findDeployment(objects, HR_POD);
    Deployment tools = findDeployment(objects, HR_MCP);
    assertThat(airlock.getSpec().getTemplate().getSpec().getContainers().getFirst().getImage())
        .isEqualTo(airlockImage);
    assertThat(tools.getSpec().getTemplate().getSpec().getContainers().getFirst().getImage())
        .isEqualTo(toolServerImage);
    assertThat(List.of(airlock, tools))
        .allSatisfy(
            d -> {
              assertThat(findEnv(d, "AISOLATE_CODE_DIGEST")).startsWith("sha256:784c");
              assertThat(findEnv(d, "JDK_JAVA_OPTIONS")).isNotNull();
              assertThat(findEnv(d, "HTTPS_PROXY")).isEqualTo(python ? "http://egress:3128" : null);
              if (python) {
                assertThat(List.of(findEnv(d, "NO_PROXY").split(",")))
                    .contains("keycloak.localtest.me", "localhost", ".svc", ".cluster.local")
                    .doesNotContain(TestSettings.PROXIED_HOST, "*.svc");
              } else {
                assertThat(findEnv(d, "NO_PROXY")).isNull();
              }
            });
  }

  @Test
  void givenAirlockWithoutAJar_whenRendered_thenNoPluginVolumeNoPluginEnvNoMcpServer()
      throws IOException {
    // Arrange: founders-finance as the AIsolate UI saves it, before its first deploy
    Configuration release =
        parse("airlocks.yaml", y -> y.replaceAll("\\n  jar: \\{digest: \"sha256:72b8.*", ""));

    // Act
    List<HasMetadata> objects = renderer.render(release, FF, OWNERS);
    Deployment airlock = findDeployment(objects, FF_POD);

    // Assert
    assertThat(airlock.getSpec().getTemplate().getSpec().getVolumes())
        .extracting(Volume::getName)
        .containsExactly("config", "tmp", "audit-token", "audit-buffer");
    assertThat(listEnvNames(airlock))
        .doesNotContain("AISOLATE_CODE_URL", "AISOLATE_CODE_DIGEST", "AISOLATE_TOOL_SERVER_URL");
    assertThat(names(objects, "Deployment")).containsExactly(FF_POD);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void givenProxySetting_whenRendered_thenBothPodKindsGetTheJvmProxyOptionsOnlyWhenEnabled(
      boolean enabled) {
    // Arrange
    Renderer withProxy = new Renderer(TestSettings.withProxy(TestSettings.SETTINGS, enabled));

    // Act
    List<Deployment> deployments = of(withProxy.render(RELEASE, HR, OWNERS), Deployment.class);

    // Assert: a policy route to the proxy is useless unless the JVM is told to use it
    assertThat(deployments)
        .hasSize(2)
        .allSatisfy(
            d ->
                assertThat(
                        String.valueOf(findEnv(d, "JDK_JAVA_OPTIONS"))
                            .startsWith("-Dhttps.proxyHost=egress -Dhttps.proxyPort=3128 "))
                    .isEqualTo(enabled));
  }

  // Survives beside the golden as a regeneration guard: limits and the tmp cap stay in force.
  @Test
  void givenSettings_whenRendered_thenEveryContainerHasLimitsAndTmpIsCapped() {
    // Arrange / Act
    List<Deployment> deployments = of(renderer.render(RELEASE, HR, OWNERS), Deployment.class);

    // Assert: one airlock's code or load must not starve the node or fill its disk
    assertThat(deployments)
        .hasSize(2)
        .allSatisfy(
            d -> {
              var pod = d.getSpec().getTemplate().getSpec();
              assertThat(pod.getContainers())
                  .allSatisfy(
                      c ->
                          assertThat(c.getResources().getLimits())
                              .containsKeys("cpu", "memory", "ephemeral-storage"));
              assertThat(pod.getVolumes())
                  .filteredOn(v -> v.getName().equals("tmp"))
                  .singleElement()
                  .satisfies(
                      v ->
                          assertThat(v.getEmptyDir().getSizeLimit())
                              .isEqualTo(new Quantity(Renderer.CODE_TMP_SIZE_LIMIT)));
            });
  }

  @ParameterizedTest
  @CsvSource({"http://tempo.aisolate-monitoring.svc:4318, true", "'', false"})
  void givenOtlpEndpoint_whenRendered_thenBothPodsSendTracesThereOnlyWhenSet(
      String endpoint, boolean set) {
    // Arrange
    Renderer tracing = new Renderer(TestSettings.withOtlpEndpoint(TestSettings.SETTINGS, endpoint));
    String collector = "app=tempo:4318";

    // Act
    List<HasMetadata> objects = tracing.render(RELEASE, HR, OWNERS);

    // Assert: the endpoint as it is (the pod adds /v1/traces), and a route to its host
    assertThat(of(objects, Deployment.class))
        .hasSize(2)
        .allSatisfy(
            d ->
                assertThat(findEnv(d, "MANAGEMENT_OTLP_TRACING_ENDPOINT"))
                    .isEqualTo(set ? endpoint : null));
    assertThat(of(objects, NetworkPolicy.class))
        .hasSize(2)
        .allSatisfy(p -> assertThat(egressPeers(p).contains(collector)).isEqualTo(set));
  }

  @ParameterizedTest
  @CsvSource({"gvisor, gvisor", "'',"})
  void givenRuntimeClass_whenRendered_thenEveryPodKindCarriesIt(String setting, String expected) {
    // Arrange
    Renderer sandboxed =
        new Renderer(TestSettings.withRuntimeClass(TestSettings.SETTINGS, setting));

    // Act
    List<HasMetadata> airlock = sandboxed.render(RELEASE, HR, OWNERS);
    List<HasMetadata> discovery =
        sandboxed.renderDiscovery(List.of(RELEASE), List.of(spaceOwner("hr-acme")));

    // Assert: the airlock pod, its MCP server and the discovery pods alike
    assertThat(
            Stream.concat(
                of(airlock, Deployment.class).stream(), of(discovery, Deployment.class).stream()))
        .hasSize(3)
        .allSatisfy(
            d ->
                assertThat(d.getSpec().getTemplate().getSpec().getRuntimeClassName())
                    .isEqualTo(expected));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void givenScheduling_whenRendered_thenEveryPodKindCarriesItsNodeSelectorAndTolerations(
      boolean set) {
    // Arrange: a tainted node pool, as the chart passes platform.scheduling on; unset: nothing
    Map<String, String> nodeSelector = Map.of("pool", "robot");
    Renderer scheduled =
        new Renderer(
            TestSettings.withScheduling(
                TestSettings.SETTINGS,
                set
                    ? new ControllerProperties.Scheduling(
                        nodeSelector,
                        List.of(
                            new ControllerProperties.Toleration(
                                "pool", "Equal", "robot", "NoSchedule", null)))
                    : null));

    // Act
    List<HasMetadata> airlock = scheduled.render(RELEASE, HR, OWNERS);
    List<HasMetadata> discovery =
        scheduled.renderDiscovery(List.of(RELEASE), List.of(spaceOwner("hr-acme")));

    // Assert: the airlock pod, its MCP server and the discovery pods alike
    assertThat(
            Stream.concat(
                of(airlock, Deployment.class).stream(), of(discovery, Deployment.class).stream()))
        .hasSize(3)
        .allSatisfy(
            d -> {
              var pod = d.getSpec().getTemplate().getSpec();
              if (set) {
                assertThat(pod.getNodeSelector()).isEqualTo(nodeSelector);
                assertThat(pod.getTolerations())
                    .containsExactly(
                        new TolerationBuilder()
                            .withKey("pool")
                            .withOperator("Equal")
                            .withValue("robot")
                            .withEffect("NoSchedule")
                            .build());
              } else {
                assertThat(pod.getNodeSelector()).isNullOrEmpty();
                assertThat(pod.getTolerations()).isNullOrEmpty();
              }
            });
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void givenAuditIngest_whenRendered_thenOnlyTheAirlockPodHoldsItsTokenAndReachesIt(
      boolean ingest) {
    // Arrange
    Renderer audit =
        new Renderer(
            ingest
                ? TestSettings.SETTINGS
                : TestSettings.withoutAuditIngest(TestSettings.SETTINGS));

    // Act
    List<HasMetadata> objects = audit.render(RELEASE, HR, OWNERS);

    // Assert: the token is this airlock's alone, and only its airlock pod mounts it
    List<Secret> secrets = of(objects, Secret.class);
    Deployment airlockPod =
        of(objects, Deployment.class).stream()
            .filter(d -> Names.AIRLOCK_ROLE.equals(d.getMetadata().getLabels().get(Renderer.ROLE)))
            .findFirst()
            .orElseThrow();
    Deployment mcpServer =
        of(objects, Deployment.class).stream()
            .filter(d -> d != airlockPod)
            .findFirst()
            .orElseThrow();
    NetworkPolicy airlockPolicy = findPolicy(objects, airlockPod.getMetadata().getName());
    assertThat(egressPeers(airlockPolicy).contains("app=backend:" + Renderer.AUDIT_INGEST_PORT))
        .isEqualTo(ingest);
    assertThat(secretVolumes(mcpServer)).doesNotContain("hr-acme-hr-assistant-audit");
    if (!ingest) {
      assertThat(secrets).isEmpty();
      assertThat(secretVolumes(airlockPod)).isEmpty();
      assertThat(findEnv(airlockPod, "AISOLATE_AUDIT_URL")).isNull();
      return;
    }
    Secret token =
        secrets.stream()
            .filter(x -> x.getMetadata().getName().equals("hr-acme-hr-assistant-audit"))
            .findFirst()
            .orElseThrow();
    String expected = AuditIngestToken.of("test-ingest-key", "hr-acme", "hr-assistant");
    assertThat(new String(Base64.getDecoder().decode(token.getData().get("token"))))
        .isEqualTo(expected);
    assertThat(secretVolumes(airlockPod)).contains(token.getMetadata().getName());
    assertThat(findEnv(airlockPod, "AISOLATE_AUDIT_URL"))
        .isEqualTo("http://backend.aisolate.svc:8085/v1/audit/events");
    assertThat(findEnv(airlockPod, "AISOLATE_AUDIT_FAIL_CLOSED")).isEqualTo("true");
  }

  private static List<String> secretVolumes(Deployment deployment) {
    return deployment.getSpec().getTemplate().getSpec().getVolumes().stream()
        .filter(v -> v.getSecret() != null)
        .map(v -> v.getSecret().getSecretName())
        .toList();
  }

  static Stream<Arguments> changes() {
    return Stream.of(
        Arguments.of(
            "another airlock's description",
            (UnaryOperator<String>) y -> y.replace("Founders finance", "Founders"),
            false),
        Arguments.of(
            "another airlock's prompt",
            (UnaryOperator<String>) y -> y.replace("founders only", "founders, only"),
            false),
        Arguments.of(
            "the airlock's roles", (UnaryOperator<String>) y -> y.replace(", hr]", "]"), true),
        Arguments.of(
            "the airlock's jar digest",
            (UnaryOperator<String>) y -> y.replace("sha256:784c", "sha256:884c"),
            true));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("changes")
  void givenOneAirlockChanged_whenRendered_thenOnlyTheAirlockServingItGetsANewRevision(
      String change, UnaryOperator<String> edit, boolean restarts) throws IOException {
    // Arrange: a fixture string that drifted would leave the release as it is
    Configuration changed = parse("airlocks.yaml", edit);
    assertThat(changed.revision()).isNotEqualTo(RELEASE.revision());

    // Act
    String before = Renderer.selectServedDocuments(RELEASE, HR).revision();
    String after = Renderer.selectServedDocuments(changed, HR).revision();

    // Assert: a pod restarts when what it serves changes, never for a sibling's change
    assertThat(after.equals(before)).isEqualTo(!restarts);
  }

  // Survives beside the golden as a regeneration guard: discovery leaks no e-mail or secret.
  @Test
  void givenRelease_whenDiscoveryRendered_thenOnlyWhatDiscoveryListsAndNoEmailOrDocument() {
    // Arrange: owned as Releases owns it, by every Space and controlled by none
    OwnerReference space = spaceOwner("hr-acme");

    // Act
    ConfigMap discovery =
        of(renderer.renderDiscovery(List.of(RELEASE), List.of(space)), ConfigMap.class).getFirst();

    // Assert: every user's token reaches the pods that read it
    assertThat(discovery.getData()).containsOnlyKeys("hr-acme.json");
    String listed = discovery.getData().get("hr-acme.json");
    assertThat(DiscoveredAirlock.fromJson(listed))
        .extracting(DiscoveredAirlock::id)
        .containsExactly(HR, FF);
    assertThat(listed)
        .contains("\"roles\":[\"ceo\",\"hr\",\"employee\"")
        .doesNotContain("@example.com")
        .doesNotContain("You are the Acme HR assistant")
        .doesNotContain("jdbc:")
        .doesNotContain("secretRef")
        .doesNotContain("models.example.com");
    assertThat(discovery.getMetadata().getOwnerReferences()).containsExactly(space);
  }

  // Survives beside the golden as a regeneration guard: discovery pods hold no airlock's code.
  @Test
  void givenRelease_whenDiscoveryRendered_thenItsPodsRunNoAirlocksCodeDataOrModel() {
    // Arrange
    OwnerReference space = spaceOwner("hr-acme");

    // Act
    List<HasMetadata> objects = renderer.renderDiscovery(List.of(RELEASE), List.of(space));

    // Assert: the shared Service's pods: the mount alone, so no airlock's code holds a token
    Deployment pods = findDeployment(objects, Renderer.DISCOVERY);
    assertThat(pods.getSpec().getSelector().getMatchLabels())
        .containsExactly(Map.entry(Renderer.ROLE, Renderer.DISCOVERY_ROLE));
    var template = pods.getSpec().getTemplate();
    assertThat(template.getMetadata().getLabels())
        .containsEntry(Renderer.ROLE, Renderer.DISCOVERY_ROLE);
    assertThat(template.getMetadata().getAnnotations()).isEmpty();
    assertThat(template.getSpec().getVolumes())
        .extracting(Volume::getName)
        .containsExactly("tmp", "discovery");
    assertThat(template.getSpec().getContainers().getFirst().getImage())
        .isEqualTo(TestSettings.SETTINGS.images().airlock());
    assertThat(findEnv(pods, "AISOLATE_CONFIG_DISCOVERY_DIR")).isEqualTo("/etc/aisolate-discovery");
    assertThat(listEnvNames(pods))
        .noneMatch(
            name ->
                name.equals("AISOLATE_CONFIG_DIR")
                    || name.equals("AISOLATE_AIRLOCK")
                    || name.startsWith("AISOLATE_PLUGIN")
                    || name.startsWith("AISOLATE_DATASOURCE_")
                    || name.startsWith("AISOLATE_LLM")
                    || name.equals("AISOLATE_TOOL_SERVER_URL"));
    assertThat(pods.getMetadata().getOwnerReferences()).containsExactly(space);
    NetworkPolicy policy = findPolicy(objects, Renderer.DISCOVERY);
    assertThat(policy.getSpec().getPodSelector().getMatchLabels())
        .containsExactly(Map.entry(Renderer.ROLE, Renderer.DISCOVERY_ROLE));
    assertThat(egressPeers(policy))
        .containsExactlyInAnyOrder(
            "k8s-app=kube-dns:53",
            "app=tempo:4318",
            "app=keycloak:8080",
            "aisolate.dev/role=egress-proxy:3128");
  }

  @Test
  void givenDiscoveryChanged_whenRendered_thenItsPodTemplateStaysTheSame() {
    // Arrange: what it lists changes; the pods re-read the mount
    OwnerReference space = spaceOwner("hr-acme");

    // Act
    Deployment before =
        findDeployment(
            renderer.renderDiscovery(List.of(RELEASE), List.of(space)), Renderer.DISCOVERY);
    Deployment after =
        findDeployment(renderer.renderDiscovery(List.of(), List.of(space)), Renderer.DISCOVERY);

    // Assert
    assertThat(after.getSpec().getTemplate()).isEqualTo(before.getSpec().getTemplate());
  }

  private static OwnerReference spaceOwner(String name) {
    return new OwnerReferenceBuilder()
        .withApiVersion("aisolate.dev/v1")
        .withKind("Space")
        .withName(name)
        .withUid("uid-space")
        .build();
  }

  private static OwnerReference owner(String name, String uid) {
    return new OwnerReferenceBuilder()
        .withApiVersion("aisolate.dev/v1")
        .withKind("Airlock")
        .withName(name)
        .withUid(uid)
        .withController(true)
        .build();
  }

  /** The fixture documents with one file edited. */
  private static Configuration parse(String file, UnaryOperator<String> edit) throws IOException {
    Map<String, String> files = new LinkedHashMap<>();
    for (Path path : Files.list(DOCUMENTS).sorted().toList()) {
      files.put(path.getFileName().toString(), Files.readString(path));
    }
    files.computeIfPresent(file, (k, v) -> edit.apply(v));
    return ConfigurationLoader.parse(files);
  }

  /** Each egress peer as {@code cidr:port} or {@code label=value,...:port}. */
  private static List<String> egressPeers(NetworkPolicy policy) {
    return policy.getSpec().getEgress().stream()
        .flatMap(
            (NetworkPolicyEgressRule rule) ->
                rule.getTo().stream()
                    .map(
                        peer ->
                            (peer.getIpBlock() != null
                                    ? peer.getIpBlock().getCidr()
                                    : peer.getPodSelector() == null
                                        ? "*"
                                        : peer.getPodSelector().getMatchLabels().entrySet().stream()
                                            .map(e -> e.getKey() + "=" + e.getValue())
                                            .collect(Collectors.joining(",")))
                                + ":"
                                + (rule.getPorts().isEmpty()
                                    ? "*"
                                    : rule.getPorts().getFirst().getPort().getIntVal())))
        .toList();
  }

  private static List<String> names(List<HasMetadata> objects, String kind) {
    return objects.stream()
        .filter(o -> o.getKind().equals(kind))
        .map(o -> o.getMetadata().getName())
        .toList();
  }

  private static <T extends HasMetadata> List<T> of(List<HasMetadata> objects, Class<T> type) {
    return objects.stream().filter(type::isInstance).map(type::cast).toList();
  }

  private static Deployment findDeployment(List<HasMetadata> objects, String name) {
    return of(objects, Deployment.class).stream()
        .filter(d -> d.getMetadata().getName().equals(name))
        .findFirst()
        .orElseThrow();
  }

  private static NetworkPolicy findPolicy(List<HasMetadata> objects, String name) {
    return of(objects, NetworkPolicy.class).stream()
        .filter(p -> p.getMetadata().getName().equals(name))
        .findFirst()
        .orElseThrow();
  }

  private static List<EnvVar> listEnv(Deployment deployment) {
    return deployment.getSpec().getTemplate().getSpec().getContainers().getFirst().getEnv();
  }

  private static String findEnv(Deployment deployment, String name) {
    return listEnv(deployment).stream()
        .filter(e -> e.getName().equals(name))
        .map(EnvVar::getValue)
        .findFirst()
        .orElse(null);
  }

  /** {@code <secret>/<key>} of a variable read from a Secret. */
  private static String findSecretKey(Deployment deployment, String name) {
    return listEnv(deployment).stream()
        .filter(e -> e.getName().equals(name))
        .map(e -> e.getValueFrom().getSecretKeyRef())
        .map(ref -> ref.getName() + "/" + ref.getKey())
        .findFirst()
        .orElse(null);
  }

  private static List<String> listEnvNames(Deployment deployment) {
    return listEnv(deployment).stream().map(EnvVar::getName).toList();
  }
}
