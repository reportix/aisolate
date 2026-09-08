package com.reportix.aisolate.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class LoginCommandTest {

  @TempDir Path tmp;

  private final FakeConsole console = new FakeConsole();
  private IdentityStub stub;
  private Path project;
  private Path home;
  private Path sessionFile;

  @BeforeEach
  void start() throws IOException {
    stub = new IdentityStub();
    project = TestProject.create(tmp.resolve("hr-lab"), "hr-lab", stub.url);
    home = tmp.resolve("home");
    sessionFile = home.resolve(Session.FILE);
  }

  @AfterEach
  void stop() {
    stub.close();
  }

  @Test
  void givenBrowserSignsIn_whenLogin_thenSessionSavedAndSignedInLine() {
    // Arrange
    FakeBrowser browser = new FakeBrowser("code=" + IdentityStub.CODE + "&state={state}");

    // Act
    int exit = login(browser);

    // Assert
    assertThat(exit).isZero();
    assertThat(console.lines.getLast()).isEqualTo("✔ Signed in as jasmine");
    assertThat(console.errors).isEmpty();
    assertThat(Session.load(sessionFile, stub.url))
        .get()
        .extracting(Session::user, Session::refreshToken)
        .containsExactly("jasmine", "rt");
  }

  @Test
  void givenProviderDenies_whenLogin_thenOneErrorLineAndNoSession() {
    // Arrange
    FakeBrowser browser = new FakeBrowser("error=access_denied&state={state}");

    // Act
    int exit = login(browser);

    // Assert
    assertThat(exit).isEqualTo(1);
    assertThat(console.errors).containsExactly("Login failed: access_denied");
    assertThat(sessionFile).doesNotExist();
  }

  @Test
  void givenUnreachableEndpoint_whenLogin_thenOneLineAndNoBrowser() {
    // Arrange
    stub.close();
    AtomicBoolean opened = new AtomicBoolean();

    // Act
    int exit = login(url -> opened.set(true));

    // Assert
    assertThat(exit).isEqualTo(1);
    assertThat(console.errors).singleElement().asString().startsWith("cannot reach 127.0.0.1:");
    assertThat(opened).isFalse();
  }

  private int login(Consumer<String> browser) {
    return new CommandLine(
            new LoginCommand(
                console,
                HttpClient.newHttpClient(),
                home,
                browser,
                new ByteArrayInputStream("\n".getBytes(StandardCharsets.UTF_8)),
                Duration.ofSeconds(10)))
        .execute("-p=" + project);
  }
}
