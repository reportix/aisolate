package com.reportix.aisolate.backend.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.reportix.aisolate.backend.BackendApplication;
import com.reportix.aisolate.backend.infrastructure.cluster.ClusterSyncService;
import com.reportix.aisolate.backend.support.BackendTestConfig;
import com.reportix.aisolate.identity.testing.Http;
import com.reportix.aisolate.identity.testing.Keycloak;
import com.reportix.aisolate.identity.testing.Tokens;
import com.reportix.aisolate.plugins.testing.PluginJars;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.fabric8.mockwebserver.http.RecordedRequest;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * A deploy ends in the cluster: the Space and Airlock resources, a Secret per data source login
 * owned by the resource that defines it, and no ConfigMap of code: the pods fetch it. The AIsolate
 * UI's edits follow.
 */
// The mock extension first: it injects the client Spring's context (built per class) hands out.
@EnableKubernetesMockClient(crud = true)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = BackendApplication.class)
@ContextConfiguration(initializers = BackendTestConfig.class)
// The resync is scheduled every five minutes: pushed out of reach, so only the tests call it.
@TestPropertySource(
    properties = {"aisolate.cluster.namespace=aisolate", "aisolate.cluster.resync=PT24H"})
@Import(ClusterSyncIntegrationTest.MockCluster.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ClusterSyncIntegrationTest {

  static KubernetesClient client; // injected by the mock server extension
  static KubernetesMockServer server;

  @TestConfiguration
  static class MockCluster {
    @Bean
    @Primary
    KubernetesClient kubernetesClient() {
      return client;
    }
  }

  static final ResourceDefinitionContext GLOBALS = context("Global", "globals");
  static final ResourceDefinitionContext SPACES = context("Space", "spaces");
  static final ResourceDefinitionContext AIRLOCKS = context("Airlock", "airlocks");
  static final JsonMapper JSON = JsonMapper.builder().build();
  static final String SPACE_PATH = "/v1/spaces/cluster-lab";
  static final String AIRLOCK_PATH = SPACE_PATH + "/airlocks/cluster-assistant";
  static final String GLOBAL_PATH = "/apis/aisolate.dev/v1/namespaces/aisolate/globals/aisolate";

  /** The space as the admin saves it: eric asks, jasmine develops, and its LLM granite. */
  static final String SPACE_FORM =
      """
      {"id":"cluster-lab","name":"Cluster lab",
       "members":[{"email":"eric@example.com","role":"user"},
                  {"email":"jasmine@example.com","role":"developer"}],
       "llms":[{"id":"granite","provider":"ollama","url":"http://host.minikube.internal:11434",
                "model":"granite4.1:8b"}]%s}
      """;

  /** The project file: cluster-assistant, its data source's login from .env. */
  static final String DOCUMENTS =
      """
      apiVersion: aisolate.dev/v1
      kind: Airlock
      metadata: {name: cluster-assistant}
      spec:
        llm: granite
        prompt: Answer from the tools.
        tools: [read_records, aggregate_records]
        dataSources:
          - id: acme_hr
            url: jdbc:postgresql://acme-db.aisolate.svc:5432/acme_hr
            username: ${HR_USERNAME}
            password: ${HR_PASSWORD}
      """;

  static final String VARIABLES = "{\"HR_USERNAME\":\"reader\",\"HR_PASSWORD\":\"hunter2\"}";

  @LocalServerPort int port;
  @Autowired ClusterSyncService clusterSync;
  @Autowired ScheduledTaskHolder scheduled;
  private final String jasmine =
      Tokens.forUser(Keycloak.issuerUri(), "aisolate-ui", "jasmine", "password");
  private final String rita =
      Tokens.forUser(Keycloak.issuerUri(), "aisolate-ui", "andrew", "password");

  // Airlock ids are global in the backend: this class owns cluster-lab and its names alone.
  // Per test, because the Spring context (and its port) exists only once an instance does: 201 the
  // first time, 409 after.
  @BeforeEach
  void createSpaceAndAirlock() {
    assertThat(post("/v1/spaces", SPACE_FORM.formatted("")).statusCode()).isIn(201, 409);
    assertThat(
            post(SPACE_PATH + "/airlocks", "{\"id\":\"cluster-assistant\",\"name\":\"Cluster\"}")
                .statusCode())
        .isIn(201, 409);
  }

  @Test
  @Order(1)
  void
      givenDeployWithCredentials_whenCommitted_thenResourcesAndSecretAreAppliedAndNoCodeConfigMap() {
    // Arrange: the HR tools jar the test support compiles
    byte[] jar = projectJar(Map.of());

    // Act
    HttpResponse<String> response = deploy(jar, DOCUMENTS, VARIABLES);

    // Assert: the response says applied, the mock holds what the controller renders from
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(JSON.readTree(response.body()).path("cluster").path("applied").asBoolean()).isTrue();
    GenericKubernetesResource space = resource(SPACES, "cluster-lab");
    // Every member asks, the developer too; roles stay in the backend
    assertThat((List<String>) space.get("spec", "access", "users"))
        .containsExactly("eric@example.com", "jasmine@example.com");
    assertThat((List<Map<String, String>>) space.get("spec", "llms"))
        .singleElement()
        .satisfies(
            llm -> assertThat(llm).containsEntry("url", "http://host.minikube.internal:11434"));
    // Every airlock sees the install's Global, applied with the space even when it is empty
    assertThat(resource(GLOBALS, "aisolate")).isNotNull();
    GenericKubernetesResource airlock = resource(AIRLOCKS, "cluster-assistant");
    assertThat((String) airlock.get("spec", "space")).isEqualTo("cluster-lab");
    // The space's users ask it; it lists none of its own
    assertThat((List<String>) airlock.get("spec", "access", "users")).isEmpty();
    assertThat((List<String>) airlock.get("spec", "access", "callers")).containsExactly("*");
    assertThat((String) airlock.get("spec", "llm")).isEqualTo("granite");
    assertThat((String) airlock.get("spec", "prompt")).isEqualTo("Answer from the tools.");
    assertThat((String) airlock.get("spec", "jar", "digest")).startsWith("sha256:");
    assertThat((List<String>) airlock.get("spec", "jar", "tools"))
        .containsExactly("read_records", "aggregate_records");
    String secretRef = secretRefOf(airlock);
    assertThat(secretRef).matches("cluster-lab-cluster-assistant-ds-acme-hr-[a-z0-9]{6}");
    Secret secret = client.secrets().inNamespace("aisolate").withName(secretRef).get();
    assertThat(decode(secret, "username")).isEqualTo("reader");
    assertThat(decode(secret, "password")).isEqualTo("hunter2");
    assertThat(secret.getType()).isEqualTo("Opaque");
    assertThat(secret.getMetadata().getOwnerReferences())
        .singleElement()
        .satisfies(
            owner -> {
              assertThat(owner.getKind()).isEqualTo("Airlock");
              assertThat(owner.getName()).isEqualTo("cluster-assistant");
              assertThat(owner.getUid()).isEqualTo(airlock.getMetadata().getUid());
            });
    JsonNode dataSource =
        JSON.readTree(get(AIRLOCK_PATH).body()).path("form").path("data_sources").path(0);
    assertThat(dataSource.path("secret_ref").asString()).isEqualTo(secretRef);
    assertThat(dataSource.has("password") || dataSource.has("username")).isFalse();
    assertThat(client.configMaps().inNamespace("aisolate").list().getItems()).isEmpty();
  }

  @Test
  @Order(2)
  void givenDeployedAirlock_whenEditedWithoutPassword_thenItFollowsAndKeepsSecretJarAndWhatRuns() {
    // Arrange: an AIsolate UI edit, not a deploy
    String secretRef = secretRefOf(resource(AIRLOCKS, "cluster-assistant"));

    // Act
    HttpResponse<String> response = put(AIRLOCK_PATH, form("In one sentence.", ""));

    // Assert: the UI tells "saved" from "saved, not applied" by this header
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(response.headers().firstValue("AIsolate-Cluster")).contains("applied");
    GenericKubernetesResource airlock = resource(AIRLOCKS, "cluster-assistant");
    assertThat((String) airlock.get("spec", "description")).isEqualTo("In one sentence.");
    assertThat((String) airlock.get("spec", "prompt")).isEqualTo("Answer from the tools.");
    assertThat(secretRefOf(airlock)).isEqualTo(secretRef);
    assertThat((String) airlock.get("spec", "jar", "digest")).startsWith("sha256:");
  }

  @Test
  @Order(3)
  void givenNewPassword_whenEdited_thenANewSecretAndTheOldOneDeleted() {
    // Arrange
    String old = secretRefOf(resource(AIRLOCKS, "cluster-assistant"));

    // Act
    HttpResponse<String> response = put(AIRLOCK_PATH, form("Again.", "n3w-pass"));

    // Assert
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(response.body()).doesNotContain("n3w-pass");
    String renewed = secretRefOf(resource(AIRLOCKS, "cluster-assistant"));
    assertThat(renewed).isNotEqualTo(old);
    assertThat(decode(client.secrets().inNamespace("aisolate").withName(renewed).get(), "password"))
        .isEqualTo("n3w-pass");
    assertThat(client.secrets().inNamespace("aisolate").withName(old).get()).isNull();
  }

  @Test
  @Order(4)
  void givenNewAirlockNamingAnothersSecret_whenCreated_thenRefusedUntilItBringsItsOwnLogin() {
    // Arrange: an airlock made from cluster-assistant's page, its data source as that one keeps
    // it, then with a login of its own
    String source = secretRefOf(resource(AIRLOCKS, "cluster-assistant"));
    String reused =
        """
        {"id":"cluster-other","name":"Other",
         "data_sources":[{"id":"acme_hr",
                          "url":"jdbc:postgresql://acme-db.aisolate.svc:5432/acme_hr",
                          "secret_ref":"%s"}]}
        """
            .formatted(source);
    String ownLogin =
        reused.replace(
            "\"secret_ref\":\"" + source + "\"",
            "\"username\":\"reader\",\"password\":\"other-pass\"");

    // Act
    HttpResponse<String> refused = post(SPACE_PATH + "/airlocks", reused);
    HttpResponse<String> created = post(SPACE_PATH + "/airlocks", ownLogin);

    // Assert: an airlock keeps only the Secrets made for it
    assertThat(refused.statusCode()).isEqualTo(400);
    assertThat(JSON.readTree(refused.body()).path("errors").get(0).asString())
        .isEqualTo("data_sources[0].secret_ref: not a Secret of this airlock");
    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
    String own = secretRefOf(resource(AIRLOCKS, "cluster-other"));
    assertThat(own).matches("cluster-lab-cluster-other-ds-acme-hr-[a-z0-9]{6}");
    assertThat(decode(client.secrets().inNamespace("aisolate").withName(own).get(), "password"))
        .isEqualTo("other-pass");
    assertThat(client.secrets().inNamespace("aisolate").withName(source).get()).isNotNull();
  }

  @Test
  @Order(5)
  void givenSpaceDataSourceWithPassword_whenSaved_thenSecretOwnedByTheSpaceResource() {
    // Arrange: every airlock of the space reads it
    String withDataSource =
        SPACE_FORM.formatted(
            ",\"data_sources\":[{\"id\":\"hr-documents\","
                + "\"url\":\"jdbc:postgresql://acme-db.aisolate.svc:5432/acme_hr\","
                + "\"username\":\"preparator\",\"password\":\"d0cs\"}]");

    // Act
    HttpResponse<String> response = put(SPACE_PATH, withDataSource);

    // Assert
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).doesNotContain("d0cs");
    String secretRef =
        JSON.readTree(response.body()).path("data_sources").path(0).path("secret_ref").asString();
    assertThat(secretRef).matches("cluster-lab-ds-hr-documents-[a-z0-9]{6}");
    GenericKubernetesResource space = resource(SPACES, "cluster-lab");
    assertThat(secretRefOf(space)).isEqualTo(secretRef);
    Secret secret = client.secrets().inNamespace("aisolate").withName(secretRef).get();
    assertThat(decode(secret, "password")).isEqualTo("d0cs");
    assertThat(secret.getMetadata().getOwnerReferences())
        .singleElement()
        .satisfies(
            owner -> {
              assertThat(owner.getKind()).isEqualTo("Space");
              assertThat(owner.getName()).isEqualTo("cluster-lab");
              assertThat(owner.getUid()).isEqualTo(space.getMetadata().getUid());
            });
  }

  @Test
  @Order(6)
  void givenJarAboveTheFormerConfigMapCeiling_whenDeployed_thenStoredAndApplied() {
    // Arrange: the same jar with a 1.2 MB random resource (zeros would deflate away), past 1 MiB
    byte[] padding = new byte[1_200_000];
    new java.util.Random(7).nextBytes(padding);
    byte[] jar = projectJar(Map.of("padding.bin", padding));

    // Act
    HttpResponse<String> response = deploy(jar, DOCUMENTS, VARIABLES);

    // Assert
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(JSON.readTree(response.body()).path("cluster").path("applied").asBoolean()).isTrue();
    assertThat((String) resource(AIRLOCKS, "cluster-assistant").get("spec", "jar", "digest"))
        .isEqualTo(JSON.readTree(response.body()).path("jar").path("digest").asString());
    assertThat(client.configMaps().inNamespace("aisolate").list().getItems()).isEmpty();
  }

  @Test
  @Order(7)
  void givenResourcesGoneFromTheCluster_whenTheScheduledResyncRuns_thenTheyAreBack() {
    // Arrange: an apply that failed, or an API server that lost them
    client
        .genericKubernetesResources(AIRLOCKS)
        .inNamespace("aisolate")
        .withName("cluster-assistant")
        .delete();
    client
        .genericKubernetesResources(SPACES)
        .inNamespace("aisolate")
        .withName("cluster-lab")
        .delete();

    // Act
    clusterSync.resyncAll();

    // Assert: healed, and the healing is scheduled, not only callable
    assertThat((List<String>) resource(SPACES, "cluster-lab").get("spec", "access", "users"))
        .containsExactly("eric@example.com", "jasmine@example.com");
    assertThat((String) resource(AIRLOCKS, "cluster-assistant").get("spec", "jar", "digest"))
        .startsWith("sha256:");
    assertThat(scheduled.getScheduledTasks())
        .anySatisfy(
            task ->
                assertThat(task.getTask().toString())
                    .isEqualTo(ClusterSyncService.class.getName() + ".resyncAll"));
  }

  @Test
  @Order(8)
  void givenGlobalDataSourceWithALogin_whenSavedEditedAndDeleted_thenTheGlobalOwnsItsSecret() {
    // Arrange: ledger is the install's; acme_hr too, read by an airlock that defines none
    String ledger =
        """
        {"id":"ledger","url":"jdbc:postgresql://audit-db.aisolate.svc:5432/ledger",
         "username":"auditor","password":"l3dger"}
        """;
    String acme =
        """
        {"id":"acme_hr","url":"jdbc:postgresql://acme-db.aisolate.svc:5432/acme_hr",
         "username":"reader","password":"hunter2"}
        """;
    String withoutLogin =
        DOCUMENTS
            .substring(0, DOCUMENTS.indexOf("  dataSources:"))
            .replace("cluster-assistant", "cluster-nine");

    // Act
    HttpResponse<String> created = admin("POST", "/v1/data-sources", ledger, null);
    String readAt = JSON.readTree(created.body()).path("updated_at").asString();
    String secretRef = JSON.readTree(created.body()).path("secret_ref").asString();
    Secret secret = client.secrets().inNamespace("aisolate").withName(secretRef).get();
    GenericKubernetesResource aisolate = resource(GLOBALS, "aisolate");
    // The form sends back a Secret name it read, or any other: the stored one stays
    HttpResponse<String> edited =
        admin(
            "PUT",
            "/v1/data-sources/ledger",
            "{\"url\":\"jdbc:postgresql://audit-db.aisolate.svc:5432/ledger\","
                + "\"secret_ref\":\"someone-elses\"}",
            "\"" + readAt + "\"");
    HttpResponse<String> global = admin("POST", "/v1/data-sources", acme, null);
    HttpResponse<String> deployed = deploy(projectJar(Map.of()), withoutLogin, null);
    HttpResponse<String> refused = admin("DELETE", "/v1/data-sources/acme_hr", "", null);
    HttpResponse<String> deleted = admin("DELETE", "/v1/data-sources/ledger", "", null);
    // Its own acme_hr first: then the global one may go, as the other tests expect
    deploy(projectJar(Map.of()), DOCUMENTS.replace("cluster-assistant", "cluster-nine"), VARIABLES);
    HttpResponse<String> freed = admin("DELETE", "/v1/data-sources/acme_hr", "", null);

    // Assert
    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
    assertThat(created.headers().firstValue("AIsolate-Cluster")).contains("applied");
    assertThat(created.body()).doesNotContain("l3dger", "auditor");
    assertThat(secretRef).matches("aisolate-ds-ledger-[a-z0-9]{6}");
    assertThat(decode(secret, "password")).isEqualTo("l3dger");
    assertThat(secret.getMetadata().getOwnerReferences())
        .singleElement()
        .satisfies(
            owner -> {
              assertThat(owner.getKind()).isEqualTo("Global");
              assertThat(owner.getName()).isEqualTo("aisolate");
              assertThat(owner.getUid()).isEqualTo(aisolate.getMetadata().getUid());
            });
    assertThat(edited.statusCode()).as(edited.body()).isEqualTo(200);
    assertThat(JSON.readTree(edited.body()).path("secret_ref").asString()).isEqualTo(secretRef);
    assertThat(global.statusCode()).as(global.body()).isEqualTo(201);
    assertThat(deployed.statusCode()).as(deployed.body()).isEqualTo(200);
    assertThat(refused.statusCode()).isEqualTo(400);
    assertThat(JSON.readTree(refused.body()).path("errors").get(0).asString())
        .startsWith(
            "id: cluster-lab/cluster-nine would have no data source acme_hr, which its plugin")
        .endsWith(" reads; define it on its space first");
    assertThat(deleted.statusCode()).isEqualTo(204);
    assertThat(client.secrets().inNamespace("aisolate").withName(secretRef).get()).isNull();
    assertThat(freed.statusCode()).as(freed.body()).isEqualTo(204);
    assertThat((List<?>) resource(GLOBALS, "aisolate").get("spec", "dataSources")).isEmpty();
  }

  @Test
  @Order(9)
  void givenFileWithALogin_whenDeployedTwice_thenItsSecretIsTheAirlocksAndItsResourceFollows() {
    // Arrange: a new airlock, its data source's login from the variables, deployed then again
    String documents =
        DOCUMENTS
            .replace("cluster-assistant", "cluster-block")
            .replace("spec:\n", "spec:\n  description: First.\n");
    byte[] jar = projectJar(Map.of());

    // Act
    HttpResponse<String> created = deploy(jar, documents, VARIABLES);
    HttpResponse<String> updated = deploy(jar, documents.replace("First.", "Second."), VARIABLES);

    // Assert
    assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
    assertThat(JSON.readTree(created.body()).path("airlock").path("created").asBoolean()).isTrue();
    assertThat(updated.statusCode()).as(updated.body()).isEqualTo(200);
    assertThat(JSON.readTree(updated.body()).path("airlock").path("created").asBoolean()).isFalse();
    assertThat(JSON.readTree(updated.body()).path("airlock").path("revision").asInt()).isEqualTo(2);
    GenericKubernetesResource resource = resource(AIRLOCKS, "cluster-block");
    assertThat((String) resource.get("spec", "description")).isEqualTo("Second.");
    String secretRef = secretRefOf(resource);
    assertThat(secretRef).matches("cluster-lab-cluster-block-ds-acme-hr-[a-z0-9]{6}");
    Secret secret = client.secrets().inNamespace("aisolate").withName(secretRef).get();
    assertThat(decode(secret, "username")).isEqualTo("reader");
    assertThat(decode(secret, "password")).isEqualTo("hunter2");
    assertThat(secret.getMetadata().getOwnerReferences())
        .singleElement()
        .satisfies(owner -> assertThat(owner.getKind()).isEqualTo("Airlock"));
    assertThat(updated.body()).doesNotContain("hunter2");
  }

  @Test
  @Order(10)
  void givenGlobalOpenAiModelWithAKey_whenSavedEditedAndMadeOllama_thenItsSecretHoldsItThenGoes() {
    // Arrange: an OpenAI-compatible model with its API key, typed once
    String gpt =
        """
        {"id":"gpt","provider":"openai","url":"https://api.openai.example/v1",
         "model":"gpt-4o-mini","api_key":"sk-l1ve-k3y"}
        """;

    // Act
    HttpResponse<String> created = admin("POST", "/v1/llms", gpt, null);
    String secretRef = JSON.readTree(created.body()).path("secret_ref").asString();
    Secret secret = client.secrets().inNamespace("aisolate").withName(secretRef).get();
    List<Map<String, Object>> served = resource(GLOBALS, "aisolate").get("spec", "llms");
    // The form sends no key back: the stored one stays
    HttpResponse<String> edited =
        admin(
            "PUT",
            "/v1/llms/gpt",
            "{\"provider\":\"openai\",\"url\":\"https://api.openai.example/v1\","
                + "\"model\":\"gpt-4.1-mini\"}",
            null);
    HttpResponse<String> moved =
        admin(
            "PUT",
            "/v1/llms/gpt",
            "{\"provider\":\"openai\",\"url\":\"https://elsewhere.example/v1\","
                + "\"model\":\"gpt-4.1-mini\"}",
            null);
    HttpResponse<String> ollama =
        admin(
            "PUT",
            "/v1/llms/gpt",
            "{\"provider\":\"ollama\",\"url\":\"http://host.minikube.internal:11434\","
                + "\"model\":\"gemma3\"}",
            null);
    HttpResponse<String> deleted = admin("DELETE", "/v1/llms/gpt", "", null);

    // Assert: the key in a Secret of the Global's, named in its document, never in a response
    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
    assertThat(created.body()).doesNotContain("sk-l1ve-k3y");
    assertThat(secretRef).matches("aisolate-llm-gpt-[a-z0-9]{6}");
    assertThat(decode(secret, "apiKey")).isEqualTo("sk-l1ve-k3y");
    assertThat(secret.getMetadata().getOwnerReferences())
        .singleElement()
        .satisfies(owner -> assertThat(owner.getKind()).isEqualTo("Global"));
    assertThat(served)
        .filteredOn(llm -> "gpt".equals(llm.get("id")))
        .singleElement()
        .satisfies(llm -> assertThat(llm.get("secretRef")).isEqualTo(secretRef));
    assertThat(edited.statusCode()).as(edited.body()).isEqualTo(200);
    assertThat(JSON.readTree(edited.body()).path("secret_ref").asString()).isEqualTo(secretRef);
    assertThat(moved.statusCode()).isEqualTo(400);
    assertThat(JSON.readTree(moved.body()).path("errors").get(0).asString())
        .isEqualTo("api_key: required again with a new url");
    assertThat(ollama.statusCode()).as(ollama.body()).isEqualTo(200);
    assertThat(JSON.readTree(ollama.body()).path("secret_ref").asString("")).isEmpty();
    assertThat(client.secrets().inNamespace("aisolate").withName(secretRef).get()).isNull();
    assertThat(deleted.statusCode()).isEqualTo(204);
  }

  @Test
  @Order(11)
  void givenSpaceOpenAiModelWithAKey_whenSaved_thenSecretOwnedByTheSpaceResource() {
    // Arrange: the space as the AIsolate UI read it, a model with a key added
    JsonNode space = JSON.readTree(admin("GET", SPACE_PATH, "", null).body());
    ((ArrayNode) space.path("llms"))
        .addObject()
        .put("id", "gpt")
        .put("provider", "openai")
        .put("url", "https://api.openai.example/v1")
        .put("model", "gpt-4o-mini")
        .put("api_key", "sk-sp4ce-k3y");

    // Act
    HttpResponse<String> response = put(SPACE_PATH, JSON.writeValueAsString(space));

    // Assert
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(response.body()).doesNotContain("sk-sp4ce-k3y");
    JsonNode gpt = JSON.readTree(response.body()).path("llms").get(1);
    assertThat(gpt.path("id").asString()).isEqualTo("gpt");
    String secretRef = gpt.path("secret_ref").asString();
    assertThat(secretRef).matches("cluster-lab-llm-gpt-[a-z0-9]{6}");
    Secret secret = client.secrets().inNamespace("aisolate").withName(secretRef).get();
    assertThat(decode(secret, "apiKey")).isEqualTo("sk-sp4ce-k3y");
    assertThat(secret.getMetadata().getOwnerReferences())
        .singleElement()
        .satisfies(owner -> assertThat(owner.getKind()).isEqualTo("Space"));
    List<Map<String, Object>> llms = resource(SPACES, "cluster-lab").get("spec", "llms");
    assertThat(llms.get(1).get("secretRef")).isEqualTo(secretRef);
  }

  @Test
  @Order(12)
  void givenTwoSpaces_whenResyncAllRuns_thenTheGlobalIsWrittenOnce() throws InterruptedException {
    // Arrange: a second space beside cluster-lab, and the mock's request log emptied
    String secondSpace =
        SPACE_FORM
            .formatted("")
            .replace("\"id\":\"cluster-lab\"", "\"id\":\"cluster-lab-2\"")
            .replace("\"name\":\"Cluster lab\"", "\"name\":\"Cluster lab 2\"");
    assertThat(post("/v1/spaces", secondSpace).statusCode()).isIn(201, 409);
    recordedRequests();

    // Act
    clusterSync.resyncAll();

    // Assert: only PUTs count, createOr's POST (409) and GET come with each of them
    List<String> puts =
        recordedRequests().stream()
            .filter(request -> request.getMethod().equals("PUT"))
            .map(RecordedRequest::getPath)
            .toList();
    assertThat(puts).filteredOn(GLOBAL_PATH::equals).hasSize(1);
    assertThat(puts)
        .contains(
            "/apis/aisolate.dev/v1/namespaces/aisolate/spaces/cluster-lab",
            "/apis/aisolate.dev/v1/namespaces/aisolate/spaces/cluster-lab-2");
  }

  @Test
  @Order(13)
  void givenClusterRefusesTheSecret_whenAirlockCreatedWithALogin_thenNoRowNoResourceNoSecret()
      throws InterruptedException {
    // Arrange: the Airlock resource is created, its Secret refused as a full quota refuses it; a
    // 5xx would not do, the client retries it and the retry reaches the mock's store
    server
        .expect()
        .post()
        .withPath("/api/v1/namespaces/aisolate/secrets")
        .andReturn(403, "{}")
        .once();
    String id = "cluster-refused";
    String withLogin =
        """
        {"id":"%s","name":"Refused",
         "data_sources":[{"id":"acme_hr",
                          "url":"jdbc:postgresql://acme-db.aisolate.svc:5432/acme_hr",
                          "username":"reader","password":"refused-pass"}]}
        """
            .formatted(id);
    recordedRequests();

    // Act
    HttpResponse<String> refused = post(SPACE_PATH + "/airlocks", withLogin);
    List<String> requests = requestLines();

    // Assert: the rollback takes the cluster's objects along with the row
    assertThat(refused.statusCode()).as(refused.body()).isEqualTo(400);
    assertThat(refused.body()).contains("nothing was saved");
    assertThat(get(SPACE_PATH + "/airlocks/" + id).statusCode()).isEqualTo(404);
    assertThat(requests).contains("POST /apis/aisolate.dev/v1/namespaces/aisolate/airlocks");
    assertThat(
            client.genericKubernetesResources(AIRLOCKS).inNamespace("aisolate").withName(id).get())
        .isNull();
    assertThat(client.secrets().inNamespace("aisolate").list().getItems())
        .extracting(secret -> secret.getMetadata().getName())
        .noneMatch(name -> name.startsWith("cluster-lab-" + id + "-"));
  }

  @Test
  @Order(14)
  void givenClusterRefusesTheKeysSecret_whenCreatedWithALoginAndAKey_thenTheLoginsSecretGoesToo()
      throws InterruptedException {
    // Arrange: the login's Secret taken, the key's refused. The mock matches by method and path
    // alone, so the login's 201 is an expectation too: its store never holds that Secret.
    String secrets = "/api/v1/namespaces/aisolate/secrets";
    server.expect().post().withPath(secrets).andReturn(201, "{}").once();
    server.expect().post().withPath(secrets).andReturn(403, "{}").once();
    String id = "cluster-keyed";
    String withLoginAndKey =
        """
        {"id":"%s","name":"Keyed",
         "llms":[{"id":"gpt","provider":"openai","url":"https://api.openai.example/v1",
                  "model":"gpt-4o-mini","api_key":"sk-k3yed"}],
         "data_sources":[{"id":"acme_hr",
                          "url":"jdbc:postgresql://acme-db.aisolate.svc:5432/acme_hr",
                          "username":"reader","password":"keyed-pass"}]}
        """
            .formatted(id);
    recordedRequests();

    // Act
    HttpResponse<String> refused = post(SPACE_PATH + "/airlocks", withLoginAndKey);
    List<String> requests = requestLines();

    // Assert: the key's refusal deletes none of the login's; the rollback does
    assertThat(refused.statusCode()).as(refused.body()).isEqualTo(400);
    assertThat(refused.body()).contains("nothing was saved");
    assertThat(get(SPACE_PATH + "/airlocks/" + id).statusCode()).isEqualTo(404);
    assertThat(requests).contains("POST /apis/aisolate.dev/v1/namespaces/aisolate/airlocks");
    assertThat(requests)
        .anyMatch(
            line ->
                line.matches(
                    "DELETE " + secrets + "/cluster-lab-" + id + "-ds-acme-hr-[a-z0-9]{6}"));
    assertThat(
            client.genericKubernetesResources(AIRLOCKS).inNamespace("aisolate").withName(id).get())
        .isNull();
    assertThat(client.secrets().inNamespace("aisolate").list().getItems())
        .extracting(secret -> secret.getMetadata().getName())
        .noneMatch(name -> name.startsWith("cluster-lab-" + id + "-"));
  }

  @Test
  @Order(15)
  void givenApiServerGone_whenDeployed_thenCredentialsRefusedElseStoredAndReportedNotApplied() {
    // Arrange: the database is the source of truth; the cluster is reported, never required, but
    // credentials have nowhere to go without it
    server.shutdown();
    String withoutLogin =
        DOCUMENTS.replace("      username: ${HR_USERNAME}\n      password: ${HR_PASSWORD}\n", "");

    // Act
    HttpResponse<String> refused = deploy(projectJar(Map.of()), DOCUMENTS, VARIABLES);
    HttpResponse<String> stored = deploy(projectJar(Map.of()), withoutLogin, null);

    // Assert
    assertThat(refused.statusCode()).isEqualTo(400);
    assertThat(JSON.readTree(refused.body()).path("errors").get(0).asString())
        .isEqualTo(
            "Airlock cluster-assistant: data_sources[0].password: the cluster did not take the"
                + " credentials; nothing was saved");
    assertThat(refused.body()).doesNotContain("hunter2");
    assertThat(stored.statusCode()).isEqualTo(200);
    JsonNode cluster = JSON.readTree(stored.body()).path("cluster");
    assertThat(cluster.path("applied").asBoolean()).isFalse();
    assertThat(cluster.path("problem").asString()).isNotBlank();
  }

  /** The form of cluster-assistant as the AIsolate UI sends it back: nothing of what runs. */
  private static String form(String description, String password) {
    return """
    {"id":"cluster-assistant","name":"Cluster","description":"%s","callers":["*"],
     "data_sources":[{"id":"acme_hr",
                      "url":"jdbc:postgresql://acme-db.aisolate.svc:5432/acme_hr",
                      "username":"reader","password":"%s"}]}
    """
        .formatted(description, password);
  }

  /** The HR tools plugin as a project's build writes it. */
  private static byte[] projectJar(Map<String, byte[]> extraEntries) {
    Map<String, byte[]> entries = new LinkedHashMap<>(PluginJars.compile(PluginJars.HR_TOOLS));
    entries.putAll(extraEntries);
    return PluginJars.buildJar(entries);
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

  private static String decode(Secret secret, String key) {
    return new String(
        Base64.getDecoder().decode(secret.getData().get(key)), StandardCharsets.UTF_8);
  }

  private HttpResponse<String> deploy(byte[] jar, String documents, String variables) {
    Map<String, Object> parts = new LinkedHashMap<>();
    parts.put("jar", new Http.FilePart("project.jar", jar));
    parts.put("documents", documents);
    if (variables != null) {
      parts.put("variables", variables);
    }
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + SPACE_PATH + "/deploy"));
    return Http.send(Http.bearer(Http.multipart(request, "POST", parts), jasmine).build());
  }

  private HttpResponse<String> get(String path) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
    return Http.send(Http.bearer(request, jasmine).build());
  }

  /** The install's own parts are the admin's. */
  private HttpResponse<String> admin(String method, String path, String body, String ifMatch) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body));
    if (ifMatch != null) {
      request.header("If-Match", ifMatch);
    }
    return Http.send(Http.bearer(request, rita).build());
  }

  private HttpResponse<String> put(String path, String body) {
    return send(path, "PUT", body);
  }

  private HttpResponse<String> post(String path, String body) {
    return send(path, "POST", body);
  }

  private HttpResponse<String> send(String path, String method, String body) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body));
    // The space itself is the admin's; what is in it, its developer's.
    boolean spaceItself = path.equals("/v1/spaces") || path.equals(SPACE_PATH);
    return Http.send(Http.bearer(request, spaceItself ? rita : jasmine).build());
  }

  /** Every request the mock server has seen since the last call, in order. */
  private static List<RecordedRequest> recordedRequests() throws InterruptedException {
    List<RecordedRequest> requests = new ArrayList<>();
    for (RecordedRequest request = server.takeRequest(100, TimeUnit.MILLISECONDS);
        request != null;
        request = server.takeRequest(100, TimeUnit.MILLISECONDS)) {
      requests.add(request);
    }
    return requests;
  }

  /** {@link #recordedRequests}, each as {@code <method> <path>}. */
  private static List<String> requestLines() throws InterruptedException {
    return recordedRequests().stream()
        .map(request -> request.getMethod() + " " + request.getPath())
        .toList();
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
