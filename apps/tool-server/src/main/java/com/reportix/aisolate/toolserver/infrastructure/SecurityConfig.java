package com.reportix.aisolate.toolserver.infrastructure;

import com.reportix.aisolate.identity.IdentityProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The tool server is an OAuth 2.1 resource server for the same audience as the backend. It
 * validates the user's token itself (issuer, signature, expiry, audience via Boot properties) and
 * trusts nobody else's word about who the user is; without an audience it refuses to start.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(IdentityProperties.class)
public class SecurityConfig {

  /** The same refusal of a token without {@code sub} as the airlock's: 401, never a 500. */
  @Bean
  OAuth2TokenValidator<Jwt> subjectRequired() {
    return com.reportix.aisolate.identity.SecurityConfig.subjectRequired();
  }

  @Bean
  SecurityFilterChain mcpSecurity(HttpSecurity http, OAuth2ResourceServerProperties resourceServer)
      throws Exception {
    com.reportix.aisolate.identity.SecurityConfig.requireAudience(resourceServer);
    return http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/actuator/health/**", "/actuator/prometheus")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
        .build();
  }
}
