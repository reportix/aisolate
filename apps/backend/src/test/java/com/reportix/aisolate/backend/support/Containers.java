package com.reportix.aisolate.backend.support;

import org.testcontainers.containers.PostgreSQLContainer;

/** AIsolate's own database, migrated by the application under test; Keycloak is shared. */
public final class Containers {

  private static PostgreSQLContainer aisolate;

  /** AIsolate's own control plane; the backend runs its migrations against it. */
  public static synchronized PostgreSQLContainer aisolate() {
    if (aisolate == null) {
      aisolate =
          new PostgreSQLContainer("postgres:18")
              .withDatabaseName("aisolate")
              .withUsername("aisolate")
              .withPassword("aisolate");
      aisolate.start();
    }
    return aisolate;
  }

  private Containers() {}
}
