package com.reportix.aisolate.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.within;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BrowserLoginTest {

  private static final String SIGNED_IN = "code=" + IdentityStub.CODE + "&state={state}";

  private final FakeConsole console = new FakeConsole();
  private IdentityStub stub;

  @BeforeEach
  void start() throws Exception {
    stub = new IdentityStub();
  }

  @AfterEach
  void stop() {
    stub.close();
  }

  @Test
  void givenBrowserSignsIn_whenLogin_thenCodeGrantWithTheVerifierOfTheChallenge() throws Exception {
    // Arrange
    FakeBrowser browser = new FakeBrowser(SIGNED_IN);

    // Act
    Session session = login(browser, Duration.ofSeconds(10)).login();

    // Assert
    assertThat(session)
        .extracting(
            Session::endpoint,
            Session::issuer,
            Session::user,
            Session::accessToken,
            Session::refreshToken)
        .containsExactly(stub.url, stub.issuer, "jasmine", IdentityStub.jwt("jasmine"), "rt");
    assertThat(session.expiresAt()).isCloseTo(Instant.now().getEpochSecond() + 300, within(5L));
    assertThat(browser.authorize)
        .containsEntry("client_id", "aisolate-cli")
        .containsEntry("response_type", "code")
        .containsEntry("scope", "openid profile email")
        .containsEntry("code_challenge_method", "S256");
    assertThat(browser.authorize.get("redirect_uri"))
        .matches("http://127\\.0\\.0\\.1:\\d+/callback");
    Map<String, String> grant = stub.forms.getFirst();
    assertThat(grant)
        .containsEntry("grant_type", "authorization_code")
        .containsEntry("redirect_uri", browser.authorize.get("redirect_uri"));
    assertThat(BrowserLogin.computeChallenge(grant.get("code_verifier")))
        .isEqualTo(browser.authorize.get("code_challenge"));
    assertThat(browser.page()).contains("Signed in to AIsolate");
    assertThat(console.lines)
        .containsExactly(
            "",
            "⏺ Opening your browser to sign in",
            "   Browser didn't open? Use this link:",
            "   " + findPrintedUrl(),
            "   Ctrl+C to cancel");
    assertThat(findPrintedUrl()).startsWith(stub.authorizationUrl + "?client_id=aisolate-cli&");
  }

  @Test
  void givenOpenerShowsNothing_whenThePrintedLinkIsVisited_thenSignedIn() throws Exception {
    // Arrange: the opener returns as if a browser came up; the developer pastes the printed link
    FakeBrowser visitor = new FakeBrowser(SIGNED_IN);
    Thread.ofVirtual().start(() -> visitor.accept(awaitLine(this::findPrintedUrl)));

    // Act
    Session session = login(url -> {}, Duration.ofSeconds(10)).login();

    // Assert
    assertThat(session.user()).isEqualTo("jasmine");
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          error=access_denied&error_description=User%20cancelled&state={state} | Login failed: access_denied (User cancelled)
          code=the-code&state=someone-else                                    | Login failed: the browser answered for another login attempt
          state={state}                                                        | Login failed: the provider sent no code
          error=access_denied&error_description=%1B%5B2J&state=someone-else    | Login failed: the browser answered for another login attempt
          """)
  void givenCallbackWithoutAUsableCode_whenLogin_thenOneLineAndNoTokenRequest(
      String answer, String line) {
    // Arrange
    FakeBrowser browser = new FakeBrowser(answer);

    // Act
    Throwable thrown = catchThrowable(() -> login(browser, Duration.ofSeconds(10)).login());

    // Assert
    assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessage(line);
    assertThat(stub.forms).isEmpty();
    assertThat(browser.page()).contains("Sign-in failed");
  }

  @Test
  void givenCodeTheProviderRejects_whenLogin_thenThePageSaysSignInFailed() {
    // Arrange
    FakeBrowser browser = new FakeBrowser("code=stale&state={state}");

    // Act
    Throwable thrown = catchThrowable(() -> login(browser, Duration.ofSeconds(10)).login());

    // Assert
    assertThat(thrown)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Login failed: invalid_grant");
    assertThat(browser.page()).contains("Sign-in failed").contains("invalid_grant");
  }

  @Test
  void givenNoCallback_whenLogin_thenTimedOut() {
    // Arrange
    Consumer<String> browser = url -> {};

    // Act
    Throwable thrown = catchThrowable(() -> login(browser, Duration.ofMillis(300)).login());

    // Assert
    assertThat(thrown)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("Login timed out after");
    assertThat(stub.forms).isEmpty();
  }

  @Test
  void givenBrowserCannotOpen_whenTheUrlIsVisitedAnyway_thenWarnedAndSignedIn() throws Exception {
    // Arrange: the opener fails; the developer pastes the link printed above the warning
    FakeBrowser visitor = new FakeBrowser(SIGNED_IN);
    Consumer<String> browser =
        url -> {
          throw new IllegalStateException("cannot run xdg-open: not found");
        };
    Thread.ofVirtual().start(() -> visitor.accept(awaitLine(this::findPrintedUrl)));

    // Act
    Session session = login(browser, Duration.ofSeconds(10)).login();

    // Assert
    assertThat(session.user()).isEqualTo("jasmine");
    assertThat(console.lines)
        .contains(
            "! the browser did not open (cannot run xdg-open: not found); use the link above");
  }

  @Test
  void givenFaviconRequestFirst_whenLogin_thenNotFoundAndTheCallbackStillCounts() throws Exception {
    // Arrange
    FakeBrowser visitor = new FakeBrowser(SIGNED_IN);
    AtomicInteger favicon = new AtomicInteger();
    Consumer<String> browser =
        url -> {
          URI redirect =
              URI.create(FakeBrowser.query(URI.create(url).getRawQuery()).get("redirect_uri"));
          favicon.set(visitor.get(redirect.resolve("/favicon.ico").toString()).statusCode());
          visitor.accept(url);
        };

    // Act
    Session session = login(browser, Duration.ofSeconds(10)).login();

    // Assert
    assertThat(favicon.get()).isEqualTo(404);
    assertThat(session.user()).isEqualTo("jasmine");
  }

  @Test
  void givenProviderTextWithMarkup_whenPage_thenEscaped() {
    // Arrange
    String failure = "Login failed: <script>alert(1)</script>";

    // Act
    String page = BrowserLogin.renderPage(failure);

    // Assert
    assertThat(page).contains("&lt;script&gt;alert(1)&lt;/script&gt;").doesNotContain("<script>");
  }

  private BrowserLogin login(Consumer<String> browser, Duration timeout) {
    return new BrowserLogin(
        new Auth(HttpClient.newHttpClient(), stub.url), console, browser, timeout);
  }

  private String findPrintedUrl() {
    return console.lines.stream()
        .filter(line -> line.startsWith("   http"))
        .map(String::strip)
        .findFirst()
        .orElse(null);
  }

  private static String awaitLine(Supplier<String> line) {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < deadline) {
      String value = line.get();
      if (value != null) {
        return value;
      }
      Thread.onSpinWait();
    }
    throw new IllegalStateException("nothing to click within 5 s");
  }
}
