package com.reportix.aisolate.backend.infrastructure.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI document at {@code /v3/api-docs}, Swagger UI at {@code /swagger-ui.html}, both off unless
 * turned on, and then reachable without a token (see {@link
 * com.reportix.aisolate.identity.SecurityConfig}); every operation itself still needs the
 * developer's or admin's bearer token, which the UI's Authorize button sends under the {@link
 * #BEARER_SCHEME} scheme.
 */
@OpenAPIDefinition
@SecurityScheme(
    name = SwaggerApiConfig.BEARER_SCHEME,
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT",
    description =
        "The developer's or admin's token from the customer's identity provider, audience aisolate")
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
                    "The control plane: the AIsolate UI's spaces and airlocks and the"
                        + " CLI's deploy. Every request carries the developer's token."));
  }
}
