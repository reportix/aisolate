package com.reportix.aisolate.airlock.infrastructure.plugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.reportix.aisolate.airlock.domain.airlock.AirlockDefinition;
import com.reportix.aisolate.airlock.domain.chat.ExchangeTracing;
import com.reportix.aisolate.airlock.infrastructure.config.AirlockProperties;
import com.reportix.aisolate.airlock.infrastructure.config.Llms;
import com.reportix.aisolate.airlock.support.TestAirlocks;
import com.reportix.aisolate.identity.IdentityProperties;
import com.reportix.aisolate.identity.Principal;
import com.reportix.aisolate.sdk.AIsolateManager;
import io.micrometer.observation.ObservationRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.security.oauth2.jwt.Jwt;

class ExchangeManagerFactoryTest {

  private static final AirlockDefinition AIRLOCK =
      TestAirlocks.acmeHr("7", "ollama", List.of(), TestAirlocks.NO_HANDLERS);

  private static final Llms NO_LLMS = new Llms(null, Map.of());

  private static final IdentityProperties IDENTITY =
      new IdentityProperties(null, Principal.DEFAULT_ROLES_CLAIM, null);

  private static Jwt jwt() {
    return Jwt.withTokenValue("t")
        .header("alg", "none")
        .subject("user-1")
        .claim("email", "eric@example.com")
        .claim("azp", "acme-chat")
        .claim("realm_access", Map.of("roles", List.of("employee", "offline_access")))
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60))
        .build();
  }

  /** The pod's settings as Spring binds them from the env the controller renders. */
  private static AirlockProperties fromEnv(Map<String, Object> env) {
    // Spring maps env names (AISOLATE_DATASOURCE_0_ID) only for a source of this name.
    SystemEnvironmentPropertySource source =
        new SystemEnvironmentPropertySource(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env);
    return new Binder(ConfigurationPropertySources.from(source))
        .bindOrCreate("aisolate", AirlockProperties.class);
  }

  private static AIsolateManager manager(Map<String, Object> env) {
    return new ExchangeManagerFactory(
            NO_LLMS, fromEnv(env), IDENTITY, new ExchangeTracing(ObservationRegistry.NOOP))
        .createFor(AIRLOCK, jwt());
  }

  @Test
  void givenJwt_whenCreateFor_thenPrincipalAndAirlockRefComeFromTheClaimsAndTheDefinition() {
    // Arrange
    ExchangeManagerFactory factory =
        new ExchangeManagerFactory(
            NO_LLMS, fromEnv(Map.of()), IDENTITY, new ExchangeTracing(ObservationRegistry.NOOP));

    // Act
    AIsolateManager manager = factory.createFor(AIRLOCK, jwt());

    // Assert
    assertThat(manager.principal().sub()).isEqualTo("user-1");
    assertThat(manager.principal().email()).isEqualTo("eric@example.com");
    assertThat(manager.principal().roles()).containsExactlyInAnyOrder("employee", "offline_access");
    assertThat(manager.airlock().space()).isEqualTo("hr-acme");
    assertThat(manager.airlock().airlock()).isEqualTo("acme-hr");
    assertThat(manager.airlock().revision()).isEqualTo("7");
    assertThat(manager.log().getName()).isEqualTo("aisolate.handlers");
  }

  @Test
  void givenDataSourceEnv_whenDataSource_thenAPostgresDataSourceOnItsUrl() {
    // Arrange: the airlock's one data source, as the controller renders it for both pods
    String url = "jdbc:postgresql://acme-db.aisolate.svc:5432/acme_hr";
    AIsolateManager manager =
        manager(
            Map.of(
                "AISOLATE_DATASOURCE_0_ID", "hr-documents",
                "AISOLATE_DATASOURCE_0_URL", url,
                "AISOLATE_DATASOURCE_0_USERNAME", "aisolate_context_preparator",
                "AISOLATE_DATASOURCE_0_PASSWORD", "secret"));

    // Act
    DataSource dataSource = manager.dataSource("hr-documents");

    // Assert: no connection yet; the driver dials only when a handler asks for one
    assertThat(dataSource)
        .isInstanceOfSatisfying(
            PGSimpleDataSource.class,
            postgres -> {
              assertThat(postgres.getURL()).startsWith(url);
              assertThat(postgres.getUser()).isEqualTo("aisolate_context_preparator");
              assertThat(postgres.getConnectTimeout()).isEqualTo(5);
              assertThat(postgres.getSocketTimeout()).isEqualTo(10);
            });
  }

  @Test
  void givenUnknownId_whenDataSource_thenIllegalArgumentNamingTheAirlock() {
    // Arrange
    AIsolateManager manager =
        manager(
            Map.of(
                "AISOLATE_DATASOURCE_0_ID", "hr-documents",
                "AISOLATE_DATASOURCE_0_URL", "jdbc:postgresql://acme-db:5432/acme_hr"));

    // Act
    Throwable thrown = catchThrowable(() -> manager.dataSource("payroll"));

    // Assert
    assertThat(thrown)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("hr-acme/acme-hr has no data source payroll");
  }
}
