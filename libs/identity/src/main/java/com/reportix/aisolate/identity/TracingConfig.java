package com.reportix.aisolate.identity;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.observation.transport.ReceiverContext;
import io.micrometer.observation.transport.SenderContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingReceiverTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelSpan;
import io.micrometer.tracing.propagation.Propagator;
import io.opentelemetry.api.trace.StatusCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Spans leave the pod only when the controller rendered {@code MANAGEMENT_OTLP_TRACING_ENDPOINT},
 * the chart's {@code platform.observability.otlpEndpoint}. Boot's own endpoint property takes a
 * blank value for an endpoint and fails the start; these connection details exist for a set one
 * alone, and without them Boot builds no OTLP exporter. The spans are still made (the trace id of
 * the audit line), they just go nowhere.
 *
 * <p>The span handlers are Boot's three, except that a span records an error by its type alone: an
 * exception's message may quote an e-mail (a refusal), a statement (a driver) or a value the model
 * sent (an argument check), and a span leaves the pod. The log keeps the message.
 */
@Configuration(proxyBeanMethods = false)
public class TracingConfig {

  public static final String ENDPOINT = "MANAGEMENT_OTLP_TRACING_ENDPOINT";

  /** The collector's OTLP/HTTP base URL, as for any OTLP exporter; the traces path is added. */
  @Bean
  @Conditional(EndpointSet.class)
  OtlpTracingConnectionDetails otlpTracingConnectionDetails(
      @Value("${" + ENDPOINT + "}") String endpoint) {
    String base = endpoint.strip().replaceAll("/+$", "");
    String traces = base.endsWith("/v1/traces") ? base : base + "/v1/traces";
    return transport -> traces;
  }

  /**
   * The endpoint is read, never substituted into an expression: a quote in the admin's value would
   * otherwise break the start or run as SpEL.
   */
  static final class EndpointSet implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      String endpoint = context.getEnvironment().getProperty(ENDPOINT);
      return endpoint != null && !endpoint.isBlank();
    }
  }

  /**
   * Spring Security's own spans name the caller's authentication and authorities (an admin is one
   * of a handful of people); who asked is the audit line's, not the trace store's.
   */
  @Bean
  ObservationPredicate noSpringSecuritySpans() {
    return (name, context) -> !name.startsWith("spring.security.");
  }

  @Bean
  @Order(MicrometerTracingAutoConfiguration.DEFAULT_TRACING_OBSERVATION_HANDLER_ORDER)
  DefaultTracingObservationHandler errorTypeTracingHandler(ObjectProvider<Tracer> tracer) {
    return new DefaultTracingObservationHandler(tracer.getIfAvailable(() -> Tracer.NOOP)) {
      @Override
      public void onError(Observation.Context context) {
        recordType(getRequiredSpan(context), context.getError());
      }
    };
  }

  @Bean
  @Order(MicrometerTracingAutoConfiguration.SENDER_TRACING_OBSERVATION_HANDLER_ORDER)
  PropagatingSenderTracingObservationHandler<SenderContext<?>> errorTypeSenderTracingHandler(
      ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
    return new PropagatingSenderTracingObservationHandler<>(
        tracer.getIfAvailable(() -> Tracer.NOOP),
        propagator.getIfAvailable(() -> Propagator.NOOP)) {
      @Override
      public void onError(SenderContext<?> context) {
        recordType(getRequiredSpan(context), context.getError());
      }
    };
  }

  @Bean
  @Order(MicrometerTracingAutoConfiguration.RECEIVER_TRACING_OBSERVATION_HANDLER_ORDER)
  PropagatingReceiverTracingObservationHandler<ReceiverContext<?>> errorTypeReceiverTracingHandler(
      ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
    return new PropagatingReceiverTracingObservationHandler<>(
        tracer.getIfAvailable(() -> Tracer.NOOP),
        propagator.getIfAvailable(() -> Propagator.NOOP)) {
      @Override
      public void onError(ReceiverContext<?> context) {
        recordType(getRequiredSpan(context), context.getError());
      }
    };
  }

  /** Status ERROR and {@code error.type}, as OpenTelemetry names it; no event, no message. */
  static void recordType(Span span, Throwable error) {
    if (error != null && span instanceof OtelSpan) {
      OtelSpan.toOtel(span)
          .setStatus(StatusCode.ERROR)
          .setAttribute("error.type", error.getClass().getName());
    }
  }
}
