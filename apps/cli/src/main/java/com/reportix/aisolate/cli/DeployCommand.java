package com.reportix.aisolate.cli;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import tools.jackson.databind.JsonNode;

/**
 * {@code aisolate deploy}: checks the session first (refreshing it silently, never prompting),
 * builds the project (a jar with its wrapper, a Python project's zip with its SDK), and sends the
 * code with its language, {@code aisolate.yaml} as written and the variables it reads in one call,
 * into the space {@code AISOLATE_SPACE} names.
 */
@Command(
    name = "deploy",
    mixinStandardHelpOptions = true,
    versionProvider = AIsolateCli.Version.class,
    description = "Build the project and deploy it as its airlock: the code and the settings.")
@RequiredArgsConstructor
final class DeployCommand implements Callable<Integer> {

  private final Console console;
  private final HttpClient http;
  private final Path home;

  /** Null without a terminal: then a missing login is an error line, not a sign-in. */
  private final BrowserLogin.SignIn signIn;

  /** The shell's variables, replaceable so a test can set one. */
  Map<String, String> shell = System.getenv();

  @Mixin ProjectCommand project;

  /**
   * PATH in any case: Windows spells it Path, and there the map of {@code getenv()} is
   * case-sensitive.
   */
  static String path(Map<String, String> shell) {
    return shell.entrySet().stream()
        .filter(variable -> variable.getKey().equalsIgnoreCase("PATH"))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElse(null);
  }

  @Override
  public Integer call() {
    return ProjectCommand.run(
        console,
        () -> {
          ProjectFile config = ProjectFile.read(project.dir, shell);
          // Before the sign-in and the build: an unset variable must cost neither.
          Map<String, String> variables =
              resolveVariables(config.documents(), config.envFile(), shell);
          Path sessions = home.resolve(Session.FILE);
          Auth auth = new Auth(http, config.endpoint());
          // Before the build: a missing login must not cost a build first.
          Session session = requireOrSignIn(auth, sessions);
          Path jar = BuildCommand.build(console, project.dir, path(shell));
          // Again after it: a build can outlast the access token; a refresh keeps the session.
          session = auth.requireSession(sessions, Instant.now().getEpochSecond());
          console.info("Deploying to space " + config.space());
          HttpResponse<String> response =
              new DeployClient(http)
                  .deploy(
                      config.endpoint(),
                      config.space(),
                      session.accessToken(),
                      jar,
                      Language.of(project.dir),
                      config.documents(),
                      variables);
          return report(response, config, sessions);
        });
  }

  // In a terminal no session is a sign-in, not an error: the deploy goes on after it.
  private Session requireOrSignIn(Auth auth, Path sessions) throws IOException {
    try {
      Session session = auth.requireSession(sessions, Instant.now().getEpochSecond());
      console.info(session.signedIn());
      return session;
    } catch (IllegalStateException notLoggedIn) {
      if (signIn == null || !Auth.NOT_LOGGED_IN.equals(notLoggedIn.getMessage())) {
        throw notLoggedIn;
      }
      console.error("You are not logged in to " + auth.getEndpoint());
      Session session = signIn.signIn(auth);
      session.save(sessions);
      console.success(session.signedIn());
      return session;
    }
  }

  private int report(HttpResponse<String> response, ProjectFile config, Path sessions)
      throws IOException {
    JsonNode body = Auth.parse(response.body());
    switch (response.statusCode()) {
      case 200 -> {
        JsonNode airlock = body.path("airlock");
        if (airlock.path("endpoint").asString("").isEmpty()) {
          console.error("AIsolate answered HTTP 200 without a deploy result");
          return 1;
        }
        // Deployed anyway, worth knowing: a tool of the project that tools leaves out, for one.
        body.path("warnings").forEach(warning -> console.warn(warning.asString("")));
        console.success(
            "airlock "
                + config.space()
                + "/"
                + airlock.path("id").asString("")
                + (airlock.path("created").asBoolean(false) ? " (created" : " (updated")
                + ", revision "
                + airlock.path("revision").asString("")
                + ")");
        console.println("   POST " + airlock.path("endpoint").asString(""));
        JsonNode code = body.path("jar");
        console.println(
            "   code "
                + code.path("digest").asString("")
                + "; tools: "
                + namesOf(code.path("tools"))
                + "; handlers: "
                + namesOf(code.path("handlers")));
        // Present only when AIsolate runs in a cluster: whether the pods follow this deploy.
        JsonNode cluster = body.path("cluster");
        if (!cluster.isMissingNode() && !cluster.isNull()) {
          console.println(
              cluster.path("applied").asBoolean(false)
                  ? "   cluster: applied, the status of the Airlock shows its pods"
                  : "   cluster: not applied: "
                      + cluster.path("problem").asString("unknown problem"));
        }
        return 0;
      }
      case 400, 409 -> {
        if (body.path("errors").isEmpty()) {
          console.error(
              body.path("detail").asString("AIsolate answered HTTP " + response.statusCode()));
        }
        body.path("errors").forEach(error -> console.error(error.asString("")));
      }
      case 401 -> {
        Session.remove(sessions, config.endpoint());
        console.error(Auth.NOT_LOGGED_IN);
      }
      // The backend names the space and whom to ask; a proxy's bare 403 does not.
      case 403 ->
          console.error(
              body.path("detail")
                  .asString("You are not a developer of space " + config.space() + " in AIsolate"));
      case 404 -> {
        // A missing airlock's 404 says what to do; the space's is generic, so the space is named.
        String detail = body.path("detail").asString("");
        console.error(
            detail.isEmpty() || detail.equals("unknown space")
                ? "space " + config.space() + " not found"
                : detail);
      }
      default -> console.error("AIsolate answered HTTP " + response.statusCode());
    }
    return 1;
  }

  private static String namesOf(JsonNode names) {
    List<String> all = new ArrayList<>();
    names.forEach(name -> all.add(name.asString("")));
    return all.isEmpty() ? "none" : String.join(", ", all);
  }

  /**
   * The value of each variable the documents read, the shell's before {@code .env}'s. One that is
   * unset or empty and has no fallback is refused by name; a value never reaches a message.
   */
  static Map<String, String> resolveVariables(
      String documents, Map<String, String> envFile, Map<String, String> shell) {
    Map<String, String> values = new TreeMap<>();
    Set<String> unset = new TreeSet<>();
    // A comment line is no value: the backend never reads it.
    for (String line : documents.lines().toList()) {
      if (line.strip().startsWith("#")) {
        continue;
      }
      Matcher variable = ProjectFile.VARIABLE.matcher(line);
      while (variable.find()) {
        String name = variable.group(1);
        String value = shell.getOrDefault(name, "");
        value = value.isEmpty() ? envFile.getOrDefault(name, "") : value;
        if (!value.isEmpty()) {
          values.put(name, value);
        } else if (variable.group(2) == null) {
          unset.add(name);
        }
      }
    }
    if (!unset.isEmpty()) {
      throw new IllegalStateException(
          ProjectFile.FILE
              + " reads "
              + String.join(", ", unset)
              + (unset.size() == 1 ? ", which is" : ", which are")
              + " not set in .env or the shell");
    }
    return values;
  }
}
