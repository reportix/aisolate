package com.reportix.aisolate.toolserver.support;

import com.reportix.aisolate.config.ConfigurationLoader;
import com.reportix.aisolate.identity.testing.Keycloak;
import java.nio.file.Path;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Points the MCP server at the shared Keycloak, the release's documents with the revision the chart
 * computed, and the airlock's two data sources: the customer database, and one nothing answers.
 */
public final class ToolServerTestConfig
    implements ApplicationContextInitializer<ConfigurableApplicationContext> {

  /** Nothing listens on port 1: every connection is refused at once. */
  public static final String UNREACHABLE_URL = "jdbc:postgresql://localhost:1/hr_archive";

  public static final Path DOCUMENTS = Path.of("src/test/resources/documents").toAbsolutePath();

  /** What the mounted documents hash to; the controller renders it into both pods' env. */
  public static final String REVISION = ConfigurationLoader.load(DOCUMENTS).revision();

  @Override
  public void initialize(ConfigurableApplicationContext context) {
    TestPropertyValues.of(
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + Keycloak.usersIssuerUri(),
            "aisolate.config.dir=" + DOCUMENTS,
            "aisolate.config.revision=" + REVISION,
            "aisolate.datasource[0].id=acme_hr",
            "aisolate.datasource[0].url=" + Containers.acme().getJdbcUrl(),
            "aisolate.datasource[0].username=aisolate_hr",
            "aisolate.datasource[0].password=aisolate_hr",
            "aisolate.datasource[1].id=hr_archive",
            "aisolate.datasource[1].url=" + UNREACHABLE_URL,
            "aisolate.datasource[1].username=aisolate_hr",
            "aisolate.datasource[1].password=aisolate_hr")
        .applyTo(context);
  }
}
