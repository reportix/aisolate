package com.reportix.aisolate.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class SessionTest {

  private static final boolean POSIX =
      FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
  private static final String COMPOSE = "http://localhost:8080";
  private static final String ISSUER = "http://localhost:8180/realms/acme";

  @TempDir Path home;

  @Test
  void givenSession_whenSavedAndLoaded_thenEqualAndFileIsOwnerOnly() throws Exception {
    // Arrange
    Path file = home.resolve(Session.FILE);
    Files.writeString(file, "{}");
    if (POSIX) {
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
    }
    Session session = new Session(COMPOSE, ISSUER, "jasmine", "at", "rt", 1300);

    // Act
    session.save(file);
    Optional<Session> loaded = Session.load(file, COMPOSE);

    // Assert
    assertThat(loaded).contains(session);
    if (POSIX) {
      assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
          .isEqualTo("rw-------");
    }
  }

  @Test
  void givenNoSession_whenRemove_thenNoFileWritten() throws Exception {
    // Arrange
    Path file = home.resolve(Session.FILE);

    // Act
    Session.remove(file, COMPOSE);

    // Assert
    assertThat(file).doesNotExist();
  }

  @ParameterizedTest
  @CsvSource({"1031, true", "1030, false"})
  void givenSession_whenIsValidAt_thenMoreThan30SecondsLeft(long expiresAt, boolean valid) {
    // Arrange
    Session session = new Session(COMPOSE, ISSUER, "jasmine", "at", "rt", expiresAt);

    // Act
    boolean result = session.isValidAt(1000);

    // Assert
    assertThat(result).isEqualTo(valid);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = "{\"sessions\":")
  void givenNoFileOrUnreadableJson_whenLoad_thenEmpty(String content) throws Exception {
    // Arrange
    Path file = home.resolve(Session.FILE);
    if (content != null) {
      Files.writeString(file, content);
    }

    // Act
    Optional<Session> loaded = Session.load(file, COMPOSE);

    // Assert
    assertThat(loaded).isEmpty();
  }

  @Test
  void givenUnknownUser_whenSignedIn_thenNoName() {
    // Arrange: a known one is in every sign-in line of the command tests
    Session session = new Session(COMPOSE, ISSUER, "", "at", "rt", 1300);

    // Act
    String result = session.signedIn();

    // Assert
    assertThat(result).isEqualTo("Signed in");
  }
}
