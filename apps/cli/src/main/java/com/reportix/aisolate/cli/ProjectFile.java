package com.reportix.aisolate.cli;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The project as the CLI reads it: {@code aisolate.yaml}, the one Airlock it deploys, and {@code
 * .env}. The space is {@code AISOLATE_SPACE} from the shell, else from {@code .env}; the endpoint
 * is {@code AISOLATE_ENDPOINT} from the shell, else from {@code .env}, else {@code
 * http://localhost:8080}; plain http only to this machine.
 *
 * @param documents the file's text as written: what {@code deploy} sends
 * @param envFile the {@code .env} values, empty without the file
 */
record ProjectFile(String endpoint, String space, String documents, Map<String, String> envFile) {

  static final String FILE = "aisolate.yaml";
  static final String ENV_FILE = ".env";
  static final String ENDPOINT_VARIABLE = "AISOLATE_ENDPOINT";
  static final String SPACE_VARIABLE = "AISOLATE_SPACE";
  static final String DEFAULT_ENDPOINT = "http://localhost:8080";

  /**
   * The server's space and airlock id rule ({@code Names.SLUG}), copied: the CLI binary carries no
   * server library. It also keeps the space safe to put in the deploy's URL path.
   */
  static final Pattern SLUG = Pattern.compile("^[a-z]([a-z0-9-]{0,39}[a-z0-9])$");

  static final String SLUG_RULE =
      "2 to 41 lowercase letters, digits or hyphens, starting with a letter and not ending with a"
          + " hyphen";

  /** {@code ${NAME}}, or {@code ${NAME:-fallback}} when NAME may stay unset. */
  static final Pattern VARIABLE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?}");

  static ProjectFile read(Path projectDir) throws IOException {
    return read(projectDir, System.getenv());
  }

  static ProjectFile read(Path projectDir, Map<String, String> shell) throws IOException {
    Path dir = projectDir.toAbsolutePath().normalize();
    Path file = dir.resolve(FILE);
    if (!Files.isRegularFile(file)) {
      throw new IllegalStateException(
          "no " + FILE + " in " + dir + ": run this in a project created by aisolate create");
    }
    Map<String, String> envFile = readEnvFile(dir.resolve(ENV_FILE));
    String space = valueOf(SPACE_VARIABLE, shell, envFile);
    if (space.isEmpty()) {
      throw new IllegalStateException(
          SPACE_VARIABLE + " is not set: add " + SPACE_VARIABLE + "=<space> to " + ENV_FILE);
    }
    if (!SLUG.matcher(space).matches()) {
      throw new IllegalStateException(SPACE_VARIABLE + " must be " + SLUG_RULE);
    }
    return new ProjectFile(endpointOf(shell, envFile), space, Files.readString(file), envFile);
  }

  /**
   * {@code AISOLATE_ENDPOINT} from the shell, else from {@code .env}, else {@code
   * http://localhost:8080}, without a trailing slash; plain http only to this machine.
   */
  static String endpointOf(Map<String, String> shell, Map<String, String> envFile) {
    String endpoint = valueOf(ENDPOINT_VARIABLE, shell, envFile);
    endpoint = (endpoint.isEmpty() ? DEFAULT_ENDPOINT : endpoint).replaceAll("/+$", "");
    refusePlainHttp(endpoint);
    return endpoint;
  }

  // The deploy carries the bearer token and the data sources' passwords, and discovery over http
  // would let the network name the provider that gets the refresh token.
  private static void refusePlainHttp(String endpoint) {
    URI uri = Auth.parseUri(endpoint, ENDPOINT_VARIABLE);
    if ("http".equalsIgnoreCase(uri.getScheme()) && !Auth.isLoopback(uri.getHost())) {
      throw new IllegalStateException(
          "refusing "
              + ENDPOINT_VARIABLE
              + "="
              + endpoint
              + ": tokens and passwords would travel unencrypted; use https (plain http is for"
              + " localhost only)");
    }
  }

  /** The shell's value, else the file's when the shell's is unset or empty; empty when neither. */
  private static String valueOf(
      String name, Map<String, String> shell, Map<String, String> envFile) {
    String value = shell.getOrDefault(name, "");
    return value.isEmpty() ? envFile.getOrDefault(name, "") : value;
  }

  /** {@code KEY=VALUE} lines; # comments, quotes around a value and an export prefix are fine. */
  static Map<String, String> readEnvFile(Path file) throws IOException {
    Map<String, String> values = new HashMap<>();
    if (!Files.isRegularFile(file)) {
      return values;
    }
    for (String line : Files.readAllLines(file)) {
      String entry = line.strip().replaceFirst("^export\\s+", "");
      int equals = entry.indexOf('=');
      if (equals > 0 && !entry.startsWith("#")) {
        // No inline comments: a # in a password is part of it.
        values.put(
            entry.substring(0, equals).strip(), unquote(entry.substring(equals + 1).strip()));
      }
    }
    return values;
  }

  private static String unquote(String value) {
    boolean quoted =
        value.length() >= 2
            && (value.startsWith("\"") && value.endsWith("\"")
                || value.startsWith("'") && value.endsWith("'"));
    return quoted ? value.substring(1, value.length() - 1) : value;
  }
}
