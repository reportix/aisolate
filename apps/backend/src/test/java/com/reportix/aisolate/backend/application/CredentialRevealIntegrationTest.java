package com.reportix.aisolate.backend.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.reportix.aisolate.backend.BackendApplication;
import com.reportix.aisolate.backend.domain.credential.CredentialRepository;
import com.reportix.aisolate.backend.infrastructure.crypto.CredentialCipher;
import com.reportix.aisolate.backend.support.BackendTestConfig;
import com.reportix.aisolate.backend.support.OwnDatabase;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

/** The reveal through the whole stack with an injected principal: the 200 and stale-login paths. */
@SpringBootTest(classes = BackendApplication.class)
@AutoConfigureMockMvc
@ContextConfiguration(initializers = {BackendTestConfig.class, OwnDatabase.class})
@TestPropertySource(properties = "aisolate.test.database=aisolate_reveal")
class CredentialRevealIntegrationTest {

  private static final String REF = "aisolate-ds-reveal-abc123";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired MockMvc mvc;
  @Autowired JdbcClient db;
  @Autowired CredentialCipher cipher;
  @Autowired CredentialRepository rows;

  @BeforeEach
  void seed() {
    db.sql(
            "INSERT INTO data_sources (id, url, secret_ref, created_by, created_by_name)"
                + " VALUES ('reveal', 'jdbc:postgresql://x/y', ?, 'andrew', 'Andrew')"
                + " ON CONFLICT (id) DO NOTHING")
        .param(REF)
        .update();
    if (rows.find(REF).isEmpty()) {
      CredentialCipher.Sealed sealed =
          cipher.seal(
              REF,
              JSON.writeValueAsBytes(
                  new TreeMap<>(Map.of("password", "hunter2", "username", "reader"))));
      rows.insert(
          new CredentialRepository.Row(
              REF,
              "install",
              "",
              "ds",
              "reveal",
              cipher.keyId(),
              sealed.nonce(),
              sealed.ciphertext(),
              "andrew"));
    }
  }

  private static RequestPostProcessor caller(long authTime, String... authorities) {
    return caller("aisolate-ui", authTime, authorities);
  }

  private static RequestPostProcessor caller(String azp, long authTime, String... authorities) {
    return jwt()
        .jwt(
            j ->
                j.claim("email", "andrew@example.com")
                    .claim("azp", azp)
                    .claim("auth_time", authTime))
        .authorities(
            java.util.Arrays.stream(authorities)
                .<org.springframework.security.core.GrantedAuthority>map(
                    SimpleGrantedAuthority::new)
                .toList());
  }

  @Test
  void givenAnAdminWhoSignedInJustNow_whenRevealed_then200WithTheValuesAndNoStore()
      throws Exception {
    // Arrange
    RequestPostProcessor admin = caller(Instant.now().getEpochSecond(), "ROLE_AISOLATE_ADMIN");

    // Act
    var result = mvc.perform(post("/v1/credentials/{ref}/reveal", REF).with(admin));

    // Assert
    result
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.values.username").value("reader"))
        .andExpect(jsonPath("$.values.password").value("hunter2"))
        .andExpect(header().string("Cache-Control", "no-store"));
    CredentialRepository.Event last = rows.events(REF).getLast();
    assertThat(last.action()).isEqualTo("revealed");
    assertThat(last.actor()).isEqualTo("andrew@example.com");
  }

  @Test
  void givenAnAdminWhoseLoginIsStale_whenRevealed_then403StaleLoginWithTheReason()
      throws Exception {
    // Arrange
    RequestPostProcessor admin =
        caller(Instant.now().minusSeconds(301).getEpochSecond(), "ROLE_AISOLATE_ADMIN");

    // Act
    var result = mvc.perform(post("/v1/credentials/{ref}/reveal", REF).with(admin));

    // Assert
    String body =
        result
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.detail").value("sign in again to reveal"))
            .andExpect(jsonPath("$.reason").value("stale_login"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).doesNotContain("hunter2");
    CredentialRepository.Event last = rows.events(REF).getLast();
    assertThat(last.action()).isEqualTo("reveal_refused");
    assertThat(last.reason()).isEqualTo("stale_login");
  }

  @Test
  void givenTheCliClient_whenRevealed_then403WrongClient() throws Exception {
    // Arrange
    RequestPostProcessor admin =
        caller("aisolate-cli", Instant.now().getEpochSecond(), "ROLE_AISOLATE_ADMIN");

    // Act
    var result = mvc.perform(post("/v1/credentials/{ref}/reveal", REF).with(admin));

    // Assert
    String body =
        result
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.reason").value("wrong_client"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).doesNotContain("hunter2");
    assertThat(rows.events(REF).getLast().reason()).isEqualTo("wrong_client");
  }

  @ParameterizedTest
  @ValueSource(strings = {"x' OR 1=1", "a b"})
  void givenAnOddRef_whenRevealed_then404(String ref) throws Exception {
    // Arrange
    RequestPostProcessor admin = caller(Instant.now().getEpochSecond(), "ROLE_AISOLATE_ADMIN");

    // Act: the template variable is URL-encoded on expansion
    var result = mvc.perform(post("/v1/credentials/{ref}/reveal", ref).with(admin));

    // Assert
    result.andExpect(status().isNotFound());
  }

  @Test
  void givenADeveloper_whenRevealed_then403AndNothingRecorded() throws Exception {
    // Arrange
    int before = rows.events(REF).size();
    RequestPostProcessor developer = caller(Instant.now().getEpochSecond());

    // Act
    var result = mvc.perform(post("/v1/credentials/{ref}/reveal", REF).with(developer));

    // Assert
    result.andExpect(status().isForbidden());
    assertThat(rows.events(REF)).hasSize(before);
  }
}
