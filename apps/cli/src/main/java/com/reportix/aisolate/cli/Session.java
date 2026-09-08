package com.reportix.aisolate.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The tokens of one sign-in to one endpoint. All of them live in {@code ~/.aisolate/session.json}
 * (owner-only), keyed by endpoint, so the compose and the cluster AIsolate stay signed in side by
 * side. Never holds a password.
 *
 * @param issuer the provider that issued the tokens: the only one a refresh token goes back to
 */
record Session(
    String endpoint,
    String issuer,
    String user,
    String accessToken,
    String refreshToken,
    long expiresAt) {

  static final String FILE = "session.json";

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final FileAttribute<?> OWNER_ONLY =
      PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));

  /** {@code AISOLATE_HOME}, else {@code ~/.aisolate}: where the session file lives. */
  static Path home() {
    String env = System.getenv("AISOLATE_HOME");
    return env != null && !env.isBlank()
        ? Path.of(env)
        : Path.of(System.getProperty("user.home"), ".aisolate");
  }

  static Optional<Session> load(Path file, String endpoint) {
    JsonNode json = sessions(file).path(endpoint);
    if (!json.isObject()) {
      return Optional.empty();
    }
    return Optional.of(
        new Session(
            endpoint,
            json.path("issuer").asString(""),
            json.path("user").asString(""),
            json.path("access_token").asString(""),
            json.path("refresh_token").asString(""),
            json.path("expires_at").asLong(0)));
  }

  /** Adds or replaces this endpoint's session; the other endpoints' stay. */
  void save(Path file) throws IOException {
    ObjectNode sessions = sessions(file);
    sessions
        .putObject(endpoint)
        .put("issuer", issuer)
        .put("user", user)
        .put("access_token", accessToken)
        .put("refresh_token", refreshToken)
        .put("expires_at", expiresAt);
    write(file, sessions);
  }

  static void remove(Path file, String endpoint) throws IOException {
    ObjectNode sessions = sessions(file);
    if (sessions.remove(endpoint) != null) {
      write(file, sessions);
    }
  }

  /** Good for more than 30 seconds, so it does not expire mid-upload. */
  boolean isValidAt(long nowEpochSeconds) {
    return expiresAt - 30 > nowEpochSeconds;
  }

  /** The line after a login, and before a deploy. */
  String signedIn() {
    return user.isEmpty() ? "Signed in" : "Signed in as " + user;
  }

  // Missing, unreadable or without a sessions object: none.
  private static ObjectNode sessions(Path file) {
    try {
      JsonNode root = JSON.readTree(Files.readString(file));
      if (root != null && root.path("sessions") instanceof ObjectNode sessions) {
        return sessions;
      }
    } catch (IOException | JacksonException unreadable) {
      // no sessions
    }
    return JSON.createObjectNode();
  }

  // Written to an owner-only temp file and moved over the old one: never readable by others, even
  // when an earlier file had wider permissions.
  private static void write(Path file, ObjectNode sessions) throws IOException {
    Path dir = Files.createDirectories(file.toAbsolutePath().getParent());
    boolean posix = dir.getFileSystem().supportedFileAttributeViews().contains("posix");
    Path temp =
        posix
            ? Files.createTempFile(dir, "session", ".tmp", OWNER_ONLY)
            : Files.createTempFile(dir, "session", ".tmp");
    try {
      ObjectNode root = JSON.createObjectNode();
      root.set("sessions", sessions);
      Files.writeString(temp, JSON.writeValueAsString(root));
      Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(temp);
    }
  }
}
