package com.reportix.aisolate.cli;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

/**
 * {@code aisolate login}: the browser signs the developer in to the project's endpoint; always a
 * fresh login, which is how another user takes over.
 */
@Command(
    name = "login",
    mixinStandardHelpOptions = true,
    versionProvider = AIsolateCli.Version.class,
    description = "Sign in to the project's AIsolate in your browser.")
@RequiredArgsConstructor
final class LoginCommand implements Callable<Integer> {

  private final Console console;
  private final HttpClient http;
  private final Path home;
  private final Consumer<String> browser;
  private final InputStream keys;
  private final Duration timeout;

  @Mixin ProjectCommand project;

  @Override
  public Integer call() {
    return ProjectCommand.run(
        console,
        () -> {
          ProjectFile config = ProjectFile.read(project.dir);
          Session session =
              new BrowserLogin(new Auth(http, config.endpoint()), console, browser, keys, timeout)
                  .login();
          session.save(home.resolve(Session.FILE));
          console.success(session.signedIn());
          return 0;
        });
  }
}
