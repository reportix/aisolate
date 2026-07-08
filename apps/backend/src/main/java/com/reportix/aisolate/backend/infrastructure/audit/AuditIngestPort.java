package com.reportix.aisolate.backend.infrastructure.audit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.apache.catalina.connector.Connector;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The ingest port: a connector of its own, which the NetworkPolicies let the airlock and tool
 * server pods reach and nothing else. It answers {@code POST /v1/audit/events} and {@code GET
 * /v1/code/...} and nothing more, and the API port never answers those: a pod that holds a user's
 * token gains no API through it. Its endpoints take no user token, so the JWT check is not in their
 * way; each checks the airlock's token itself.
 */
@Configuration
public class AuditIngestPort {

  public static final String PATH = "/v1/audit/events";

  /** An airlock's code, {@code <space>/<airlock>/<digest>} below it. */
  public static final String CODE_PATH = "/v1/code";

  @Bean
  Connector auditIngestConnector(AuditProperties properties) {
    Connector connector = new Connector();
    connector.setPort(properties.ingestPort());
    return connector;
  }

  @Bean
  WebServerFactoryCustomizer<TomcatServletWebServerFactory> auditIngestConnectorOnTomcat(
      Connector auditIngestConnector) {
    return factory -> factory.addAdditionalConnectors(auditIngestConnector);
  }

  /** Before Spring Security: a request in the wrong place is 404 before any token is read. */
  @Bean
  FilterRegistrationBean<OncePerRequestFilter> auditIngestOnItsPortOnly(
      Connector auditIngestConnector) {
    FilterRegistrationBean<OncePerRequestFilter> registration =
        new FilterRegistrationBean<>(
            new OncePerRequestFilter() {
              @Override
              protected void doFilterInternal(
                  HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                  throws ServletException, IOException {
                boolean onIngestPort =
                    request.getLocalPort() == auditIngestConnector.getLocalPort();
                String uri = request.getRequestURI();
                boolean ingest =
                    ("POST".equals(request.getMethod()) && PATH.equals(uri))
                        || ("GET".equals(request.getMethod()) && uri.startsWith(CODE_PATH + "/"));
                if (onIngestPort != ingest) {
                  response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                  response.setContentType("application/problem+json");
                  response.getWriter().write("{\"status\":404,\"title\":\"Not Found\"}");
                  return;
                }
                chain.doFilter(request, response);
              }
            });
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return registration;
  }

  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  SecurityFilterChain auditIngestSecurity(HttpSecurity http, Connector auditIngestConnector)
      throws Exception {
    return http.securityMatcher(
            request -> request.getLocalPort() == auditIngestConnector.getLocalPort())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .build();
  }
}
