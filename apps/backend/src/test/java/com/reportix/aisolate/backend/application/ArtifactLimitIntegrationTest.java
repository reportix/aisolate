package com.reportix.aisolate.backend.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.reportix.aisolate.backend.BackendApplication;
import com.reportix.aisolate.backend.support.BackendTestConfig;
import com.reportix.aisolate.identity.testing.Http;
import com.reportix.aisolate.identity.testing.Tokens;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ContextConfiguration;
import tools.jackson.databind.json.JsonMapper;

/**
 * No artifact limit by default; an operator's (AISOLATE_MAX_ARTIFACT_SIZE) refuses a larger one.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = BackendApplication.class,
    properties = "spring.servlet.multipart.max-file-size=512KB")
@ContextConfiguration(initializers = BackendTestConfig.class)
class ArtifactLimitIntegrationTest {

  @LocalServerPort int port;

  @Test
  void givenAnOperatorsLimit_whenALargerArtifactIsDeployed_then400NamingTheLimit() {
    // Arrange: random bytes, past the limit before any form is read
    byte[] jar = new byte[600 * 1024];
    new Random(7).nextBytes(jar);
    Map<String, Object> parts = new LinkedHashMap<>();
    parts.put("jar", new Http.FilePart("project.jar", jar));
    String jasmine = Tokens.forOperator("jasmine", "password");
    HttpRequest.Builder request =
        Http.multipart(
            HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/v1/spaces/limit-lab/deploy")),
            "POST",
            parts);

    // Act
    HttpResponse<String> response = Http.send(Http.bearer(request, jasmine).build());

    // Assert
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(
            JsonMapper.builder().build().readTree(response.body()).path("errors").get(0).asString())
        .isEqualTo("jar: larger than 512 KiB, the most this install takes");
  }
}
