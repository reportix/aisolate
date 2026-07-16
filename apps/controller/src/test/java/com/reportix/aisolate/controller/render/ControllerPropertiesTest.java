package com.reportix.aisolate.controller.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ByteArrayResource;

/** The chart's settings.yaml binds as written: bracketed host keys keep their dots. */
class ControllerPropertiesTest {

  @Test
  void givenSettingsYaml_whenBound_thenHostKeysKeepTheirDotsAndProxyDomainsBind()
      throws IOException {
    // Arrange
    String yaml =
        """
        aisolate:
          controller:
            namespace: aisolate
            egress:
              hosts:
                "[acme-db.aisolate.svc]":
                  podSelector: {app: acme-db}
                  port: 5432
              proxy: {enabled: true, host: egress, port: 3128, domains: [models.example.com]}
            observability:
              otlpEndpoint: http://tempo.aisolate-monitoring.svc:4318
            resources:
              airlock: {"requests":{"cpu":"250m","ephemeral-storage":"256Mi"},"limits":{"cpu":"2"}}
              toolServer: {}
        """;
    StandardEnvironment environment = new StandardEnvironment();
    new YamlPropertySourceLoader()
        .load("settings.yaml", new ByteArrayResource(yaml.getBytes()))
        .forEach(source -> environment.getPropertySources().addFirst(source));

    // Act
    ControllerProperties settings =
        new Binder(ConfigurationPropertySources.get(environment))
            .bind("aisolate.controller", ControllerProperties.class)
            .get();

    // Assert
    assertThat(settings.egress().hosts()).containsOnlyKeys("acme-db.aisolate.svc");
    assertThat(settings.egress().hosts().get("acme-db.aisolate.svc"))
        .containsKey("podSelector")
        .containsKey("port");
    assertThat(settings.egress().proxies("models.example.com")).isTrue();
    assertThat(settings.egress().proxies("acme-db.aisolate.svc")).isFalse();
    assertThat(settings.otlpEndpoint()).isEqualTo("http://tempo.aisolate-monitoring.svc:4318");
    assertThat(settings.resources().airlock().requests())
        .containsEntry("cpu", "250m")
        .containsEntry("ephemeral-storage", "256Mi");
    assertThat(settings.resources().airlock().limits()).containsEntry("cpu", "2");
  }

  @Test
  void givenAuditSettingsAndTheKeyInTheEnvironment_whenBound_thenTheIngestIsOn()
      throws IOException {
    // Arrange: the chart writes the URL into settings.yaml and the key, a Secret, into the env
    String yaml =
        """
        aisolate:
          controller:
            audit: {url: "http://backend.aisolate.svc:8085/v1/audit/events", failClosed: true, stdout: false}
        """;
    StandardEnvironment environment = new StandardEnvironment();
    new YamlPropertySourceLoader()
        .load("settings.yaml", new ByteArrayResource(yaml.getBytes()))
        .forEach(source -> environment.getPropertySources().addFirst(source));
    environment
        .getPropertySources()
        .replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                Map.of("AISOLATE_CONTROLLER_AUDIT_INGESTKEY", "the-key")));

    // Act
    ControllerProperties settings =
        new Binder(ConfigurationPropertySources.get(environment))
            .bind("aisolate.controller", ControllerProperties.class)
            .get();

    // Assert
    assertThat(settings.auditEnabled()).isTrue();
    assertThat(settings.audit().ingestKey()).isEqualTo("the-key");
    assertThat(settings.audit().stdout()).isFalse();
  }

  @ParameterizedTest
  @CsvSource({"false, true", "true, false"})
  void givenATagOnlyImage_whenImagesBuilt_thenRefusedNamingItUnlessTagsAreAllowed(
      boolean allowTags, boolean refused) {
    // Arrange
    String pinned = "aisolate/x@sha256:" + "0".repeat(64);

    // Act
    Throwable thrown =
        catchThrowable(
            () ->
                new ControllerProperties.Images(
                    pinned, "aisolate/tool-server:dev", pinned, pinned, "Never", allowTags));

    // Assert
    if (refused) {
      assertThat(thrown)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage(
              "the image aisolate/tool-server:dev has no digest: pin it as repository@sha256:..."
                  + " or set allowTags");
    } else {
      assertThat(thrown).isNull();
    }
  }

  @Test
  void givenSettingsYamlWithAnOpenCidr_whenBound_thenBindingFailsNamingTheHost()
      throws IOException {
    // Arrange
    String yaml =
        """
        aisolate:
          controller:
            egress:
              hosts:
                "[models.example.com]":
                  cidr: 0.0.0.0/0
                  port: 443
        """;
    StandardEnvironment environment = new StandardEnvironment();
    new YamlPropertySourceLoader()
        .load("settings.yaml", new ByteArrayResource(yaml.getBytes()))
        .forEach(source -> environment.getPropertySources().addFirst(source));

    // Act
    Throwable thrown =
        catchThrowable(
            () ->
                new Binder(ConfigurationPropertySources.get(environment))
                    .bind("aisolate.controller", ControllerProperties.class)
                    .get());

    // Assert: the controller does not start on a peer no policy would bound
    assertThat(thrown)
        .rootCause()
        .hasMessage(
            "egress.hosts[models.example.com]: the cidr 0.0.0.0/0 is too wide: /8 or longer");
  }
}
