package com.reportix.aisolate.backend.infrastructure.directory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.reportix.aisolate.backend.domain.directory.Directory;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.json.JsonMapper;

/** Keycloak's token endpoint and users API stubbed: what the directory asks and what it keeps. */
@ExtendWith(OutputCaptureExtension.class)
class KeycloakDirectoryTest {

  private static final String SECRET = "s3cret-never-shown";
  private static final String TOKEN_PATH = "/realms/acme/protocol/openid-connect/token";
  private static final String USERS_PATH = "/admin/realms/acme/users";

  private HttpServer keycloak;
  private final List<String> calls = new CopyOnWriteArrayList<>();
  private volatile int tokenStatus = 200;
  private volatile int usersStatus = 200;
  private volatile String users = "[]";
  private volatile long usersDelayMillis = 0;

  @BeforeEach
  void startKeycloak() throws IOException {
    keycloak = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    // Off the dispatcher thread: a slow answer must not hold stop() or the next request.
    keycloak.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    keycloak.createContext(
        TOKEN_PATH,
        exchange -> {
          String form =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          calls.add("token " + URLDecoder.decode(form, StandardCharsets.UTF_8));
          reply(
              exchange,
              tokenStatus,
              tokenStatus == 200
                  ? "{\"access_token\":\"t-" + calls.size() + "\",\"expires_in\":300}"
                  : "{\"error\":\"unauthorized_client\"}");
        });
    keycloak.createContext(
        USERS_PATH,
        exchange -> {
          calls.add(
              "users "
                  + exchange.getRequestURI().getRawQuery()
                  + " "
                  + exchange.getRequestHeaders().getFirst("Authorization"));
          sleep(usersDelayMillis);
          reply(exchange, usersStatus, usersStatus == 200 ? users : "{\"error\":\"HTTP 401\"}");
        });
    keycloak.start();
  }

  @AfterEach
  void stopKeycloak() {
    keycloak.stop(0);
  }

  @Test
  void givenPeopleInKeycloak_whenSearchedTwice_thenAtMostTwentyNamedPeopleAndOneToken() {
    // Arrange: 25 matches, one disabled and one without an e-mail
    String many =
        IntStream.range(0, 25)
            .mapToObj(
                i ->
                    "{\"id\":\"u"
                        + i
                        + "\",\"username\":\"elena"
                        + i
                        + "\",\"firstName\":\"Elena\",\"lastName\":\"Varga "
                        + i
                        + "\",\"email\":\"elena"
                        + i
                        + "@acme.example\",\"enabled\":true,\"emailVerified\":true}")
            .collect(Collectors.joining(","));
    users =
        "[{\"username\":\"gone\",\"email\":\"gone@acme.example\",\"enabled\":false},"
            + "{\"username\":\"service-account-x\",\"enabled\":true},"
            + many
            + "]";
    KeycloakDirectory directory = directory(Duration.ofSeconds(3));

    // Act
    List<Directory.Person> found = directory.search("Ele va");
    directory.search("Ele va");

    // Assert
    assertThat(found).hasSize(Directory.MAX_RESULTS);
    assertThat(found.getFirst())
        .isEqualTo(new Directory.Person("Elena Varga 0", "elena0", "elena0@acme.example"));
    assertThat(calls).hasSize(3);
    assertThat(calls.getFirst())
        .isEqualTo(
            "token grant_type=client_credentials&client_id=aisolate-directory&client_secret="
                + SECRET);
    assertThat(calls.get(1))
        .isEqualTo("users briefRepresentation=true&search=Ele%20va&max=20 Bearer t-1")
        .isEqualTo(calls.get(2));
  }

  @Test
  void givenEmail_whenLookedUp_thenExactQueryAndTheOneWhoSignsInWithIt() {
    // Arrange: Keycloak keeps e-mails lowercase; the name falls back to the name attribute
    users = "[{\"username\":\"elena\",\"name\":\"Elena V.\",\"email\":\"elena@acme.example\"}]";
    KeycloakDirectory directory = directory(Duration.ofSeconds(3));

    // Act
    var found = directory.findByEmail("Elena@Acme.example");

    // Assert
    assertThat(found).contains(new Directory.Person("Elena V.", "elena", "elena@acme.example"));
    assertThat(calls.get(1))
        .startsWith("users briefRepresentation=true&email=Elena%40Acme.example&exact=true");
  }

  @Test
  void givenNobodyWithTheEmail_whenLookedUp_thenEmpty() {
    // Arrange
    users = "[]";

    // Act
    var found = directory(Duration.ofSeconds(3)).findByEmail("ghost@acme.example");

    // Assert
    assertThat(found).isEmpty();
  }

  @Test
  void givenKeycloakSlowerThanTheTimeout_whenSearched_thenUnavailableWithinTheTimeout() {
    // Arrange
    usersDelayMillis = 3_000;
    KeycloakDirectory directory = directory(Duration.ofMillis(300));
    long start = System.nanoTime();

    // Act / Assert
    assertThatThrownBy(() -> directory.search("ele"))
        .isInstanceOf(Directory.UnavailableException.class)
        .hasMessage("the directory did not answer");
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
  }

  @ParameterizedTest
  @ValueSource(strings = {"token", "users"})
  void givenKeycloakAnswers401_whenSearched_thenPlainUnavailableAndTheSecretNowhere(
      String refusing, CapturedOutput output) {
    // Arrange
    if (refusing.equals("token")) {
      tokenStatus = 401;
    } else {
      usersStatus = 401;
    }

    // Act
    Throwable thrown = catchThrowable(() -> directory(Duration.ofSeconds(3)).search("ele"));

    // Assert: a users 401 is retried once with a fresh token
    assertThat(thrown)
        .isInstanceOf(Directory.UnavailableException.class)
        .hasMessage("the directory did not answer")
        .hasNoCause();
    assertThat(output.getAll()).contains("HTTP 401").doesNotContain(SECRET);
    assertThat(calls.stream().filter(c -> c.startsWith("token")).count())
        .isEqualTo(refusing.equals("token") ? 1 : 2);
  }

  @Test
  void givenProperties_whenPrinted_thenNoSecret() {
    // Arrange
    DirectoryProperties properties = properties(Duration.ofSeconds(3));

    // Act
    String printed = properties.toString();

    // Assert
    assertThat(printed).contains("aisolate-directory").doesNotContain(SECRET);
  }

  private KeycloakDirectory directory(Duration timeout) {
    return new KeycloakDirectory(properties(timeout), JsonMapper.builder().build());
  }

  private DirectoryProperties properties(Duration timeout) {
    return new DirectoryProperties(
        "http://127.0.0.1:" + keycloak.getAddress().getPort() + "/",
        "acme",
        "aisolate-directory",
        SECRET,
        timeout);
  }

  private static void reply(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    try {
      exchange.sendResponseHeaders(status, bytes.length);
      exchange.getResponseBody().write(bytes);
    } catch (IOException gone) {
      // The client timed out and closed the connection.
    }
    exchange.close();
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
