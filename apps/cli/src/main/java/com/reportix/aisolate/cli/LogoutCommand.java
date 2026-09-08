package com.reportix.aisolate.cli;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

/** {@code aisolate logout}: the provider forgets the session when it can, the CLI always does. */
@Command(
    name = "logout",
    mixinStandardHelpOptions = true,
    versionProvider = AIsolateCli.Version.class,
    description = "Sign out of the project's AIsolate.")
@RequiredArgsConstructor
final class LogoutCommand implements Callable<Integer> {

  private final Console console;
  private final HttpClient http;
  private final Path home;

  @Mixin ProjectCommand project;

  @Override
  public Integer call() {
    return ProjectCommand.run(
        console,
        () -> {
          ProjectFile config = ProjectFile.read(project.dir);
          Path sessions = home.resolve(Session.FILE);
          Optional<Session> session = Session.load(sessions, config.endpoint());
          if (session.isEmpty()) {
            console.info("Not logged in to " + config.endpoint());
            return 0;
          }
          try {
            new Auth(http, config.endpoint()).revoke(session.get());
          } catch (IllegalStateException unreachable) {
            // Forgotten here regardless: unused, the refresh token expires on its own (90 days).
            console.warn("the identity provider was not told: " + unreachable.getMessage());
          }
          Session.remove(sessions, config.endpoint());
          console.success("Logged out of " + config.endpoint());
          return 0;
        });
  }
}
