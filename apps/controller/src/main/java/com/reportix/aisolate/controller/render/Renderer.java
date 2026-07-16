package com.reportix.aisolate.controller.render;

import com.reportix.aisolate.config.AirlockDocument;
import com.reportix.aisolate.config.AuditIngestToken;
import com.reportix.aisolate.config.Configuration;
import com.reportix.aisolate.config.ConfigurationLoader;
import com.reportix.aisolate.config.DataSource;
import com.reportix.aisolate.config.DiscoveredAirlock;
import com.reportix.aisolate.config.Llm;
import com.reportix.aisolate.config.Names;
import com.reportix.aisolate.config.SpaceDocument;
import com.reportix.aisolate.config.Urls;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.LabelSelectorBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyEgressRule;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyEgressRuleBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyIngressRule;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyIngressRuleBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPeerBuilder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;

/**
 * The objects of one airlock, every one owned by its Airlock: a ConfigMap with only the documents
 * that airlock serves, and a pod pair, each with its Deployment, Service and NetworkPolicy: the
 * airlock pod (and its Ingress rule) and the MCP server only that pod reaches, all named {@code
 * <space>-<airlock>-...}. An airlock's revision covers the Global, its Space and itself, so a
 * sibling's change restarts none of its pods, a change to its space restarts every airlock of the
 * space and a change to the Global every airlock. Pure: documents and settings in, objects out; the
 * golden test pins the output.
 */
@RequiredArgsConstructor
public final class Renderer {

  public static final String MANAGED_BY = "aisolate.dev/managed-by";
  public static final String REVISION = "aisolate.dev/revision";
  public static final String ROLE = Names.ROLE_LABEL;
  public static final String SPACE = Names.SPACE_LABEL;
  public static final String AIRLOCK = "aisolate.dev/airlock";

  /** The fixed label of the pods that may read {@code /actuator/prometheus}, in any namespace. */
  public static final String SCRAPER = "aisolate.dev/scraper";

  /** Actuator's port in every AIsolate pod: health and metrics, on no Service. */
  public static final int MANAGEMENT_PORT = 9464;

  static final String CONFIG_DIR = "/etc/aisolate";
  static final String AUDIT_TOKEN_DIR = "/var/run/aisolate/audit";
  static final String CODE_TOKEN_DIR = "/var/run/aisolate/code";
  static final String AUDIT_BUFFER_DIR = "/var/lib/aisolate/audit";

  /**
   * The backend's ingest port: an airlock pod hands its audit lines over there, both pods of an
   * airlock with code fetch it there. No pod reaches the backend's API.
   */
  public static final int AUDIT_INGEST_PORT = 8085;

  /** Above the sink's 64 MB bound, so an admitted exchange's line still fits. */
  static final String AUDIT_BUFFER_SIZE_LIMIT = "96Mi";

  /**
   * Every valid space's airlocks as discovery lists them: the ConfigMap, and the Deployment and
   * NetworkPolicy of the pods that serve it.
   */
  public static final String DISCOVERY = "aisolate-discovery";

  /** The pods behind the release's {@code aisolate} Service: discovery only, no airlock's code. */
  public static final String DISCOVERY_ROLE = "discovery";

  static final String DISCOVERY_DIR = "/etc/aisolate-discovery";

  static final String TMP_SIZE_LIMIT = "256Mi";

  // An airlock's pods fetch their artifact into /tmp, and a Python pod extracts it there too.
  static final String CODE_TMP_SIZE_LIMIT = "2Gi";

  /** At most 63 characters: a Service name is one DNS label. */
  public static final int MAX_NAME = Names.MAX_NAME;

  private final ControllerProperties settings;

  /** The airlock pod of one airlock, named so {@code kubectl get pods} reads. */
  public static String airlockPodName(String space, String airlock) {
    return space + "-" + airlock + "-airlock";
  }

  /** The MCP server (tool server) of one airlock, reached by its airlock pod alone. */
  public static String mcpServerName(String space, String airlock) {
    return Names.mcpServerName(space, airlock);
  }

  /** The Secret of one airlock's audit ingest token, mounted by its airlock pod alone. */
  public static String auditSecretName(String space, String airlock) {
    return space + "-" + airlock + "-audit";
  }

  /** The Secret of one airlock's code token, mounted by both of its pods. */
  public static String codeSecretName(String space, String airlock) {
    return space + "-" + airlock + "-code";
  }

  /** The ConfigMap of one airlock's documents, mounted by its two pods. */
  public static String configMapName(String space, String airlock) {
    return space + "-" + airlock + "-config";
  }

  /** The objects of {@code airlock}, the name of one of the release's Airlocks. */
  public List<HasMetadata> render(Configuration release, String airlock, Owners owners) {
    Configuration served = selectServedDocuments(release, airlock);
    Target t =
        new Target(
            served.spaceOf(airlock),
            served.airlocks().get(airlock),
            served,
            served.llmsOf(airlock),
            served.dataSourcesOf(airlock),
            owners.of("Airlock", airlock));
    // Applied in this order: a narrower policy is in force before the pods it narrows start.
    List<HasMetadata> objects = new ArrayList<>();
    objects.add(buildConfigMap(t));
    if (settings.auditEnabled()) {
      objects.add(buildAuditSecret(t));
    }
    if (delivers(t)) {
      objects.add(buildCodeSecret(t));
    }
    objects.add(buildAirlockPolicy(t));
    if (t.runsMcpServer()) {
      objects.add(buildMcpServerPolicy(t));
    }
    objects.add(buildAirlockDeployment(t));
    objects.add(buildService(t.airlockPod(), 8080, buildAirlockSelector(t), t));
    objects.add(buildIngress(t));
    if (t.runsMcpServer()) {
      objects.add(buildMcpServerDeployment(t));
      objects.add(buildService(t.mcpServer(), 8081, buildMcpServerSelector(t), t));
    }
    return objects;
  }

  /**
   * The documents one airlock's pods serve, alone: the Global, its Space and itself, whole. The
   * pods check their files against this revision.
   */
  public static Configuration selectServedDocuments(Configuration release, String airlock) {
    return ConfigurationLoader.parse(
        buildFiles(
            Stream.of(
                release.global(), release.spaceOf(airlock), release.airlocks().get(airlock))));
  }

  /**
   * Discovery of every valid space: what it lists, {@code <space>.json} each, holding no document,
   * only {@link DiscoveredAirlock}; and the pods behind the shared {@code aisolate} Service that
   * serve it. Every user's token reaches those pods, so they run the airlock image with no
   * documents, no jar, no data source and no model, and a chat path that reaches them is 404. They
   * re-read the mount, so their template never changes with what it lists. Owned by every Space, so
   * the cluster deletes them with the last one.
   */
  public List<HasMetadata> renderDiscovery(
      List<Configuration> releases, List<OwnerReference> spaces) {
    Map<String, String> files = new TreeMap<>();
    releases.forEach(
        release ->
            files.put(
                release.spaces().keySet().iterator().next() + ".json",
                DiscoveredAirlock.toJson(DiscoveredAirlock.of(release))));
    HasMetadata list =
        new ConfigMapBuilder()
            .withMetadata(buildDiscoveryMeta(Map.of(), spaces))
            .withData(files)
            .build();
    return List.of(list, buildDiscoveryPolicy(spaces), buildDiscoveryDeployment(spaces));
  }

  private ObjectMeta buildDiscoveryMeta(Map<String, String> labels, List<OwnerReference> spaces) {
    ObjectMeta meta = buildMeta(DISCOVERY, labels, null, Optional.empty());
    meta.setOwnerReferences(spaces);
    return meta;
  }

  private static Map<String, String> buildDiscoverySelector() {
    return Map.of(ROLE, DISCOVERY_ROLE);
  }

  private HasMetadata buildDiscoveryDeployment(List<OwnerReference> spaces) {
    List<EnvVar> env = new ArrayList<>();
    env.add(buildEnv("AISOLATE_CONFIG_DISCOVERY_DIR", DISCOVERY_DIR));
    env.add(buildEnv("AISOLATE_PUBLIC_URL", settings.ingress().publicUrl()));
    env.add(buildEnv("AISOLATE_CORS_ALLOWED_ORIGINS", settings.corsAllowedOrigins()));
    env.add(buildEnv("AISOLATE_API_DOCS", "false"));
    env.addAll(buildIdentityEnv());
    env.addAll(buildProxyEnv(false));
    env.addAll(buildTracingEnv());
    Container container =
        buildContainer(
            "discovery",
            settings.images().airlock(),
            8080,
            settings.resources() == null ? null : settings.resources().airlock(),
            env,
            List.of(
                buildMount("tmp", "/tmp", false), buildMount("discovery", DISCOVERY_DIR, true)));
    PodSpec pod =
        buildPod(
            List.of(container),
            List.of(buildEmptyDir("tmp"), buildDiscoveryVolume()),
            settings.airlockPods().runtimeClassName());
    HasMetadata deployment =
        buildDeployment(
            DISCOVERY,
            buildDiscoverySelector(),
            buildDiscoverySelector(),
            settings.airlockPods().replicas(),
            pod,
            null,
            Optional.empty());
    deployment.getMetadata().setOwnerReferences(spaces);
    return deployment;
  }

  /** As an airlock pod's, without the model, the data sources or an MCP server to reach. */
  private HasMetadata buildDiscoveryPolicy(List<OwnerReference> spaces) {
    List<NetworkPolicyEgressRule> egress = new ArrayList<>();
    egress.add(Peers.egress(settings.egress().dns(), "UDP", "TCP"));
    egress.addAll(buildHostEgress(Stream.of(settings.otlpEndpoint())));
    egress.addAll(buildCommonEgress());
    return new NetworkPolicyBuilder()
        .withMetadata(buildDiscoveryMeta(buildDiscoverySelector(), spaces))
        .withNewSpec()
        .withPodSelector(
            new LabelSelectorBuilder().withMatchLabels(buildDiscoverySelector()).build())
        .withPolicyTypes("Ingress", "Egress")
        .withIngress(buildPublicIngress())
        .withEgress(egress)
        .endSpec()
        .build();
  }

  /** What the ingress controller reaches on 8080, and a scraper on the management port. */
  private List<NetworkPolicyIngressRule> buildPublicIngress() {
    return List.of(
        new NetworkPolicyIngressRuleBuilder()
            .withFrom(Peers.peer(settings.egress().ingressFrom()))
            .withPorts(Peers.port(8080, "TCP"))
            .build(),
        buildScraperIngress());
  }

  /**
   * One airlock as its pods see it: its space, its documents, its LLMs and data sources as resolved
   * (in id order: their env index), its owner.
   */
  private record Target(
      SpaceDocument space,
      AirlockDocument airlock,
      Configuration served,
      List<Llm> llms,
      List<DataSource> dataSources,
      Optional<OwnerReference> owner) {

    /** The code deployed to it; null before its first deploy. */
    AirlockDocument.Jar jar() {
      return airlock.spec().jar();
    }

    /** Python code runs on the Python images; an airlock without code on the Java ones. */
    boolean python() {
      return jar() != null && jar().python();
    }

    String revision() {
      return served.revision();
    }

    String airlockPod() {
      return airlockPodName(space.name(), airlock.name());
    }

    String mcpServer() {
      return mcpServerName(space.name(), airlock.name());
    }

    /**
     * An MCP server only for code with a tool the model may call; the airlock pod loads the jar
     * either way, for its handlers.
     */
    boolean runsMcpServer() {
      return airlock.hasCode() && !airlock.tools().isEmpty();
    }
  }

  private HasMetadata buildConfigMap(Target t) {
    return new ConfigMapBuilder()
        .withMetadata(
            buildMeta(
                configMapName(t.space().name(), t.airlock().name()),
                Map.of(AIRLOCK, t.airlock().name()),
                t.revision(),
                t.owner()))
        .withData(buildFiles(documentsOf(t.served())))
        .build();
  }

  /** One file per document, in their order. */
  public static Map<String, String> buildFiles(Stream<?> documents) {
    Map<String, String> files = new LinkedHashMap<>();
    documents.forEach(
        d -> files.put(ConfigurationLoader.fileName(d), ConfigurationLoader.canonical(d)));
    return files;
  }

  /** The Global, then the spaces, then the airlocks. */
  private static Stream<?> documentsOf(Configuration configuration) {
    return Stream.of(
            List.of(configuration.global()),
            configuration.spaces().values(),
            configuration.airlocks().values())
        .flatMap(Collection::stream);
  }

  /**
   * What selects one airlock's airlock pod: role and airlock, both immutable. The role keeps it
   * apart from the MCP server of the same airlock, which carries the airlock too. Every map the
   * objects carry is sorted, so the YAML is the same every run ({@code Map.of} iterates in an order
   * salted per JVM).
   */
  private static Map<String, String> buildAirlockSelector(Target t) {
    return new TreeMap<>(Map.of(ROLE, Names.AIRLOCK_ROLE, AIRLOCK, t.airlock().name()));
  }

  private static Map<String, String> buildMcpServerSelector(Target t) {
    return new TreeMap<>(Map.of(ROLE, Names.TOOL_SERVER_ROLE, AIRLOCK, t.airlock().name()));
  }

  private HasMetadata buildMcpServerDeployment(Target t) {
    Map<String, String> labels =
        Map.of(ROLE, Names.TOOL_SERVER_ROLE, SPACE, t.space().name(), AIRLOCK, t.airlock().name());
    CodeWiring code = buildCodeWiring(t);
    List<EnvVar> env = new ArrayList<>();
    env.add(buildEnv("AISOLATE_CONFIG_DIR", CONFIG_DIR));
    env.add(buildEnv("AISOLATE_CONFIG_REVISION", t.revision()));
    env.addAll(code.env());
    env.addAll(buildDataSourceEnv(t));
    env.addAll(buildIdentityEnv());
    env.addAll(buildProxyEnv(t.python()));
    env.addAll(buildTracingEnv());
    Container tools =
        buildContainer(
            "tools",
            settings.images().toolServer(t.python()),
            8081,
            settings.resources() == null ? null : settings.resources().toolServer(),
            env,
            surround(
                buildMount("config", CONFIG_DIR, true),
                code.mounts(),
                buildMount("tmp", "/tmp", false)));
    PodSpec pod =
        buildPod(
            List.of(tools),
            surround(
                buildConfigVolume(t), code.volumes(), buildEmptyDir("tmp", CODE_TMP_SIZE_LIMIT)),
            settings.airlockPods().runtimeClassName());
    return buildDeployment(
        t.mcpServer(), labels, buildMcpServerSelector(t), 1, pod, t.revision(), t.owner());
  }

  private HasMetadata buildAirlockDeployment(Target t) {
    Map<String, String> labels =
        Map.of(ROLE, Names.AIRLOCK_ROLE, AIRLOCK, t.airlock().name(), SPACE, t.space().name());
    CodeWiring code = buildCodeWiring(t);
    List<EnvVar> env = new ArrayList<>();
    env.add(buildEnv("AISOLATE_AIRLOCK", t.airlock().name()));
    env.add(buildEnv("AISOLATE_CONFIG_DIR", CONFIG_DIR));
    env.add(buildEnv("AISOLATE_CONFIG_REVISION", t.revision()));
    env.addAll(code.env());
    if (t.runsMcpServer()) {
      env.add(buildEnv("AISOLATE_TOOL_SERVER_URL", "http://" + t.mcpServer() + ":8081/mcp"));
    }
    env.add(buildEnv("AISOLATE_PUBLIC_URL", settings.ingress().publicUrl()));
    env.add(buildEnv("AISOLATE_CORS_ALLOWED_ORIGINS", settings.corsAllowedOrigins()));
    env.add(buildEnv("AISOLATE_API_DOCS", "false"));
    env.addAll(buildLlmEnv(t));
    env.addAll(buildDataSourceEnv(t));
    env.addAll(buildIdentityEnv());
    env.addAll(buildProxyEnv(t.python()));
    env.addAll(buildTracingEnv());
    env.addAll(buildAuditEnv());
    List<VolumeMount> mounts =
        surround(
            buildMount("config", CONFIG_DIR, true),
            code.mounts(),
            buildMount("tmp", "/tmp", false));
    List<Volume> volumes =
        surround(buildConfigVolume(t), code.volumes(), buildEmptyDir("tmp", CODE_TMP_SIZE_LIMIT));
    if (settings.auditEnabled()) {
      mounts.add(buildMount("audit-token", AUDIT_TOKEN_DIR, true));
      mounts.add(buildMount("audit-buffer", AUDIT_BUFFER_DIR, false));
      volumes.add(
          new VolumeBuilder()
              .withName("audit-token")
              .withNewSecret()
              .withSecretName(auditSecretName(t.space().name(), t.airlock().name()))
              .endSecret()
              .build());
      volumes.add(buildEmptyDir("audit-buffer", AUDIT_BUFFER_SIZE_LIMIT));
    }
    Container container =
        buildContainer(
            "airlock",
            settings.images().airlock(t.python()),
            8080,
            settings.resources() == null ? null : settings.resources().airlock(),
            env,
            mounts);
    PodSpec pod = buildPod(List.of(container), volumes, settings.airlockPods().runtimeClassName());
    // The selector is immutable: it names the airlock, which the Deployment name carries.
    return buildDeployment(
        t.airlockPod(),
        labels,
        buildAirlockSelector(t),
        settings.airlockPods().replicas(),
        pod,
        t.revision(),
        t.owner());
  }

  private HasMetadata buildIngress(Target t) {
    ObjectMeta meta = buildMeta(t.airlockPod(), buildAirlockSelector(t), t.revision(), t.owner());
    // An exchange streams for minutes: no buffering, a read timeout above the exchange budget.
    meta.setAnnotations(
        new TreeMap<>(
            Map.of(
                "nginx.ingress.kubernetes.io/proxy-read-timeout", "600",
                "nginx.ingress.kubernetes.io/proxy-buffering", "off")));
    return new IngressBuilder()
        .withMetadata(meta)
        .withNewSpec()
        .withIngressClassName(settings.ingress().className())
        .addNewRule()
        .withHost(settings.ingress().host())
        .withNewHttp()
        .addNewPath()
        .withPath("/v1/spaces/" + t.space().name() + "/airlocks/" + t.airlock().name() + "/")
        .withPathType("Prefix")
        .withNewBackend()
        .withNewService()
        .withName(t.airlockPod())
        .withNewPort()
        .withNumber(8080)
        .endPort()
        .endService()
        .endBackend()
        .endPath()
        .endHttp()
        .endRule()
        .endSpec()
        .build();
  }

  private HasMetadata buildAirlockPolicy(Target t) {
    List<NetworkPolicyEgressRule> egress = new ArrayList<>();
    egress.add(Peers.egress(settings.egress().dns(), "UDP", "TCP"));
    egress.addAll(
        buildHostEgress(
            Stream.of(
                    t.llms().stream().map(Llm::url),
                    Stream.of(settings.otlpEndpoint()),
                    t.dataSources().stream().map(DataSource::url))
                .flatMap(urls -> urls)));
    if (t.runsMcpServer()) {
      egress.add(
          new NetworkPolicyEgressRuleBuilder()
              .withTo(
                  new NetworkPolicyPeerBuilder()
                      .withPodSelector(
                          new LabelSelectorBuilder()
                              .withMatchLabels(buildMcpServerSelector(t))
                              .build())
                      .build())
              .withPorts(Peers.port(8081, "TCP"))
              .build());
    }
    if (settings.auditEnabled() || delivers(t)) {
      // The backend's ingest port alone: its API stays out of reach of a pod holding user tokens.
      egress.add(buildBackendIngestEgress());
    }
    egress.addAll(buildCommonEgress());
    return new NetworkPolicyBuilder()
        .withMetadata(buildMeta(t.airlockPod(), buildAirlockSelector(t), t.revision(), t.owner()))
        .withNewSpec()
        .withPodSelector(
            new LabelSelectorBuilder().withMatchLabels(buildAirlockSelector(t)).build())
        .withPolicyTypes("Ingress", "Egress")
        .withIngress(buildPublicIngress())
        .withEgress(egress)
        .endSpec()
        .build();
  }

  /** The backend's ingest port, where an airlock's code is fetched and audit lines handed over. */
  private static NetworkPolicyEgressRule buildBackendIngestEgress() {
    return new NetworkPolicyEgressRuleBuilder()
        .withTo(
            new NetworkPolicyPeerBuilder()
                .withPodSelector(
                    new LabelSelectorBuilder().withMatchLabels(Map.of("app", "backend")).build())
                .build())
        .withPorts(Peers.port(AUDIT_INGEST_PORT, "TCP"))
        .build();
  }

  private HasMetadata buildMcpServerPolicy(Target t) {
    List<NetworkPolicyEgressRule> egress = new ArrayList<>();
    egress.add(Peers.egress(settings.egress().dns(), "UDP", "TCP"));
    egress.addAll(
        buildHostEgress(
            Stream.concat(
                t.dataSources().stream().map(DataSource::url),
                Stream.of(settings.otlpEndpoint()))));
    if (delivers(t)) {
      egress.add(buildBackendIngestEgress());
    }
    egress.addAll(buildCommonEgress());
    return new NetworkPolicyBuilder()
        .withMetadata(buildMeta(t.mcpServer(), buildMcpServerSelector(t), t.revision(), t.owner()))
        .withNewSpec()
        .withPodSelector(
            new LabelSelectorBuilder().withMatchLabels(buildMcpServerSelector(t)).build())
        .withPolicyTypes("Ingress", "Egress")
        .withIngress(
            new NetworkPolicyIngressRuleBuilder()
                .withFrom(
                    new NetworkPolicyPeerBuilder()
                        .withPodSelector(
                            new LabelSelectorBuilder()
                                .withMatchLabels(buildAirlockSelector(t))
                                .build())
                        .build())
                .withPorts(Peers.port(8081, "TCP"))
                .build(),
            buildScraperIngress())
        .withEgress(egress)
        .endSpec()
        .build();
  }

  /**
   * A scraper the cluster runs, in any namespace, by the fixed label: the management port alone,
   * where {@code /actuator/prometheus} answers without a token.
   */
  private static NetworkPolicyIngressRule buildScraperIngress() {
    return new NetworkPolicyIngressRuleBuilder()
        .withFrom(
            new NetworkPolicyPeerBuilder()
                .withNamespaceSelector(new LabelSelectorBuilder().build())
                .withPodSelector(
                    new LabelSelectorBuilder().withMatchLabels(Map.of(SCRAPER, "true")).build())
                .build())
        .withPorts(Peers.port(MANAGEMENT_PORT, "TCP"))
        .build();
  }

  /**
   * The admin's peer for each URL's host; a proxied host has none, the proxy rule covers it. The
   * release refuses a host that is neither.
   */
  private List<NetworkPolicyEgressRule> buildHostEgress(Stream<String> urls) {
    ControllerProperties.Egress egress = settings.egress();
    return urls.filter(Renderer::notBlank)
        .map(Urls::host)
        .filter(Objects::nonNull)
        .distinct()
        .filter(host -> !egress.proxies(host) && egress.hosts().containsKey(host))
        .map(host -> Peers.egress(egress.hosts().get(host)))
        .toList();
  }

  /** The backend hands this airlock's code to its pods: it has code and the install a backend. */
  private boolean delivers(Target t) {
    return t.airlock().hasCode() && settings.codeEnabled();
  }

  /**
   * What a pod needs to fetch the airlock's code at start: the digest it must have, the backend's
   * URL for it and the airlock's code token, read-only on {@code /var/run/aisolate/code}. No code,
   * none of it; no backend (headless), the digest alone, and the pod refuses to start.
   */
  private CodeWiring buildCodeWiring(Target t) {
    if (!t.airlock().hasCode()) {
      return new CodeWiring(List.of(), List.of(), List.of());
    }
    EnvVar digest = buildEnv("AISOLATE_CODE_DIGEST", t.jar().digest());
    if (!delivers(t)) {
      return new CodeWiring(List.of(digest), List.of(), List.of());
    }
    String url =
        String.join(
            "/",
            settings.code().url().strip().replaceAll("/+$", ""),
            t.space().name(),
            t.airlock().name(),
            t.jar().digest());
    Volume token =
        new VolumeBuilder()
            .withName("code-token")
            .withNewSecret()
            .withSecretName(codeSecretName(t.space().name(), t.airlock().name()))
            .endSecret()
            .build();
    return new CodeWiring(
        List.of(
            buildEnv("AISOLATE_CODE_URL", url),
            digest,
            buildEnv("AISOLATE_CODE_TOKEN_FILE", CODE_TOKEN_DIR + "/" + AuditIngestToken.KEY)),
        List.of(buildMount("code-token", CODE_TOKEN_DIR, true)),
        List.of(token));
  }

  /**
   * The LLM that answers by id, and every LLM the airlock sees, indexed in id order: the airlock
   * pod reads its catalog here, never from the documents. An API key stays in its Secret.
   */
  private static List<EnvVar> buildLlmEnv(Target t) {
    List<EnvVar> env = new ArrayList<>();
    env.add(buildEnv("AISOLATE_LLM", t.airlock().spec().llm()));
    for (int i = 0; i < t.llms().size(); i++) {
      Llm llm = t.llms().get(i);
      String prefix = "AISOLATE_LLM_" + i + "_";
      env.add(buildEnv(prefix + "ID", llm.id()));
      env.add(buildEnv(prefix + "PROVIDER", llm.provider()));
      env.add(buildEnv(prefix + "URL", llm.url()));
      env.add(buildEnv(prefix + "MODEL", llm.model()));
      if (notBlank(llm.secretRef())) {
        env.add(buildSecretEnv(prefix + "API_KEY", llm.secretRef(), "apiKey"));
      }
    }
    return env;
  }

  /**
   * The resolved data sources, both pods alike, indexed in id order: Spring binds them as {@code
   * aisolate.datasource[i]}; the credentials stay in each one's Secret.
   */
  private static List<EnvVar> buildDataSourceEnv(Target t) {
    List<EnvVar> env = new ArrayList<>();
    for (int i = 0; i < t.dataSources().size(); i++) {
      DataSource dataSource = t.dataSources().get(i);
      String prefix = "AISOLATE_DATASOURCE_" + i + "_";
      env.add(buildEnv(prefix + "ID", dataSource.id()));
      env.add(buildEnv(prefix + "URL", dataSource.url()));
      env.add(buildSecretEnv(prefix + "USERNAME", dataSource.secretRef(), "username"));
      env.add(buildSecretEnv(prefix + "PASSWORD", dataSource.secretRef(), "password"));
    }
    return env;
  }

  /** A pod's code additions; each list is empty when the airlock has no code. */
  private record CodeWiring(List<EnvVar> env, List<VolumeMount> mounts, List<Volume> volumes) {}

  /** The code additions between {@code config} and {@code tmp}, where both pods keep them. */
  @SafeVarargs
  private static <T> List<T> surround(T first, List<T> plugins, T... last) {
    List<T> all = new ArrayList<>(List.of(first));
    all.addAll(plugins);
    all.addAll(List.of(last));
    return all;
  }

  /** The identity provider (JWKS) and, when rendered, the egress proxy: peers of both pod kinds. */
  private List<NetworkPolicyEgressRule> buildCommonEgress() {
    List<NetworkPolicyEgressRule> rules = new ArrayList<>();
    Map<String, Object> identity = settings.identity().egress();
    if (identity != null && !identity.isEmpty()) rules.add(Peers.egress(identity));
    if (settings.egress().proxy() != null && settings.egress().proxy().enabled()) {
      rules.add(
          new NetworkPolicyEgressRuleBuilder()
              .withTo(
                  new NetworkPolicyPeerBuilder()
                      .withPodSelector(
                          new LabelSelectorBuilder()
                              .withMatchLabels(Map.of(ROLE, "egress-proxy"))
                              .build())
                      .build())
              .withPorts(Peers.port(settings.egress().proxy().port(), "TCP"))
              .build());
    }
    return rules;
  }

  /**
   * The JVM's route to the outside when the proxy is rendered; a policy route alone is none. The
   * hosts the admin maps directly, and the identity provider unless it is proxied, bypass it: the
   * proxy lets through only its own domains, and the JVM would send them every HTTPS host. A Python
   * process ignores the JVM's options: it reads the same route from {@code HTTPS_PROXY} and {@code
   * NO_PROXY}, where a domain suffix is written {@code .svc}.
   */
  private List<EnvVar> buildProxyEnv(boolean python) {
    ControllerProperties.Egress egress = settings.egress();
    ControllerProperties.Proxy proxy = egress.proxy();
    if (proxy == null || !proxy.enabled()) return List.of();
    TreeSet<String> direct = new TreeSet<>(egress.hosts().keySet());
    Stream.of(settings.identity().issuerUri(), settings.identity().jwkSetUri())
        .filter(Renderer::notBlank)
        .map(Urls::host)
        .filter(Objects::nonNull)
        .forEach(direct::add);
    direct.removeIf(egress::proxies);
    direct.addAll(List.of("localhost", "*.svc", "*.cluster.local"));
    EnvVar jvm =
        buildEnv(
            "JDK_JAVA_OPTIONS",
            "-Dhttps.proxyHost="
                + proxy.host()
                + " -Dhttps.proxyPort="
                + proxy.port()
                + " -Dhttp.nonProxyHosts="
                + String.join("|", direct));
    if (!python) return List.of(jvm);
    return List.of(
        jvm,
        buildEnv("HTTPS_PROXY", "http://" + proxy.host() + ":" + proxy.port()),
        buildEnv(
            "NO_PROXY",
            direct.stream()
                .map(host -> host.startsWith("*.") ? host.substring(1) : host)
                .collect(Collectors.joining(","))));
  }

  /**
   * Where the pod sends its traces, an OTLP/HTTP base URL: the pod appends {@code /v1/traces}. None
   * without the setting, and then no span leaves the pod.
   */
  private List<EnvVar> buildTracingEnv() {
    String endpoint = settings.otlpEndpoint();
    return endpoint.isBlank()
        ? List.of()
        : List.of(buildEnv("MANAGEMENT_OTLP_TRACING_ENDPOINT", endpoint));
  }

  /**
   * The airlock's ingest token: the backend takes from this pod only lines naming this airlock.
   * Derived, so the render stays a function of the documents and the settings.
   */
  private HasMetadata buildAuditSecret(Target t) {
    return buildTokenSecret(
        t,
        auditSecretName(t.space().name(), t.airlock().name()),
        AuditIngestToken.of(settings.audit().ingestKey(), t.space().name(), t.airlock().name()));
  }

  /** The airlock's code token: it fetches this airlock's code, nothing else. */
  private HasMetadata buildCodeSecret(Target t) {
    return buildTokenSecret(
        t,
        codeSecretName(t.space().name(), t.airlock().name()),
        AuditIngestToken.ofCode(
            settings.audit().ingestKey(), t.space().name(), t.airlock().name()));
  }

  private HasMetadata buildTokenSecret(Target t, String name, String token) {
    return new SecretBuilder()
        .withMetadata(buildMeta(name, buildAirlockSelector(t), null, t.owner()))
        .withType("Opaque")
        .withData(
            Map.of(
                AuditIngestToken.KEY,
                Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8))))
        .build();
  }

  /** Without an ingest the pod prints its lines to stdout alone. */
  private List<EnvVar> buildAuditEnv() {
    if (!settings.auditEnabled()) return List.of();
    ControllerProperties.Audit audit = settings.audit();
    return List.of(
        buildEnv("AISOLATE_AUDIT_URL", audit.url()),
        buildEnv("AISOLATE_AUDIT_TOKEN_FILE", AUDIT_TOKEN_DIR + "/" + AuditIngestToken.KEY),
        buildEnv("AISOLATE_AUDIT_BUFFER_DIR", AUDIT_BUFFER_DIR),
        buildEnv("AISOLATE_AUDIT_FAIL_CLOSED", String.valueOf(audit.failClosed())),
        buildEnv("AISOLATE_AUDIT_STDOUT", String.valueOf(audit.stdout())));
  }

  private List<EnvVar> buildIdentityEnv() {
    List<EnvVar> env = new ArrayList<>();
    env.add(buildEnv("AISOLATE_ISSUER_URI", settings.identity().issuerUri()));
    if (notBlank(settings.identity().jwkSetUri())) {
      env.add(
          buildEnv(
              "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI",
              settings.identity().jwkSetUri()));
    }
    if (notBlank(settings.identity().rolesClaim())) {
      env.add(buildEnv("AISOLATE_ROLES_CLAIM", settings.identity().rolesClaim()));
    }
    return env;
  }

  private HasMetadata buildDeployment(
      String name,
      Map<String, String> labels,
      Map<String, String> selector,
      int replicas,
      PodSpec pod,
      String revision,
      Optional<OwnerReference> owner) {
    Map<String, String> podLabels = new TreeMap<>(labels);
    podLabels.put(MANAGED_BY, "controller");
    return new DeploymentBuilder()
        .withMetadata(buildMeta(name, labels, revision, owner))
        .withNewSpec()
        .withReplicas(replicas)
        .withNewStrategy()
        .withType("Recreate")
        .endStrategy()
        .withSelector(new LabelSelectorBuilder().withMatchLabels(new TreeMap<>(selector)).build())
        .withNewTemplate()
        .withNewMetadata()
        .withLabels(podLabels)
        .withAnnotations(revision == null ? Map.of() : Map.of(REVISION, revision))
        .endMetadata()
        .withSpec(pod)
        .endTemplate()
        .endSpec()
        .build();
  }

  private HasMetadata buildService(String name, int port, Map<String, String> selector, Target t) {
    return new ServiceBuilder()
        .withMetadata(buildMeta(name, selector, t.revision(), t.owner()))
        .withNewSpec()
        .withSelector(new TreeMap<>(selector))
        .addNewPort()
        .withName("http")
        .withPort(port)
        .withTargetPort(new IntOrString(port))
        .withProtocol("TCP")
        .endPort()
        .endSpec()
        .build();
  }

  private PodSpec buildPod(List<Container> containers, List<Volume> volumes, String runtimeClass) {
    PodSpecBuilder builder =
        new PodSpecBuilder()
            .withAutomountServiceAccountToken(false)
            .withNewSecurityContext()
            .withRunAsNonRoot(true)
            .withNewSeccompProfile()
            .withType("RuntimeDefault")
            .endSeccompProfile()
            .endSecurityContext()
            .withContainers(containers)
            .withVolumes(volumes);
    if (notBlank(runtimeClass)) builder.withRuntimeClassName(runtimeClass);
    return builder.build();
  }

  /** {@code resources} null: none rendered. */
  private Container buildContainer(
      String name,
      String image,
      int port,
      ControllerProperties.Resources resources,
      List<EnvVar> env,
      List<VolumeMount> mounts) {
    ContainerBuilder container =
        new ContainerBuilder()
            .withName(name)
            .withImage(image)
            .withImagePullPolicy(settings.images().pullPolicy())
            .withEnv(env);
    if (resources != null) {
      container
          .withNewResources()
          .withRequests(quantities(resources.requests()))
          .withLimits(quantities(resources.limits()))
          .endResources();
    }
    return container
        .addNewPort()
        .withContainerPort(port)
        .withName("http")
        .endPort()
        .addNewPort()
        .withContainerPort(MANAGEMENT_PORT)
        .withName("management")
        .endPort()
        .withNewReadinessProbe()
        .withNewHttpGet()
        .withPath("/actuator/health/readiness")
        .withPort(new IntOrString(MANAGEMENT_PORT))
        .endHttpGet()
        .withInitialDelaySeconds(10)
        .withPeriodSeconds(5)
        .withFailureThreshold(30)
        .endReadinessProbe()
        .withNewLivenessProbe()
        .withNewHttpGet()
        .withPath("/actuator/health/liveness")
        .withPort(new IntOrString(MANAGEMENT_PORT))
        .endHttpGet()
        .withInitialDelaySeconds(60)
        .withPeriodSeconds(10)
        .endLivenessProbe()
        .withNewSecurityContext()
        .withAllowPrivilegeEscalation(false)
        .withReadOnlyRootFilesystem(true)
        .withNewCapabilities()
        .withDrop("ALL")
        .endCapabilities()
        .endSecurityContext()
        .withVolumeMounts(mounts)
        .build();
  }

  private ObjectMeta buildMeta(
      String name, Map<String, String> labels, String revision, Optional<OwnerReference> owner) {
    Map<String, String> all = new TreeMap<>(labels);
    all.put(MANAGED_BY, "controller");
    if (revision != null) all.put(REVISION, revision);
    ObjectMetaBuilder builder =
        new ObjectMetaBuilder().withName(name).withNamespace(settings.namespace()).withLabels(all);
    owner.ifPresent(builder::withOwnerReferences);
    return builder.build();
  }

  private static Volume buildConfigVolume(Target t) {
    return new VolumeBuilder()
        .withName("config")
        .withNewConfigMap()
        .withName(configMapName(t.space().name(), t.airlock().name()))
        .endConfigMap()
        .build();
  }

  /** Optional: a pod starts before the controller's first pass has written it. */
  private static Volume buildDiscoveryVolume() {
    return new VolumeBuilder()
        .withName("discovery")
        .withNewConfigMap()
        .withName(DISCOVERY)
        .withOptional(true)
        .endConfigMap()
        .build();
  }

  /** Capped: a pod filling its node's disk would evict other spaces' pods. */
  private static Volume buildEmptyDir(String name) {
    return buildEmptyDir(name, TMP_SIZE_LIMIT);
  }

  private static Volume buildEmptyDir(String name, String sizeLimit) {
    return new VolumeBuilder()
        .withName(name)
        .withNewEmptyDir()
        .withSizeLimit(new Quantity(sizeLimit))
        .endEmptyDir()
        .build();
  }

  private static Map<String, Quantity> quantities(Map<String, String> values) {
    Map<String, Quantity> sorted = new TreeMap<>();
    values.forEach((resource, amount) -> sorted.put(resource, new Quantity(amount)));
    return sorted;
  }

  private static VolumeMount buildMount(String name, String path, boolean readOnly) {
    return new VolumeMountBuilder()
        .withName(name)
        .withMountPath(path)
        .withReadOnly(readOnly)
        .build();
  }

  private static EnvVar buildEnv(String name, String value) {
    return new EnvVarBuilder().withName(name).withValue(value).build();
  }

  private static EnvVar buildSecretEnv(String name, String secret, String key) {
    return new EnvVarBuilder()
        .withName(name)
        .withNewValueFrom()
        .withNewSecretKeyRef(key, secret, false)
        .endValueFrom()
        .build();
  }

  private static boolean notBlank(String s) {
    return s != null && !s.isBlank();
  }
}
