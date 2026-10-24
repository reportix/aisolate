package com.reportix.aisolate.identity.testing;

import java.nio.file.Path;
import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * The test Keycloak every app's integration tests share: the image and the two realm files of
 * docker/compose (aisolate: operators; acme: application users), started once per test JVM.
 */
public final class Keycloak {

  /** The repository's docker/ directory, which Gradle passes to every test task. */
  public static final Path DOCKER =
      Path.of(System.getProperty("aisolate.docker.dir", "../../docker")).toAbsolutePath();

  public static final GenericContainer<?> KEYCLOAK =
      new GenericContainer<>("quay.io/keycloak/keycloak:26.7.3")
          .withCommand("start-dev", "--import-realm")
          .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
          .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
          .withEnv("KC_HEALTH_ENABLED", "true")
          .withCopyFileToContainer(
              MountableFile.forHostPath(DOCKER.resolve("keycloak/realm-aisolate.json")),
              "/opt/keycloak/data/import/realm-aisolate.json")
          .withCopyFileToContainer(
              MountableFile.forHostPath(DOCKER.resolve("keycloak/realm-acme.json")),
              "/opt/keycloak/data/import/realm-acme.json")
          .withExposedPorts(8080, 9000)
          .waitingFor(
              Wait.forHttp("/health/ready")
                  .forPort(9000)
                  .withStartupTimeout(Duration.ofMinutes(3)));

  static {
    KEYCLOAK.start();
  }

  /** The organisation's realm: the application users who ask airlocks. */
  public static String usersIssuerUri() {
    return realm("acme");
  }

  /** AIsolate's own realm: the operators of the dashboard and the CLI. */
  public static String operatorsIssuerUri() {
    return realm("aisolate");
  }

  private static String realm(String name) {
    return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080) + "/realms/" + name;
  }

  private Keycloak() {}
}
