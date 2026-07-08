package com.reportix.aisolate.backend.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.reportix.aisolate.backend.BackendApplication;
import com.reportix.aisolate.backend.support.BackendTestConfig;
import com.reportix.aisolate.backend.support.OwnDatabase;
import com.reportix.aisolate.identity.testing.Http;
import com.reportix.aisolate.identity.testing.Keycloak;
import com.reportix.aisolate.identity.testing.Tokens;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The directory against the test Keycloak's read-only service account (aisolate-directory): the
 * AIsolate UI searches people, sees who each listed user is, and cannot add an e-mail nobody signs
 * in with. The seeded space and airlock list eric and nora, both in the realm; jasmine develops the
 * space, which only the admin saves.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = BackendApplication.class)
// A database of its own: a test writes a user the realm does not know straight into the seed.
@ContextConfiguration(initializers = {BackendTestConfig.class, OwnDatabase.class})
@TestPropertySource(properties = "aisolate.test.database=aisolate_directory")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DirectoryIntegrationTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String SPACE = "/v1/spaces/hr-acme";
  private static final String AIRLOCK = SPACE + "/airlocks/acme-hr";

  @DynamicPropertySource
  static void directory(DynamicPropertyRegistry registry) {
    registry.add("aisolate.directory.url", () -> Keycloak.issuerUri().replace("/realms/acme", ""));
    registry.add("aisolate.directory.realm", () -> "acme");
    registry.add("aisolate.directory.client-id", () -> "aisolate-directory");
    registry.add("aisolate.directory.client-secret", () -> "aisolate-directory-dev-secret");
  }

  @LocalServerPort int port;
  @Autowired JdbcClient db;

  private final String jasmine =
      Tokens.forUser(Keycloak.issuerUri(), "aisolate-ui", "jasmine", "password");
  private final String rita =
      Tokens.forUser(Keycloak.issuerUri(), "aisolate-ui", "andrew", "password");
  private final String eric = Tokens.forUser("eric", "password");

  @ParameterizedTest
  @Order(1)
  @ValueSource(strings = {"/v1/directory/people?q=nor", SPACE + "/users", AIRLOCK + "/users"})
  void givenUserOfTheSpace_whenDirectoryEndpoint_then403(String path) {
    // Arrange / Act: the company's people are its developers' and the admin's, not an asker's
    HttpResponse<String> response = get(eric, path);

    // Assert
    assertThat(response.statusCode()).isEqualTo(403);
  }

  @Test
  @Order(1)
  void givenDeveloper_whenSearchingNor_thenNoraByNameUsernameAndEmailOnly() {
    // Arrange / Act
    HttpResponse<String> response = get(jasmine, "/v1/directory/people?q=nor");

    // Assert
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode people = JSON.readTree(response.body());
    assertThat(people.size()).isEqualTo(1);
    assertThat(people.path(0).propertyNames())
        .containsExactlyInAnyOrder("name", "username", "email");
    assertThat(people.path(0).path("name").asString()).isEqualTo("Nora Varga");
    assertThat(people.path(0).path("email").asString()).isEqualTo("nora@example.com");
  }

  @Test
  @Order(1)
  void givenSeededAirlock_whenItsUsersRead_thenEachNamedAndNoneInheritedTwice() {
    // Arrange / Act
    JsonNode users = JSON.readTree(get(jasmine, AIRLOCK + "/users").body());

    // Assert: the airlock lists both of the space's users itself; the developer asks it as well
    assertThat(users.path("directory").asBoolean()).isTrue();
    assertThat(names(users.path("users"))).containsExactly("Eric Lindholm", "Nora Varga");
    assertThat(users.path("users").path(0).path("in_directory").asBoolean()).isTrue();
    assertThat(names(users.path("inherited"))).containsExactly("Jasmine Keller");
  }

  @Test
  @Order(2)
  void givenEmailNobodySignsInWith_whenAddedToTheSpaceOrAirlock_then400NotInTheDirectory() {
    // Arrange
    List<String> spaceUsers = List.of("eric@example.com", "nora@example.com", "ghost@example.com");
    List<String> airlockUsers =
        List.of("eric@example.com", "nora@example.com", "Ghost@example.com");

    // Act
    HttpResponse<String> space = saveUsers(SPACE, spaceUsers);
    HttpResponse<String> airlock = saveUsers(AIRLOCK, airlockUsers);

    // Assert
    assertThat(space.statusCode()).isEqualTo(400);
    assertThat(errors(space)).containsExactly("members: ghost@example.com is not in the directory");
    assertThat(airlock.statusCode()).isEqualTo(400);
    assertThat(errors(airlock)).containsExactly("users: Ghost@example.com is not in the directory");
  }

  @Test
  @Order(3)
  void givenPersonInTheDirectory_whenAddedToTheSpace_thenSavedAndListedByName() {
    // Arrange
    List<String> users = List.of("eric@example.com", "nora@example.com", "LEO@example.com");

    // Act
    HttpResponse<String> saved = saveUsers(SPACE, users);
    JsonNode listed = JSON.readTree(get(jasmine, SPACE + "/users").body());

    // Assert
    assertThat(saved.statusCode()).isEqualTo(200);
    assertThat(names(listed.path("users")))
        .containsExactly("Eric Lindholm", "Nora Varga", "Leo Brandt", "Jasmine Keller");
    assertThat(listed.path("users").valueStream().map(u -> u.path("role").asString()))
        .containsExactly("user", "user", "user", "developer");
  }

  @Test
  @Order(4)
  void givenUserWhoLeftTheDirectory_whenListedThenSavedAgain_thenFlaggedAndKept() {
    // Arrange: listed while no directory checked the save, or since removed from the realm
    db.sql(
            "UPDATE spaces SET definition = jsonb_set(definition, '{members}',"
                + " '[{\"email\": \"eric@example.com\", \"role\": \"user\"},"
                + " {\"email\": \"left@example.com\", \"role\": \"user\"},"
                + " {\"email\": \"jasmine@example.com\", \"role\": \"developer\"}]')"
                + " WHERE id = 'hr-acme'")
        .update();

    // Act: the save keeps her, as an edit of the LLM or a removal of someone else would
    JsonNode users = JSON.readTree(get(jasmine, SPACE + "/users").body());
    HttpResponse<String> saved = saveUsers(SPACE, List.of("eric@example.com", "left@example.com"));

    // Assert
    JsonNode left = users.path("users").path(1);
    assertThat(left.path("email").asString()).isEqualTo("left@example.com");
    assertThat(left.path("in_directory").asBoolean()).isFalse();
    assertThat(left.path("name").isNull()).isTrue();
    assertThat(saved.statusCode()).isEqualTo(200);
  }

  private static List<String> names(JsonNode users) {
    return StreamSupport.stream(users.spliterator(), false)
        .map(user -> user.path("name").asString())
        .toList();
  }

  private static List<String> errors(HttpResponse<String> response) {
    return StreamSupport.stream(JSON.readTree(response.body()).path("errors").spliterator(), false)
        .map(JsonNode::asString)
        .toList();
  }

  private HttpResponse<String> get(String bearer, String path) {
    return Http.send(Http.bearer(HttpRequest.newBuilder(uri(path)).GET(), bearer).build());
  }

  /**
   * As the AIsolate UI saves users: the whole space or airlock as read, its users replaced. The
   * space's are members with the role user, saved by the admin, jasmine kept as its developer. No
   * If-Match: these tests race no other.
   */
  private HttpResponse<String> saveUsers(String path, List<String> users) {
    boolean space = path.equals(SPACE);
    ObjectNode form = (ObjectNode) JSON.readTree(get(jasmine, path).body());
    if (space) {
      List<Map<String, String>> members = new ArrayList<>();
      users.forEach(email -> members.add(Map.of("email", email, "role", "user")));
      members.add(Map.of("email", "jasmine@example.com", "role", "developer"));
      form.set("members", JSON.valueToTree(members));
    } else {
      form.set("users", JSON.valueToTree(users));
    }
    return Http.send(
        Http.bearer(
                HttpRequest.newBuilder(uri(path))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(form))),
                space ? rita : jasmine)
            .build());
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }
}
