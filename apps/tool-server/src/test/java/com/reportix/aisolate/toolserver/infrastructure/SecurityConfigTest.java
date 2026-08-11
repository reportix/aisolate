package com.reportix.aisolate.toolserver.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.api.Test;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;

class SecurityConfigTest {

  @Test
  void givenNoAudience_whenTheSecurityIsBuilt_thenTheToolServerRefusesToStart() {
    // Arrange: a blank AISOLATE_AUDIENCE would accept every client's token
    OAuth2ResourceServerProperties resourceServer = new OAuth2ResourceServerProperties();

    // Act
    Throwable thrown = catchThrowable(() -> new SecurityConfig().mcpSecurity(null, resourceServer));

    // Assert
    assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessageContaining("audiences");
  }
}
