package com.reportix.aisolate.airlock.infrastructure.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI document at {@code /v3/api-docs}, Swagger UI at {@code /swagger-ui.html}. Both are public
 * (see {@link com.reportix.aisolate.identity.SecurityConfig}); every operation itself still needs
 * the user's bearer token, which the UI's Authorize button sends under the {@link #BEARER_SCHEME}
 * scheme.
 */
@OpenAPIDefinition
@SecurityScheme(
    name = SwaggerApiConfig.BEARER_SCHEME,
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT",
    description = "The end user's token from the customer's identity provider, audience aisolate")
@Configuration
public class SwaggerApiConfig {

  /** Version of the API contract, not of the build: every path is served under {@code /v1/}. */
  public static final String API_VERSION = "1";

  public static final String BEARER_SCHEME = "bearer";

  @Bean
  public OpenAPI baseOpenAPI() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Rest API for AIsolate")
                .version(API_VERSION)
                .description(
                    "Governed chat over the organisation's data. Every request carries the end"
                        + " user's token; the tool servers apply each airlock's rules to that user"
                        + " before any row leaves the database."));
  }
}
