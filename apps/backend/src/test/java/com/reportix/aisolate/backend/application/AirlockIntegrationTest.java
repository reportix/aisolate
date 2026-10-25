package com.reportix.aisolate.backend.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.reportix.aisolate.backend.BackendApplication;
import com.reportix.aisolate.backend.domain.space.SpaceRepository;
import com.reportix.aisolate.backend.support.BackendTestConfig;
import com.reportix.aisolate.identity.testing.Http;
import com.reportix.aisolate.identity.testing.Tokens;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ContextConfiguration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The AIsolate UI's API, driven the way the UI drives it: the admin sets up a space with its
 * developers and the roles that may ask every airlock of it, a developer creates an airlock in it,
 * which may add roles of its own and serves at once on its own endpoint. An edit saves the airlock
 * in place and bumps its revision; its id stays. An airlock saved here has no code and runs
 * nothing: what runs (its LLM, prompt, tools and handlers) comes with a deploy, never from the UI's
 * form.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = BackendApplication.class)
@ContextConfiguration(initializers = BackendTestConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AirlockIntegrationTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The seeded space and its seeded airlock. */
  private static final String SPACE = "/v1/spaces/hr-acme";

  private static final String SEEDED = SPACE + "/airlocks/acme-hr";

  private static final String AIRLOCK = SPACE + "/airlocks/hr-app-1";

  /** Created by its tests: two roles, an airlock that lists one of them, then that one removed. */
  private static final String LEDGER = "/v1/spaces/ledger";

  @LocalServerPort int port;
  @LocalManagementPort int managementPort;
  @Autowired DataSource dataSource;

  private final String jasmine = Tokens.forOperator("jasmine", "password");
  private final String rita = Tokens.forOperator("andrew", "password");
  private final String auditor = Tokens.forOperator("auditor", "password");

  /** No LLM: its airlocks set their own. jasmine develops it, employees ask. */
  private static final String SPACE_FORM =
      """
      {"id":"sandbox","name":"Sandbox","description":"Try things out.",
       "developers":["jasmine@example.com"],"roles":["employee"]}
      """;

  private static final String AIRLOCK_FORM =
      """
      {"id":"hr-app-1","name":"HR app 1","description":"Pay questions."}
      """;

  @ParameterizedTest
  @Order(1)
  @CsvSource({"auditor, ''", "jasmine, developer", "rita, admin"})
  void givenCaller_whenListingSpaces_thenTheSeededSpaceOnlyWhereTheyMayOpenItWithTheirRole(
      String caller, String role) {
    // Arrange: the auditor develops none, jasmine develops hr-acme, rita is the admin
    String token = Map.of("auditor", auditor, "jasmine", jasmine, "rita", rita).get(caller);

    // Act
    HttpResponse<String> response = get(token, "/v1/spaces");

    // Assert: one who develops none sees no space at all, not a 403
    assertThat(response.statusCode()).isEqualTo(200);
    List<JsonNode> seeded =
        JSON.readTree(response.body())
            .valueStream()
            .filter(s -> s.path("id").asString().equals("hr-acme"))
            .toList();
    assertThat(seeded.stream().map(s -> s.path("caller_role").asString()))
        .containsExactlyElementsOf(role.isEmpty() ? List.of() : List.of(role));
  }

  @ParameterizedTest
  @Order(1)
  @ValueSource(strings = {"/v1/directory/people?q=nor", SPACE + "/users", SEEDED + "/users"})
  void givenRemovedEndpoint_whenCalledByTheAdmin_then404(String path) {
    // Arrange / Act: the directory and the user lists are gone; developers are typed e-mails
    HttpResponse<String> response = get(rita, path);

    // Assert
    assertThat(response.statusCode()).isEqualTo(404);
  }

  @ParameterizedTest
  @Order(1)
  @CsvSource({
    "api, /readyz, true",
    "api, /actuator/prometheus, false",
    "management, /actuator/prometheus, true",
    "management, /actuator/health/readiness, true"
  })
  void givenNoToken_whenProbed_thenReadinessOnTheApiPortAndActuatorOnlyOnTheManagementPort(
      String which, String path, boolean answers) {
    // Arrange: the AIsolate UI probes the API's host; the kubelet and a scraper the management port
    int target = "api".equals(which) ? port : managementPort;

    // Act
    HttpResponse<String> response =
        Http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + target + path)).build());

    // Assert
    assertThat(response.statusCode() == 200)
        .as("%s %s: %s", which, path, response.statusCode())
        .isEqualTo(answers);
  }

  @Test
  @Order(2)
  void givenSeededAirlock_whenRead_thenItCarriesItsFormItsEndpointAndNoCode() {
    // Arrange / Act
    JsonNode space = JSON.readTree(get(jasmine, SPACE).body());
    JsonNode airlock = JSON.readTree(get(jasmine, SEEDED).body());

    // Assert: the space names its developers, roles, LLM and data sources; the airlock replaces
    // none of them
    assertThat(space.path("name").asString()).isEqualTo("HR Acme");
    assertThat(strings(space.path("developers"))).containsExactly("jasmine@example.com");
    assertThat(strings(space.path("roles"))).containsExactly("ceo");
    assertThat(space.has("members")).isFalse();
    assertThat(space.path("caller_role").asString()).isEqualTo("developer");
    assertThat(space.has("callers")).isFalse();
    assertThat(space.path("llms").path(0).path("id").asString()).isEqualTo("granite");
    assertThat(space.path("llms").path(0).path("url").asString())
        .isEqualTo("http://host.minikube.internal:11434");
    assertThat(space.has("provider") || space.has("llm_url")).isFalse();
    JsonNode dataSource = space.path("data_sources").path(0);
    assertThat(dataSource.path("id").asString()).isEqualTo("acme_hr");
    assertThat(dataSource.path("secret_ref").asString()).isEqualTo("hr-acme-ds-acme-hr-seed01");
    assertThat(dataSource.has("password") || dataSource.has("username")).isFalse();
    assertThat(airlock.path("name").asString()).isEqualTo("Acme HR assistant");
    assertThat(airlock.path("description").asString())
        .isEqualTo("Answers people, pay and HR document questions.");
    assertThat(strings(airlock.path("roles"))).containsExactly("employee");
    assertThat(airlock.has("users")).isFalse();
    assertThat(strings(airlock.path("callers"))).containsExactly("*");
    assertThat(airlock.path("llms").isEmpty()).isTrue();
    assertThat(airlock.path("data_sources").isEmpty()).isTrue();
    assertThat(airlock.has("versions")).isFalse();
    assertThat(airlock.path("revision").asInt()).isEqualTo(1);
    assertThat(airlock.path("created_by").asString()).isEqualTo("jasmine");
    assertThat(airlock.path("endpoint").asString())
        .endsWith("/v1/spaces/hr-acme/airlocks/acme-hr/chat");
    assertThat(airlock.path("jar").isNull()).isTrue();
    JsonNode form = airlock.path("form");
    assertThat(form.path("id").asString()).isEqualTo("acme-hr");
    assertThat(form.path("llm").asString()).isEqualTo("granite");
    assertThat(form.path("prompt").asString()).startsWith("You are the Acme HR assistant.");
    assertThat(airlock.path("main_llm").path("from").asString()).isEqualTo("space");
    assertThat(airlock.path("main_llm").path("model").asString()).isEqualTo("granite4.1:8b");
    assertThat(strings(form.path("tools"))).isEmpty();
    assertThat(strings(form.path("context_preparators"))).isEmpty();
    assertThat(strings(form.path("memory_handlers"))).isEmpty();
    assertThat(strings(form.path("verifiers"))).isEmpty();
    assertThat(form.path("data_sources").isEmpty()).isTrue();
    assertThat(form.has("version") || form.has("allowed_roles") || form.has("rules")).isFalse();
  }

  @Test
  @Order(3)
  void givenInvalidSpaceForm_whenCreateSpace_then400WithFieldErrorsButNoneForTheBlankName() {
    // Arrange
    String bad =
        """
        {"id":"Bad Id","name":""}
        """;

    // Act
    HttpResponse<String> response = post(rita, "/v1/spaces", bad);

    // Assert
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(errors(response))
        .anySatisfy(e -> assertThat(e).startsWith("id:"))
        .noneMatch(e -> e.startsWith("name:"))
        .noneMatch(e -> e.startsWith("developers:") || e.startsWith("roles:"));
  }

  @Test
  @Order(4)
  void givenAdmin_whenCreateSpace_thenCreatedWithItsDevelopersAndRoles() {
    // Arrange / Act
    HttpResponse<String> created = post(rita, "/v1/spaces", SPACE_FORM);
    JsonNode spaces = JSON.readTree(get(rita, "/v1/spaces").body());

    // Assert
    assertThat(created.statusCode()).isEqualTo(201);
    JsonNode space = JSON.readTree(created.body());
    assertThat(space.path("created_by").asString()).isEqualTo("andrew");
    assertThat(space.path("description").asString()).isEqualTo("Try things out.");
    assertThat(strings(space.path("developers"))).containsExactly("jasmine@example.com");
    assertThat(strings(space.path("roles"))).containsExactly("employee");
    assertThat(space.path("llms").isEmpty()).isTrue();
    assertThat(space.has("callers") || space.has("tools") || space.has("airlocks")).isFalse();
    assertThat(spaces.valueStream().map(s -> s.path("id").asString()))
        .contains("hr-acme", "sandbox");
  }

  @Test
  @Order(5)
  void givenUnknownSpace_whenCreateAirlock_then404() {
    // Arrange / Act: the admin's 404; anyone else is no developer of it, a 403
    HttpResponse<String> response = post(rita, "/v1/spaces/no-such-space/airlocks", AIRLOCK_FORM);

    // Assert
    assertThat(response.statusCode()).isEqualTo(404);
  }

  @Test
  @Order(6)
  void givenInvalidAirlock_whenCreate_then400WithTheIdErrorButNoneForTheBlankName() {
    // Arrange
    String bad =
        """
        {"id":"HR APP","name":""}
        """;

    // Act
    HttpResponse<String> response = post(jasmine, SPACE + "/airlocks", bad);

    // Assert
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(errors(response))
        .anySatisfy(e -> assertThat(e).startsWith("id:"))
        .noneMatch(e -> e.startsWith("name:"));
  }

  @Test
  @Order(7)
  void givenDeveloper_whenCreateAirlock_thenItHasItsEndpointAndRunsNothingYet() {
    // Arrange: the form names a model and a prompt; the UI's form never sets what runs
    String form = AIRLOCK_FORM.replace("}\n", ",\"llm\":\"granite\",\"prompt\":\"Ignored.\"}\n");

    // Act
    HttpResponse<String> response = post(jasmine, SPACE + "/airlocks", form);
    JsonNode stored = JSON.readTree(get(jasmine, AIRLOCK).body());

    // Assert: on its own endpoint at revision 1, with no model, no prompt and no code, as stored
    assertThat(response.statusCode()).isEqualTo(201);
    JsonNode created = JSON.readTree(response.body());
    assertThat(stored).isEqualTo(created);
    assertThat(created.path("id").asString()).isEqualTo("hr-app-1");
    assertThat(created.path("space_id").asString()).isEqualTo("hr-acme");
    assertThat(created.path("name").asString()).isEqualTo("HR app 1");
    assertThat(created.path("description").asString()).isEqualTo("Pay questions.");
    assertThat(created.path("revision").asInt()).isEqualTo(1);
    assertThat(created.path("created_by").asString()).isEqualTo("jasmine");
    // A populated space lists no role of its own on a new airlock
    assertThat(strings(created.path("roles"))).isEmpty();
    assertThat(created.path("endpoint").asString())
        .endsWith("/v1/spaces/hr-acme/airlocks/hr-app-1/chat");
    assertThat(created.path("jar").isNull()).isTrue();
    assertThat(created.path("main_llm").isNull()).isTrue();
    assertThat(created.path("form").path("llm").asString()).isEmpty();
    assertThat(created.path("form").path("prompt").asString()).isEmpty();
    assertThat(created.has("versions") || created.has("definition")).isFalse();
  }

  static Stream<Arguments> givenIdTaken_whenCreateAgain_then409SayingItExists() {
    return Stream.of(
        arguments(
            named("a space", "/v1/spaces"),
            SPACE_FORM,
            "id",
            "A space with the id sandbox already exists."),
        // Its own cluster name is taken too: the plain answer comes first
        arguments(
            named("an airlock, in its space", SPACE + "/airlocks"),
            AIRLOCK_FORM,
            "id",
            "An airlock with the id hr-app-1 already exists in this space."),
        // Airlock ids are unique across spaces
        arguments(
            named("an airlock, in another space", "/v1/spaces/sandbox/airlocks"),
            AIRLOCK_FORM,
            "id",
            "An airlock with the id hr-app-1 already exists in space hr-acme."));
  }

  @ParameterizedTest
  @Order(8)
  @MethodSource
  void givenIdTaken_whenCreateAgain_then409SayingItExists(
      String path, String form, String field, String message) {
    // Arrange: each one created by the tests above

    // Act
    HttpResponse<String> response = post(rita, path, form);

    // Assert
    assertConflict(response, field, message);
  }

  @ParameterizedTest
  @Order(9)
  @CsvSource({"GET, /airlocks/hr-app-1", "PUT, /airlocks/hr-app-1"})
  void givenAirlock_whenAddressedThroughAnotherSpace_then404(String method, String path) {
    // Arrange: a valid body, so only the space decides

    // Act
    HttpResponse<String> response =
        send(method, jasmine, "/v1/spaces/sandbox" + path, AIRLOCK_FORM);

    // Assert
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(JSON.readTree(response.body()).path("detail").asString())
        .isEqualTo("unknown airlock");
  }

  @Test
  @Order(10)
  void givenAirlock_whenDeveloperEditsIt_thenSavedInPlaceWithTheIdLocked() {
    // Arrange: an LLM of its own beside the space's; a save names who may ask, as the form does
    String renamed =
        """
        {"id":"hr-app-1","name":"HR app one","description":"Pay and leave questions.",
         "roles":["employee","ceo"],"callers":["*"],
         "llms":[{"id":"nomic","provider":"ollama","url":"http://ollama:11434",
                  "model":"nomic-embed-text"}]}
        """;
    String moved =
        """
        {"id":"hr-app-2","name":"HR app one"}
        """;

    // Act
    HttpResponse<String> response = put(jasmine, AIRLOCK, renamed);
    HttpResponse<String> refused = put(jasmine, AIRLOCK, moved);

    // Assert: the same airlock, one revision on
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode airlock = JSON.readTree(response.body());
    assertThat(airlock.path("name").asString()).isEqualTo("HR app one");
    assertThat(airlock.path("description").asString()).isEqualTo("Pay and leave questions.");
    assertThat(airlock.path("llms").path(0).path("model").asString()).isEqualTo("nomic-embed-text");
    assertThat(airlock.path("revision").asInt()).isEqualTo(2);
    assertThat(OffsetDateTime.parse(airlock.path("updated_at").asString()))
        .isAfter(OffsetDateTime.parse(airlock.path("created_at").asString()));
    assertThat(refused.statusCode()).isEqualTo(400);
    assertThat(errors(refused)).containsExactly("id: cannot be changed");
  }

  @Test
  @Order(11)
  void givenAirlockFormNamingWhatRuns_whenSaved_thenWhatRunsStaysAsStored() {
    // Arrange: a stale tab, or a hand-made request, sends a model, a prompt and tools
    String form =
        """
        {"id":"hr-app-1","name":"HR app one","callers":["*"],
         "llm":"granite","prompt":"From the form.","tools":["read_records"]}
        """;
    int before = JSON.readTree(get(jasmine, AIRLOCK).body()).path("revision").asInt();

    // Act
    HttpResponse<String> response = put(jasmine, AIRLOCK, form);

    // Assert: saved, and only a deploy sets what runs
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    JsonNode saved = JSON.readTree(response.body());
    assertThat(saved.path("revision").asInt()).isEqualTo(before + 1);
    assertThat(saved.path("form").path("llm").asString()).isEmpty();
    assertThat(saved.path("form").path("prompt").asString()).isEmpty();
    assertThat(strings(saved.path("form").path("tools"))).isEmpty();
  }

  @ParameterizedTest
  @Order(14)
  @CsvSource({"GET", "PUT"})
  void givenUnknownAirlock_whenAddressed_then404UnknownAirlock(String method) {
    // Arrange / Act
    HttpResponse<String> response =
        send(method, jasmine, SPACE + "/airlocks/hr-app-9", AIRLOCK_FORM.replace("-1", "-9"));

    // Assert
    assertThat(response.statusCode()).isEqualTo(404);
    assertThat(JSON.readTree(response.body()).path("detail").asString())
        .isEqualTo("unknown airlock");
  }

  @Test
  @Order(15)
  void givenPasswordInLlmUrlAndForeignSecret_whenSaved_then400NeverEchoingThePassword() {
    // Arrange: the form is valid but for the password in its model's URL and a data source naming
    // the seeded airlock's Secret, which hr-app-1 does not own (it stores no acme_hr)
    String bad =
        """
        {"id":"hr-app-1","callers":["*"],
         "llms":[{"id":"granite","provider":"ollama",
                  "url":"http://ollama:hunter2@ollama:11434","model":"m"}],
         "data_sources":[{"id":"acme_hr","url":"jdbc:postgresql://acme-db:5432/hr",
                          "secret_ref":"hr-acme-ds-acme-hr-seed01"}]}
        """;

    // Act
    HttpResponse<String> response = put(jasmine, AIRLOCK, bad);

    // Assert: both fields are named, the password is nowhere in the answer
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(errors(response))
        .satisfiesExactly(
            e -> assertThat(e).startsWith("llms[0].url: carries a password"),
            e ->
                assertThat(e)
                    .isEqualTo("data_sources[0].secret_ref: not a Secret of this airlock"));
    assertThat(response.body()).doesNotContain("hunter2");
  }

  static Stream<Arguments> givenFormJustRead_whenSavedTwiceWithWhatItRead_then200Then409() {
    return Stream.of(
        // The space's own form is the admin's to save
        arguments(named("a space", SPACE), "rita"),
        arguments(named("an airlock", AIRLOCK), "jasmine"));
  }

  @ParameterizedTest
  @Order(16)
  @MethodSource
  void givenFormJustRead_whenSavedTwiceWithWhatItRead_then200Then409(String path, String saver) {
    // Arrange: the UI sends back what it read, quoted as an ETag
    String token = saver.equals("rita") ? rita : jasmine;
    JsonNode read = JSON.readTree(get(token, path).body());
    String ifMatch = "\"" + read.path("updated_at").asString() + "\"";
    String body = JSON.writeValueAsString(read);

    // Act
    HttpResponse<String> first = putIfMatch(token, path, body, ifMatch);
    HttpResponse<String> second = putIfMatch(token, path, body, ifMatch);
    JsonNode stored = JSON.readTree(get(token, path).body());

    // Assert: a current value saves; the first save made it stale, and still stands
    assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
    assertConflict(
        second,
        "updated_at",
        "Saved by someone else since you opened it; reload it and save again.");
    assertThat(stored.path("updated_at")).isEqualTo(JSON.readTree(first.body()).path("updated_at"));
  }

  static Stream<Arguments> givenTwoCreatesRacingForOneId_whenBothInsert_thenOne201AndOne409() {
    return Stream.of(
        arguments(named("space", "/v1/spaces"), SPACE_FORM.replace("sandbox", "raced")),
        arguments(
            named("airlock", SPACE + "/airlocks"), AIRLOCK_FORM.replace("hr-app-1", "raced-app")));
  }

  @ParameterizedTest
  @Order(21)
  @MethodSource
  void givenTwoCreatesRacingForOneId_whenBothInsert_thenOne201AndOne409(String path, String body)
      throws Exception {
    // Arrange: both requests released together; the names lock makes one wait for the other
    CountDownLatch go = new CountDownLatch(1);
    Callable<Integer> create =
        () -> {
          go.await();
          return post(rita, path, body).statusCode();
        };

    // Act
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<Integer> first = pool.submit(create);
      Future<Integer> second = pool.submit(create);
      go.countDown();

      // Assert: neither is a 500
      assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(201, 409);
    }
  }

  @Test
  @Order(22)
  void givenRoleNotOfTheSpace_whenCreateAirlock_thenCreatedWithItAsItsOwn() {
    // Arrange: intern is no role of hr-acme; its holders may ask this airlock alone
    String own =
        """
        {"id":"intern-app","name":"Intern app","roles":["intern"]}
        """;

    // Act
    HttpResponse<String> response = post(jasmine, SPACE + "/airlocks", own);

    // Assert
    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(strings(JSON.readTree(response.body()).path("roles"))).containsExactly("intern");
  }

  @Test
  @Order(24)
  void givenNoCaller_whenUpdateAirlock_then400NeverWidenedToAny() {
    // Arrange: a save in place has no default, it names its callers
    String form = "{\"id\":\"hr-app-1\",\"name\":\"HR app one\",\"roles\":[],\"callers\":[]}";

    // Act
    HttpResponse<String> response = put(jasmine, AIRLOCK, form);

    // Assert
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(errors(response)).containsExactly("callers: must name at least one calling client");
  }

  @Test
  @Order(25)
  void givenRoleListedOnAnAirlock_whenRemovedFromTheSpace_thenSavedAndTheAirlockKeepsIt() {
    // Arrange: a space of two roles, whose airlock also lists founder as its own
    String ledger =
        """
        {"id":"ledger","name":"Ledger","roles":["employee","founder"]}
        """;
    assertThat(post(rita, "/v1/spaces", ledger).statusCode()).isEqualTo(201);
    assertThat(
            post(
                    rita,
                    LEDGER + "/airlocks",
                    "{\"id\":\"ledger-app\",\"name\":\"Ledger app\"," + "\"roles\":[\"founder\"]}")
                .statusCode())
        .isEqualTo(201);
    String withoutFounder = ledger.replace(",\"founder\"", "");

    // Act
    HttpResponse<String> response = put(rita, LEDGER, withoutFounder);

    // Assert: no "remove it there first": founders may still ask ledger-app, and it alone
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(strings(JSON.readTree(response.body()).path("roles"))).containsExactly("employee");
    assertThat(
            strings(JSON.readTree(get(rita, LEDGER + "/airlocks/ledger-app").body()).path("roles")))
        .containsExactly("founder");
  }

  @Test
  @Order(26)
  void givenAirlockSavedWithNoOwnRole_whenSaved_thenStoredWithNone() {
    // Arrange: ledger-app drops founder and narrows its callers
    String narrowed =
        """
        {"id":"ledger-app","name":"Ledger app","roles":[],"callers":["ledger-chat"]}
        """;

    // Act
    HttpResponse<String> airlock = put(rita, LEDGER + "/airlocks/ledger-app", narrowed);

    // Assert
    assertThat(airlock.statusCode()).isEqualTo(200);
    JsonNode saved = JSON.readTree(airlock.body());
    assertThat(strings(saved.path("roles"))).isEmpty();
    assertThat(strings(saved.path("callers"))).containsExactly("ledger-chat");
  }

  @Test
  @Order(28)
  void givenSpaceWithoutRoles_whenCreateAirlock_thenCreatedThatNoOneMayAskYet() {
    // Arrange: a space by name only
    String empty = "/v1/spaces/empty-space";
    HttpResponse<String> space =
        post(rita, "/v1/spaces", "{\"id\":\"empty-space\",\"name\":\"Empty space\"}");

    // Act
    HttpResponse<String> response =
        post(rita, empty + "/airlocks", "{\"id\":\"empty-app\",\"name\":\"Empty app\"}");

    // Assert: allowed, just useless until roles are listed on the space or the airlock
    assertThat(space.statusCode()).isEqualTo(201);
    assertThat(strings(JSON.readTree(space.body()).path("developers"))).isEmpty();
    assertThat(strings(JSON.readTree(space.body()).path("roles"))).isEmpty();
    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(strings(JSON.readTree(response.body()).path("roles"))).isEmpty();
    // The name the UI shows comes from the token's name claim, stored beside the username.
    assertThat(JSON.readTree(response.body()).path("created_by").asString()).isEqualTo("andrew");
    assertThat(JSON.readTree(response.body()).path("created_by_name").asString())
        .isEqualTo("Andrew Hale");
  }

  @Test
  @Order(30)
  void givenClusterNameOfAnAirlock_whenCreateSpace_then409NamingTheClash() {
    // Arrange: the seeded airlock's pods and Secrets are named hr-acme-acme-hr-…
    String clash = "{\"id\":\"hr-acme-acme-hr\",\"name\":\"Clash\"}";

    // Act
    HttpResponse<String> response = post(rita, "/v1/spaces", clash);

    // Assert
    assertConflict(
        response,
        "id",
        "The id hr-acme-acme-hr can't be used here: it would collide with the airlock"
            + " acme-hr in space hr-acme. Choose another id.");
  }

  @ParameterizedTest
  @Order(31)
  @CsvSource(
      delimiter = '|',
      value = {"acme-acme-hr | the airlock acme-hr in space hr-acme", "acme | the space hr-acme"})
  void givenClusterNameTaken_whenCreateAirlockInAnotherSpace_then409NamingTheClash(
      String id, String other) {
    // Arrange: space hr with this airlock would name its objects like the seeded ones
    post(rita, "/v1/spaces", "{\"id\":\"hr\",\"name\":\"HR\"}");

    // Act
    HttpResponse<String> response =
        post(rita, "/v1/spaces/hr/airlocks", "{\"id\":\"" + id + "\",\"name\":\"X\"}");

    // Assert
    assertConflict(
        response,
        "id",
        "The id "
            + id
            + " can't be used here: it would collide with "
            + other
            + ". Choose another"
            + " id.");
  }

  static Stream<Arguments> givenNameWhoseIdWouldCollide_whenCreatedWithoutId_thenTheIdStepsPast() {
    return Stream.of(
        // hr-acme-acme-hr is the cluster name of the seeded hr-acme/acme-hr
        arguments(named("a space", "/v1/spaces"), "HR Acme Acme HR", "hr-acme-acme-hr-2"),
        // hr/acme-acme-hr would be named like the seeded hr-acme/acme-hr, and
        // hr/acme-acme-hr-2 like the space hr-acme-acme-hr-2 the first case created
        arguments(named("an airlock", "/v1/spaces/hr/airlocks"), "Acme Acme HR", "acme-acme-hr-3"));
  }

  @ParameterizedTest
  @Order(33)
  @MethodSource
  void givenNameWhoseIdWouldCollide_whenCreatedWithoutId_thenTheIdStepsPast(
      String path, String name, String id) {
    // Arrange: the AIsolate UI sends no id, so its user could not fix a collision
    post(rita, "/v1/spaces", "{\"id\":\"hr\",\"name\":\"HR\"}");

    // Act
    HttpResponse<String> response = post(rita, path, "{\"name\":\"" + name + "\"}");

    // Assert
    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(JSON.readTree(response.body()).path("id").asString()).isEqualTo(id);
  }

  @Test
  @Order(34)
  void givenAnotherCreateHoldingTheNames_whenCreateAirlock_thenItWaitsAndSeesWhatThatOneAdded()
      throws Exception {
    // Arrange: a create in flight, as the backend runs one, adding the space hr-acme-lock-app
    try (Connection inFlight = dataSource.getConnection();
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      inFlight.setAutoCommit(false);
      try (Statement sql = inFlight.createStatement()) {
        sql.execute("SELECT pg_advisory_xact_lock(" + SpaceRepository.NAMES_LOCK + ")");
        sql.execute("INSERT INTO spaces (id, name) VALUES ('hr-acme-lock-app', 'Lock')");
      }

      // Act: an airlock whose cluster name that space would take
      Future<HttpResponse<String>> create =
          pool.submit(
              () -> post(jasmine, SPACE + "/airlocks", "{\"id\":\"lock-app\",\"name\":\"L\"}"));
      boolean waited = queuesBehind(inFlight);
      inFlight.commit();

      // Assert: it waited for the lock, then found the space; without it, both would pass
      assertThat(waited).isTrue();
      assertConflict(
          create.get(),
          "id",
          "The id lock-app can't be used here: it would collide with the space"
              + " hr-acme-lock-app. Choose another id.");
    }
  }

  @Test
  @Order(35)
  void givenAirlocksResolvingTheSpacesLlm_whenSpaceSavedWithoutIt_then400NamingThemAndKept() {
    // Arrange: two airlocks answer with the space's granite, as a deploy left them; they define
    // none
    String space =
        """
        {"id":"llm-home","name":"LLM home","roles":["employee"]%s}
        """;
    String llm =
        ",\"llms\":[{\"id\":\"granite\",\"provider\":\"ollama\",\"url\":\"http://ollama:11434\","
            + "\"model\":\"granite4.1:8b\"}]";
    String home = "/v1/spaces/llm-home";
    assertThat(post(rita, "/v1/spaces", space.formatted(llm)).statusCode()).isEqualTo(201);
    for (String id : List.of("llm-app", "llm-app-2")) {
      assertThat(
              post(rita, home + "/airlocks", "{\"id\":\"" + id + "\",\"name\":\"LLM app\"}")
                  .statusCode())
          .isEqualTo(201);
      answersWith(id, "granite");
    }

    // Act
    HttpResponse<String> response = put(rita, home, space.formatted(""));

    // Assert: the controller would hold both back; the space keeps its LLM
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(errors(response))
        .containsExactly(
            "llms: llm-home/llm-app and llm-home/llm-app-2 would have no model granite; define it"
                + " on their airlock first");
    assertThat(JSON.readTree(get(rita, home).body()).path("llms").path(0).path("id").asString())
        .isEqualTo("granite");
  }

  @Test
  @Order(36)
  void givenLlmMovedToTheAirlocks_whenOneSavedWithoutIt_then400AndTheSpaceSaveWentThrough() {
    // Arrange: the admin moves the LLM from the space to the airlocks, in that order
    String home = "/v1/spaces/llm-home";
    String airlock =
        """
        {"id":"%s","name":"LLM app","callers":["*"]%s}
        """;
    String llm =
        ",\"llms\":[{\"id\":\"granite\",\"provider\":\"ollama\",\"url\":\"http://ollama:11434\","
            + "\"model\":\"granite4.1:8b\"}]";
    for (String id : List.of("llm-app", "llm-app-2")) {
      assertThat(put(rita, home + "/airlocks/" + id, airlock.formatted(id, llm)).statusCode())
          .isEqualTo(200);
    }
    HttpResponse<String> spaceCleared =
        put(rita, home, "{\"id\":\"llm-home\",\"name\":\"LLM home\"}");

    // Act
    HttpResponse<String> response =
        put(rita, home + "/airlocks/llm-app", airlock.formatted("llm-app", ""));

    // Assert
    assertThat(spaceCleared.statusCode()).as(spaceCleared.body()).isEqualTo(200);
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(errors(response))
        .containsExactly(
            "llms: llm-home/llm-app would have no model granite; define it on their space first");
  }

  @Test
  @Order(37)
  void givenLongSpaceId_whenAirlockNamedPast63Characters_then400NamingTheLength() {
    // Arrange: 22 characters of space; Kubernetes names an airlock's MCP server
    // <space>-<airlock>-mcp-server, at most 63 characters
    String space = "/v1/spaces/acme-financial-holding";
    assertThat(
            post(
                    rita,
                    "/v1/spaces",
                    "{\"id\":\"acme-financial-holding\",\"name\":\"Acme\",\"llms\":[{\"id\":"
                        + "\"granite\",\"provider\":\"ollama\",\"url\":\"http://ollama:11434\","
                        + "\"model\":\"g\"}]}")
                .statusCode())
        .isEqualTo(201);

    // Act
    HttpResponse<String> airlock =
        post(
            rita,
            space + "/airlocks",
            "{\"id\":\"acme-hr-assistant-for-employees-at\",\"name\":\"X\"}");
    HttpResponse<String> fitting =
        post(
            rita, space + "/airlocks", "{\"id\":\"acme-hr-assistant-for-staff-1\",\"name\":\"X\"}");

    // Assert: the controller would refuse it; the backend says so before anything is saved
    assertThat(airlock.statusCode()).isEqualTo(400);
    assertThat(errors(airlock))
        .containsExactly(
            "id: at most 29 characters in the space acme-financial-holding: it runs as"
                + " <space>-<airlock>-mcp-server, and Kubernetes allows 63");
    assertThat(fitting.statusCode()).as(fitting.body()).isEqualTo(201);
  }

  @Test
  @Order(38)
  void givenGlobalLlm_whenAnAirlockAnswersWithIt_thenItResolvesFromGlobalAndItsDeleteWaits() {
    // Arrange: gemma is the install's; an airlock of the seeded space names it, as a deploy would
    String gemma =
        """
        {"id":"gemma","provider":"ollama","url":"http://ollama:11434","model":"gemma3:4b"}
        """;
    HttpResponse<String> created = post(rita, "/v1/llms", gemma);
    HttpResponse<String> again = post(rita, "/v1/llms", gemma);
    HttpResponse<String> invalid =
        post(rita, "/v1/llms", "{\"id\":\"Gemma\",\"provider\":\"bedrock\",\"url\":\"\"}");
    assertThat(
            post(jasmine, SPACE + "/airlocks", "{\"id\":\"global-app\",\"name\":\"G\"}")
                .statusCode())
        .isEqualTo(201);
    answersWith("global-app", "gemma");
    String app = SPACE + "/airlocks/global-app";
    HttpResponse<String> airlock = get(jasmine, app);
    String readAt = JSON.readTree(created.body()).path("updated_at").asString();
    JsonNode listed = JSON.readTree(get(jasmine, SPACE + "/airlocks").body());
    JsonNode llms = JSON.readTree(get(jasmine, "/v1/llms").body());

    // Act
    HttpResponse<String> stale =
        putIfMatch(rita, "/v1/llms/gemma", gemma, "\"2020-01-01T00:00:00Z\"");
    HttpResponse<String> edited =
        putIfMatch(
            rita, "/v1/llms/gemma", gemma.replace("gemma3:4b", "gemma3:12b"), "\"" + readAt + "\"");
    HttpResponse<String> refused = send("DELETE", rita, "/v1/llms/gemma", "");
    answersWith("global-app", "granite");
    HttpResponse<String> deleted = send("DELETE", rita, "/v1/llms/gemma", "");

    // Assert
    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
    assertThat(again.statusCode()).isEqualTo(409);
    assertThat(errors(again)).containsExactly("id: a model gemma already exists");
    assertThat(invalid.statusCode()).isEqualTo(400);
    assertThat(errors(invalid))
        .containsExactly(
            "id: 1 to 41 lowercase letters, digits, _ or -, starting with a letter or digit",
            "provider: bedrock is not one of [ollama, openai]",
            "url: required",
            "model: required");
    assertThat(JSON.readTree(airlock.body()).path("main_llm").path("from").asString())
        .isEqualTo("global");
    assertThat(
            listed
                .valueStream()
                .filter(a -> a.path("id").asString().equals("global-app"))
                .map(a -> a.path("main_llm").path("model").asString()))
        .containsExactly("gemma3:4b");
    assertThat(llms.valueStream().map(l -> l.path("id").asString())).contains("gemma");
    assertThat(JSON.readTree(get(jasmine, "/v1/llms/nope").body()).path("detail").asString())
        .isEqualTo("no model named nope");
    assertThat(stale.statusCode()).isEqualTo(409);
    assertThat(errors(stale))
        .containsExactly(
            "updated_at: Saved by someone else since you opened it; reload it and save again.");
    assertThat(JSON.readTree(edited.body()).path("model").asString()).isEqualTo("gemma3:12b");
    assertThat(refused.statusCode()).isEqualTo(400);
    assertThat(errors(refused))
        .containsExactly(
            "id: hr-acme/global-app would have no model gemma; define it on its space first");
    assertThat(deleted.statusCode()).isEqualTo(204);
    assertThat(get(jasmine, "/v1/llms/gemma").statusCode()).isEqualTo(404);
  }

  /** What runs is a deploy's to set: the row as one left it, naming the model that answers. */
  private void answersWith(String airlockId, String llm) {
    try (Connection db = dataSource.getConnection();
        Statement sql = db.createStatement()) {
      sql.execute(
          "UPDATE airlocks SET definition = definition || '{\"llm\":\""
              + llm
              + "\",\"prompt\":\"p\"}' WHERE id = '"
              + airlockId
              + "'");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private HttpResponse<String> putIfMatch(String bearer, String path, String body, String ifMatch) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .header("If-Match", ifMatch)
            .PUT(HttpRequest.BodyPublishers.ofString(body));
    return Http.send(Http.bearer(request, bearer).build());
  }

  /** Whether another session waits on an advisory lock within ten seconds. */
  private static boolean queuesBehind(Connection session) throws Exception {
    for (int i = 0; i < 100; i++) {
      try (Statement sql = session.createStatement();
          ResultSet waiting =
              sql.executeQuery(
                  "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted")) {
        waiting.next();
        if (waiting.getInt(1) > 0) {
          return true;
        }
      }
      Thread.sleep(100);
    }
    return false;
  }

  /** A 409 whose message the form shows on {@code field}. */
  private static void assertConflict(HttpResponse<String> response, String field, String message) {
    assertThat(response.statusCode()).isEqualTo(409);
    assertThat(JSON.readTree(response.body()).path("detail").asString()).isEqualTo(message);
    assertThat(errors(response)).containsExactly(field + ": " + message);
  }

  private static List<String> errors(HttpResponse<String> response) {
    return strings(JSON.readTree(response.body()).path("errors"));
  }

  private static List<String> strings(JsonNode array) {
    return array.valueStream().map(JsonNode::asString).toList();
  }

  private HttpResponse<String> get(String bearer, String path) {
    return send("GET", bearer, path, null);
  }

  private HttpResponse<String> post(String bearer, String path, String body) {
    return send("POST", bearer, path, body);
  }

  private HttpResponse<String> put(String bearer, String path, String body) {
    return send("PUT", bearer, path, body);
  }

  private HttpResponse<String> send(String method, String bearer, String path, String body) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .method(
                method,
                method.equals("GET")
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
    return Http.send(Http.bearer(request, bearer).build());
  }
}
