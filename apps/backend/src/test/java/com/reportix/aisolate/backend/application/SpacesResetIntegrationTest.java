package com.reportix.aisolate.backend.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.reportix.aisolate.backend.BackendApplication;
import com.reportix.aisolate.backend.domain.audit.AuditRepository;
import com.reportix.aisolate.backend.domain.audit.AuditService;
import com.reportix.aisolate.backend.domain.credential.CredentialRepository;
import com.reportix.aisolate.backend.domain.space.SpacesDeletedDTO;
import com.reportix.aisolate.backend.infrastructure.cluster.ClusterSyncService;
import com.reportix.aisolate.backend.support.BackendTestConfig;
import com.reportix.aisolate.backend.support.OwnDatabase;
import com.reportix.aisolate.config.AuditIngestToken;
import com.reportix.aisolate.identity.testing.Http;
import com.reportix.aisolate.identity.testing.Tokens;
import com.reportix.aisolate.plugins.testing.PluginJars;
import com.sun.net.httpserver.HttpServer;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * DELETE /spaces, the clean-slate endpoint: the database emptied, then the cluster cleared, then
 * the audit trail and the metrics store.
 */
// The mock extension first: it injects the client Spring's context (built per class) hands out.
@EnableKubernetesMockClient(crud = true)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = BackendApplication.class)
// A database of its own: the wipe must not take the other classes' seed.
@ContextConfiguration(initializers = {BackendTestConfig.class, OwnDatabase.class})
@TestPropertySource(
    properties = {"aisolate.cluster.namespace=aisolate", "aisolate.test.database=aisolate_reset"})
@Import(SpacesResetIntegrationTest.MockCluster.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SpacesResetIntegrationTest {

  static KubernetesClient client; // injected by the mock server extension
  static KubernetesMockServer server;

  /** Prometheus's admin API: what the backend asked, answered as told. */
  static final Queue<String> ADMIN = new ConcurrentLinkedQueue<>();

  static volatile int adminStatus = 204;
  static final HttpServer PROMETHEUS = prometheus();

  @DynamicPropertySource
  static void metricsStore(DynamicPropertyRegistry registry) {
    registry.add(
        "aisolate.metrics.query-url",
        () -> "http://localhost:" + PROMETHEUS.getAddress().getPort());
  }

  @TestConfiguration
  static class MockCluster {
    @Bean
    @Primary
    KubernetesClient kubernetesClient() {
      return client;
    }
  }

  static final ResourceDefinitionContext SPACES = context("Space", "spaces");
  static final ResourceDefinitionContext AIRLOCKS = context("Airlock", "airlocks");
  static final JsonMapper JSON = JsonMapper.builder().build();
  static final String DB = "jdbc:postgresql://acme-db:5432/acme_hr";

  /** A data source with its login on the space and on the airlock's deploy: one Secret each. */
  static final String SPACE_FORM =
      """
      {"id":"reset-lab","name":"Reset lab",
       "developers":["jasmine@example.com"],"roles":["employee"],
       "llms":[{"id":"granite","provider":"ollama","url":"http://ollama:11434","model":"granite4.1:8b"}],
       "data_sources":[{"id":"hr-documents","url":"%s","username":"preparator",
                        "password":"hunter2"}]}
      """
          .formatted(DB);

  static final String AIRLOCK_FORM =
      """
      {"id":"reset-assistant","name":"Reset assistant"}
      """;

  static final String DOCUMENTS =
      """
      apiVersion: aisolate.dev/v1
      kind: Airlock
      metadata: {name: reset-assistant}
      spec:
        llm: granite
        prompt: Answer from the tools.
        tools: [read_records, aggregate_records]
        dataSources:
          - id: acme_hr
            url: jdbc:postgresql://acme-db:5432/acme_hr
            username: reader
            password: ${HR_PASSWORD}
      """;

  @LocalServerPort int port;
  private final String jasmine = Tokens.forOperator("jasmine", "password");
  private final String rita = Tokens.forOperator("andrew", "password");
  @Autowired ClusterSyncService clusterSync;
  @Autowired CredentialRepository rows;
  @Autowired AuditService audit;
  @Autowired AuditRepository auditRows;

  @ParameterizedTest
  @Order(1)
  @ValueSource(strings = {"auditor", "jasmine"})
  void givenNoAdminRole_whenAllSpacesDeleted_then403(String user) {
    // Arrange: the auditor, then a developer of one team: every team's spaces are the admin's
    String token = Tokens.forOperator(user, "password");

    // Act
    HttpResponse<String> response = deleteAllSpaces(token);

    // Assert
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(listSpaces().size()).isPositive();
  }

  @Test
  @Order(3)
  void givenDeployedSpace_whenAllSpacesDeleted_thenResourcesSecretsOfBothKindsAndJarsGo() {
    // Arrange: the seeded hr-acme and a space deployed here, a Secret owned by each of the two
    // resources, and the controller's own pod, not one to wait for
    setUpResetLab();
    List<String> secrets =
        List.of(
            secretRefOf(resource(SPACES, "reset-lab")),
            secretRefOf(resource(AIRLOCKS, "reset-assistant")));
    assertThat(secrets)
        .allSatisfy(name ->
            assertThat(client.secrets().inNamespace("aisolate").withName(name).get()).isNotNull());
    pod("aisolate-controller-0", "controller");

    // Act
    HttpResponse<String> response = deleteAllSpaces(rita);

    // Assert: every row gone
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = JSON.readTree(response.body());
    assertThat(body.path("spaces").asInt()).isGreaterThanOrEqualTo(2);
    assertThat(body.path("airlocks").asInt()).isGreaterThanOrEqualTo(1);
    assertThat(body.has("versions")).isFalse();
    assertThat(listSpaces().size()).isZero();
    JsonNode cluster = body.path("cluster");
    assertThat(cluster.path("pods_gone").asBoolean()).isTrue();
    assertThat(cluster.path("problem").isString()).isFalse();
    // The mock has no garbage collector: gone because the reset deleted them by name
    assertThat(secrets)
        .allSatisfy(name ->
            assertThat(client.secrets().inNamespace("aisolate").withName(name).get()).isNull());
    assertThat(
            client.genericKubernetesResources(AIRLOCKS).inNamespace("aisolate").list().getItems())
        .isEmpty();
    assertThat(client.genericKubernetesResources(SPACES).inNamespace("aisolate").list().getItems())
        .isEmpty();
  }

  @Test
  @Order(4)
  void givenSpaceAndInstallCredentials_whenReset_thenTheSpaceRowsGoAndTheInstallsStay() {
    // Arrange: a login on the install and one on a space, each sealed in a row
    HttpRequest.Builder global =
        HttpRequest.newBuilder(URI.create(base() + "/data-sources"))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"id\":\"reset-ledger\",\"url\":\""
                        + DB
                        + "\","
                        + "\"username\":\"auditor\",\"password\":\"l3dger\"}"));
    HttpResponse<String> installed = Http.send(Http.bearer(global, rita).build());
    assertThat(installed.statusCode()).as(installed.body()).isEqualTo(201);
    String installRef = JSON.readTree(installed.body()).path("secret_ref").asString();
    create("/spaces", SPACE_FORM);
    String spaceRef = secretRefOf(resource(SPACES, "reset-lab"));
    assertThat(rows.find(spaceRef)).isPresent();
    assertThat(rows.find(installRef)).isPresent();

    // Act
    HttpResponse<String> response = deleteAllSpaces(rita);

    // Assert
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(rows.find(spaceRef)).isEmpty();
    assertThat(rows.events(spaceRef).getLast().action()).isEqualTo("deleted");
    assertThat(rows.find(installRef)).isPresent();
  }

  @Test
  @Order(5)
  void givenAuditLinesAndAMetricsStore_whenAllSpacesDeleted_thenTheTrailAndTheSeriesGo() {
    // Arrange: one line of an airlock, as its pod hands it in; the store's admin API is on
    String token =
        AuditIngestToken.of(BackendTestConfig.INGEST_KEY, "reset-lab", "reset-assistant");
    audit.ingest(token, line("What is my notice period?"));
    assertThat(auditRows.countAll()).isEqualTo(1);
    ADMIN.clear();
    adminStatus = 204;

    // Act
    HttpResponse<String> response = deleteAllSpaces(rita);

    // Assert: the trail empty, the product's series deleted, and the next line chains again
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode monitoring = JSON.readTree(response.body()).path("monitoring");
    assertThat(monitoring.path("audit_lines").asInt()).isEqualTo(1);
    assertThat(monitoring.path("metrics_cleared").asBoolean()).isTrue();
    assertThat(monitoring.path("problem").isNull()).isTrue();
    assertThat(ADMIN)
        .containsExactly(
            "POST /api/v1/admin/tsdb/delete_series match[]={__name__=~\"aisolate_.*|gen_ai_.*\"}",
            "POST /api/v1/admin/tsdb/clean_tombstones ");
    assertThat(auditRows.countAll()).isZero();
    assertThat(audit.ingest(token, line("And my salary?")).repeat()).isFalse();
    assertThat(auditRows.countAll()).isEqualTo(1);
  }

  @Test
  @Order(6)
  void
      givenMetricsStoreWithoutItsAdminApi_whenAllSpacesDeleted_thenTheTrailGoesAndTheStoreIsNamed() {
    // Arrange: Prometheus without --web.enable-admin-api; the line the test before left
    adminStatus = 503;

    // Act
    HttpResponse<String> response = deleteAllSpaces(rita);

    // Assert
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode monitoring = JSON.readTree(response.body()).path("monitoring");
    assertThat(monitoring.path("audit_lines").asInt()).isEqualTo(1);
    assertThat(monitoring.path("metrics_cleared").asBoolean()).isFalse();
    assertThat(monitoring.path("problem").asString())
        .isEqualTo("HTTP 503 from /api/v1/admin/tsdb/delete_series: admin APIs disabled");
    assertThat(auditRows.countAll()).isZero();
  }

  @Test
  @Order(7)
  void givenAirlockPodOutlivingTheWait_whenCleared_thenPodsNotGoneWithoutProblem() {
    // Arrange: the mock has no garbage collector, so this pod stays
    pod("reset-lab-reset-assistant-airlock-0", "airlock");

    // Act
    SpacesDeletedDTO.Cluster cluster = clusterSync.clear(Duration.ofMillis(200));

    // Assert
    assertThat(cluster.podsGone()).isFalse();
    assertThat(cluster.problem()).isNull();
  }

  @Test
  @Order(8)
  void givenApiServerGone_whenAllSpacesDeleted_thenRowsGoneAndProblemReported() {
    // Arrange: the database is the source of truth; the cluster is reported, never required
    create(
        "/spaces", "{\"id\":\"reset-lab\",\"name\":\"Reset lab\"," + "\"roles\":[\"employee\"]}");
    server.shutdown();

    // Act
    HttpResponse<String> response = deleteAllSpaces(rita);

    // Assert
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode body = JSON.readTree(response.body());
    assertThat(body.path("spaces").asInt()).isEqualTo(1);
    assertThat(body.path("cluster").path("pods_gone").asBoolean()).isFalse();
    assertThat(body.path("cluster").path("problem").asString()).isNotBlank();
    assertThat(listSpaces().size()).isZero();
  }

  /**
   * The admin's space and airlock, then its developer's deploy of it: a login on the space and one
   * in the deploy.
   */
  private void setUpResetLab() {
    create("/spaces", SPACE_FORM);
    create("/spaces/reset-lab/airlocks", AIRLOCK_FORM);
    Map<String, Object> parts = new LinkedHashMap<>();
    parts.put(
        "jar",
        new Http.FilePart(
            "project.jar", PluginJars.buildJar(PluginJars.compile(PluginJars.HR_TOOLS))));
    parts.put("documents", DOCUMENTS);
    parts.put("variables", "{\"HR_PASSWORD\":\"hunter2\"}");
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(base() + "/spaces/reset-lab/deploy"));
    HttpResponse<String> deployed =
        Http.send(Http.bearer(Http.multipart(request, "POST", parts), jasmine).build());
    assertThat(deployed.statusCode()).as(deployed.body()).isEqualTo(200);
  }

  private static GenericKubernetesResource resource(ResourceDefinitionContext kind, String name) {
    GenericKubernetesResource resource =
        client.genericKubernetesResources(kind).inNamespace("aisolate").withName(name).get();
    assertThat(resource).as(name).isNotNull();
    return resource;
  }

  /** The Secret name of the resource's first data source. */
  private static String secretRefOf(GenericKubernetesResource resource) {
    List<Map<String, Object>> dataSources = resource.get("spec", "dataSources");
    return (String) dataSources.getFirst().get("secretRef");
  }

  private static void pod(String name, String role) {
    client
        .pods()
        .inNamespace("aisolate")
        .resource(
            new PodBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace("aisolate")
                .addToLabels("aisolate.dev/role", role)
                .endMetadata()
                .build())
        .create();
  }

  private void create(String path, String body) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(base() + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    HttpResponse<String> created = Http.send(Http.bearer(request, rita).build());
    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
  }

  private HttpResponse<String> deleteAllSpaces(String token) {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base() + "/spaces")).DELETE();
    return Http.send(Http.bearer(request, token).build());
  }

  private JsonNode listSpaces() {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base() + "/spaces")).GET();
    return JSON.readTree(Http.send(Http.bearer(request, rita).build()).body());
  }

  private String base() {
    return "http://localhost:" + port + "/v1";
  }

  /** An exchange line of the reset lab's airlock, as its pod writes it. */
  private static String line(String question) {
    return """
    {"ts":"2026-10-18T09:11:57.006Z","event":"exchange","trace_id":"4bf92f3577b34da6a3ce929d0e0e4736",\
    "space":"reset-lab","airlock":"reset-assistant","revision":"7b2280eab4b5",\
    "user":"jasmine@example.com","client":"acme-chat","question":"%s",\
    "answer":"Three months.","outcome":"answered","reason":null,\
    "tools":[{"name":"read_my_records","args":{"entity":"hr.contracts"},\
    "data_source":"hr-documents","rows":1,"rule_version":"1.7b2280eab4b5","sql_hash":"3f9a1c0d2b7e",\
    "rows_hash":"5c1e"}],"evidence":["ev-1"],"verifiers":[],\
    "figures":{"sourced":0,"computed":0,"unsourced":0,"mismatch":0},"grounding":null,\
    "model":{"provider":"ollama","name":"granite4.1:8b","calls":1,"input_tokens":184,\
    "output_tokens":21},"duration_ms":611}\
    """
        .formatted(question);
  }

  private static HttpServer prometheus() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
      server.createContext(
          "/api/v1/admin/",
          exchange -> {
            String form =
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            ADMIN.add(
                exchange.getRequestMethod()
                    + " "
                    + exchange.getRequestURI().getPath()
                    + " "
                    + URLDecoder.decode(form, StandardCharsets.UTF_8));
            if (adminStatus == 204) {
              exchange.sendResponseHeaders(204, -1);
            } else {
              byte[] body =
                  "{\"status\":\"error\",\"errorType\":\"unavailable\",\"error\":\"admin APIs disabled\"}"
                      .getBytes(StandardCharsets.UTF_8);
              exchange.sendResponseHeaders(adminStatus, body.length);
              exchange.getResponseBody().write(body);
            }
            exchange.close();
          });
      server.start();
      return server;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static ResourceDefinitionContext context(String kind, String plural) {
    return new ResourceDefinitionContext.Builder()
        .withGroup("aisolate.dev")
        .withVersion("v1")
        .withKind(kind)
        .withPlural(plural)
        .withNamespaced(true)
        .build();
  }
}
