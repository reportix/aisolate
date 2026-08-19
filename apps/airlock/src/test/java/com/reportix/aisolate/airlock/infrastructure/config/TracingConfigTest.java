package com.reportix.aisolate.airlock.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.reportix.aisolate.identity.TracingConfig;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingConnectionDetails;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.Transport;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Traces leave the pod only for an endpoint the controller rendered; blank means none. */
class TracingConfigTest {

  private final ApplicationContextRunner context =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(OtlpTracingAutoConfiguration.class))
          .withUserConfiguration(TracingConfig.class);

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = " ")
  void givenNoOrBlankEndpoint_whenStarted_thenNoOtlpExporter(String endpoint) {
    // Arrange
    ApplicationContextRunner blank =
        endpoint == null
            ? context
            : context.withSystemProperties(TracingConfig.ENDPOINT + "=" + endpoint);

    // Act / Assert: the pod starts and keeps its spans
    blank.run(
        started -> {
          assertThat(started).hasNotFailed();
          assertThat(started).doesNotHaveBean(OtlpHttpSpanExporter.class);
        });
  }

  @ParameterizedTest
  @CsvSource({
    "http://tempo.aisolate-monitoring.svc:4318,"
        + " http://tempo.aisolate-monitoring.svc:4318/v1/traces",
    "http://tempo:4318/, http://tempo:4318/v1/traces",
    "http://tempo:4318/v1/traces, http://tempo:4318/v1/traces"
  })
  void givenEndpoint_whenStarted_thenOneOtlpExporterToItsTracesPath(
      String endpoint, String traces) {
    // Arrange
    ApplicationContextRunner set =
        context.withSystemProperties(TracingConfig.ENDPOINT + "=" + endpoint);

    // Act / Assert
    set.run(
        started -> {
          assertThat(started).hasSingleBean(OtlpHttpSpanExporter.class);
          assertThat(started.getBean(OtlpTracingConnectionDetails.class).getUrl(Transport.HTTP))
              .isEqualTo(traces);
        });
  }
}
