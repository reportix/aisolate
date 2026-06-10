package com.reportix.aisolate.identity;

import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless OAuth 2.1 resource server. Issuer and audience come from Boot properties; a request
 * without a valid token is refused with 401 before anything else runs. The customer's chat calls
 * from the browser, so its origin is allowed for CORS.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(IdentityProperties.class)
public class SecurityConfig {

  /**
   * RFC 9728 discovery document, served by Spring Security's protected-resource metadata filter.
   */
  public static final String RESOURCE_METADATA_PATH = "/.well-known/oauth-protected-resource";

  /**
   * The AIsolate UI roles by their exact names. Normalising every role instead would let any group
   * spelt {@code AIsolate_Admin} (Okta's {@code groups}) grant backend access. Developers are no
   * realm role: a space names its own.
   */
  private static final Map<String, String> AUTHORITIES =
      Map.of(
          "aisolate-admin", "ROLE_AISOLATE_ADMIN",
          "aisolate-auditor", "ROLE_AISOLATE_AUDITOR");

  @Bean
  SecurityFilterChain apiSecurity(
      HttpSecurity http,
      IdentityProperties properties,
      OAuth2ResourceServerProperties resourceServer)
      throws Exception {
    requireAudience(resourceServer);
    String publicUrl = properties.publicUrl();
    String issuer = resourceServer.getJwt().getIssuerUri();
    AuthenticationEntryPoint unauthorized =
        (request, response, exception) -> {
          // Written directly: sendError() would dispatch to /error and run this entry point twice.
          response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
          // RFC 9728: tell generic clients where the discovery document is.
          response.setHeader(
              "WWW-Authenticate",
              "Bearer resource_metadata=\"" + publicUrl + RESOURCE_METADATA_PATH + "\"");
          response.setContentType("application/problem+json");
          response.getWriter().write("{\"status\":401,\"title\":\"Unauthorized\"}");
        };
    return http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(csrf -> csrf.disable())
        .cors(Customizer.withDefaults())
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        // the management port's: the kubelet's and the scraper's, names and counts
                        // only
                        "/actuator/health/**",
                        "/actuator/prometheus",
                        // the backend's readiness on its own port, which the AIsolate UI probes
                        "/readyz",
                        "/.well-known/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            oauth2 ->
                oauth2
                    .jwt(
                        jwt ->
                            jwt.jwtAuthenticationConverter(
                                rolesFromClaimPath(properties.rolesClaim())))
                    // The document names AIsolate (its public URL) and the customer's IdP: a
                    // generic client learns where to send the user from here.
                    .protectedResourceMetadata(
                        metadata ->
                            metadata.protectedResourceMetadataCustomizer(
                                doc ->
                                    doc.resource(publicUrl)
                                        .authorizationServer(issuer)
                                        .tlsClientCertificateBoundAccessTokens(false)
                                        .scope("aisolate")
                                        .resourceName("AIsolate")))
                    .authenticationEntryPoint(unauthorized))
        .build();
  }

  /**
   * A token without a {@code sub} names nobody: refused while the token is validated, so the caller
   * gets 401 like any unusable token, not the 500 of a {@link Principal} that cannot be built.
   * Spring Boot adds every such bean to the resource server's decoder.
   */
  @Bean
  public static OAuth2TokenValidator<Jwt> subjectRequired() {
    return new JwtClaimValidator<>(
        JwtClaimNames.SUB, sub -> sub instanceof String subject && !subject.isBlank());
  }

  /**
   * A token issued to any other client of the organisation's IdP would otherwise be accepted: an
   * app whose properties lose the audience fails to start instead.
   */
  public static void requireAudience(OAuth2ResourceServerProperties resourceServer) {
    List<String> audiences = resourceServer.getJwt().getAudiences();
    if (audiences == null || audiences.stream().allMatch(String::isBlank)) {
      throw new IllegalStateException(
          "spring.security.oauth2.resourceserver.jwt.audiences is empty: tokens must be bound to"
              + " AIsolate's audience");
    }
  }

  /**
   * The AIsolate UI roles become Spring authorities, {@code aisolate-admin} -> {@code
   * ROLE_AISOLATE_ADMIN} and {@code aisolate-auditor} -> {@code ROLE_AISOLATE_AUDITOR}; no other
   * role becomes one. The claim path is configuration, not IdP-specific code.
   */
  static Converter<Jwt, AbstractAuthenticationToken> rolesFromClaimPath(String rolesClaim) {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(
        jwt ->
            Principal.roles(jwt.getClaims(), rolesClaim).stream()
                .map(AUTHORITIES::get)
                .filter(Objects::nonNull)
                .distinct()
                .<GrantedAuthority>map(SimpleGrantedAuthority::new)
                .toList());
    return converter;
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource(IdentityProperties properties) {
    CorsConfiguration cors = new CorsConfiguration();
    // Any browser-based client may discover and call AIsolate; the token, not the origin,
    // decides.
    cors.setAllowedOriginPatterns(properties.cors().allowedOrigins());
    cors.setAllowedMethods(List.of("GET", "POST", "PUT", "OPTIONS"));
    // If-Match: the AIsolate UI's saves name what their form was read at.
    cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "If-Match"));
    // The backend's answer to a save: whether the cluster took it; an export's file name.
    cors.setExposedHeaders(List.of("AIsolate-Cluster", "Content-Disposition"));
    cors.setMaxAge(3600L);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/v1/**", cors);
    source.registerCorsConfiguration("/.well-known/**", cors);
    // The AIsolate UI probes readiness from the browser to learn when AIsolate is back.
    source.registerCorsConfiguration("/readyz", cors);
    return source;
  }
}
