package com.reportix.aisolate.airlock.infrastructure.config;

import io.opentelemetry.context.propagation.TextMapPropagator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Every request to the pod starts a trace of its own: whatever trace context a caller sends, in any
 * format the pod would read (W3C, B3 single or multi, baggage), is hidden before the server's
 * observation reads it. The trace id keys the audit line and the JSON answer, so a caller must
 * neither choose it nor sample the exchange out of the trace store. The pod's own calls to its tool
 * server still carry its context on.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OwnTraceFilter extends OncePerRequestFilter {

  /**
   * Named as well as read from the propagator: B3's single-header extractor also reads the multi
   * headers, which its {@code fields()} does not list.
   */
  private static final Set<String> ALWAYS_HIDDEN =
      Set.of(
          "traceparent",
          "tracestate",
          "baggage",
          "b3",
          "x-b3-traceid",
          "x-b3-spanid",
          "x-b3-parentspanid",
          "x-b3-sampled",
          "x-b3-flags");

  private final Set<String> hidden;

  public OwnTraceFilter(ObjectProvider<TextMapPropagator> propagator) {
    Set<String> all = new TreeSet<>(ALWAYS_HIDDEN);
    propagator.ifAvailable(
        p -> p.fields().forEach(field -> all.add(field.toLowerCase(Locale.ROOT))));
    this.hidden = Set.copyOf(all);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    chain.doFilter(
        new HttpServletRequestWrapper(request) {
          @Override
          public String getHeader(String name) {
            return hidden(name) ? null : super.getHeader(name);
          }

          @Override
          public Enumeration<String> getHeaders(String name) {
            return hidden(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
          }

          @Override
          public Enumeration<String> getHeaderNames() {
            return Collections.enumeration(
                Collections.list(super.getHeaderNames()).stream()
                    .filter(name -> !hidden(name))
                    .toList());
          }
        },
        response);
  }

  private boolean hidden(String header) {
    return hidden.contains(header.toLowerCase(Locale.ROOT));
  }
}
