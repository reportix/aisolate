package com.reportix.aisolate.backend.support;

import com.reportix.aisolate.identity.testing.Keycloak;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/** Points the backend at the shared containers; the model and tool server are stubbed per test. */
public final class BackendTestConfig
    implements ApplicationContextInitializer<ConfigurableApplicationContext> {

  /** The install's audit ingest key, as the chart would share it with the controller. */
  public static final String INGEST_KEY = "test-ingest-key";

  /** The key stored credentials are sealed with in the tests. */
  public static final String CREDENTIALS_KEY = "test-credentials-key-of-at-least-32-chars";

  @Override
  public void initialize(ConfigurableApplicationContext context) {
    TestPropertyValues.of(
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + Keycloak.issuerUri(),
            "spring.datasource.url=" + Containers.aisolate().getJdbcUrl(),
            "spring.datasource.username=aisolate",
            "spring.datasource.password=aisolate",
            // Every cached context runs its own ingest port: a fixed one would collide.
            "aisolate.audit.ingest-port=0",
            "aisolate.audit.ingest-key=" + INGEST_KEY,
            "aisolate.credentials.key=" + CREDENTIALS_KEY,
            // Test-only seed: the demo space and airlock the tests drive.
            "spring.flyway.locations=classpath:db/migration,classpath:db/migration-test")
        .applyTo(context);
  }
}
