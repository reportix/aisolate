package com.reportix.aisolate.toolserver.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.PlainObject;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.reportix.aisolate.toolserver.ToolServerApplication;
import com.reportix.aisolate.toolserver.support.McpTestClient;
import com.reportix.aisolate.toolserver.support.ToolServerTestConfig;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfiguration;
import tools.jackson.databind.JsonNode;

/**
 * {@code conformance/cases/admission/token.json} against the tool server's own resource-server
 * configuration: a JWKS served here as the issuer's, every case one {@code tools/list} call.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = ToolServerApplication.class)
@ContextConfiguration(initializers = TokenConformanceTest.Issuer.class)
class TokenConformanceTest {

  private static final JsonNode FILE = ConformanceCases.file("admission", "token.json");
  private static final Set<String> TIMES = Set.of("iat", "exp", "nbf");
  private static final RSAKey ISSUER_KEY = rsaKey();
  private static final RSAKey OTHER_KEY = rsaKey();
  private static final HttpServer JWKS = jwks();

  /** The case file's issuer and audience; the JWKS URI is the override the chart renders. */
  static class Issuer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
      TestPropertyValues.of(
              "spring.security.oauth2.resourceserver.jwt.issuer-uri="
                  + FILE.path("issuer").asString(),
              "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:"
                  + JWKS.getAddress().getPort()
                  + "/certs",
              "spring.security.oauth2.resourceserver.jwt.audiences="
                  + FILE.path("audience").asString(),
              "aisolate.config.dir=" + ToolServerTestConfig.DOCUMENTS,
              "aisolate.config.revision=" + ToolServerTestConfig.REVISION,
              // Never connected to: tools/list reads no data.
              "aisolate.datasource[0].id=acme_hr",
              "aisolate.datasource[0].url=" + ToolServerTestConfig.UNREACHABLE_URL,
              "aisolate.datasource[1].id=hr_archive",
              "aisolate.datasource[1].url=" + ToolServerTestConfig.UNREACHABLE_URL)
          .applyTo(context);
    }
  }

  @LocalServerPort int port;

  static Stream<Arguments> cases() {
    return ConformanceCases.cases(FILE);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void givenBearer_whenToolsListed_thenValidOr401AsTheCaseSays(JsonNode testCase) throws Exception {
    // Arrange
    String id = testCase.path("id").asString();
    String bearer = bearer(testCase);
    JsonNode expect = testCase.path("expect");

    // Act
    HttpResponse<String> response = new McpTestClient(port).listTools(bearer);

    // Assert
    if (expect.isString() && expect.asString().equals("valid")) {
      assertThat(response.statusCode())
          .as("%s", response.headers().firstValue("WWW-Authenticate"))
          .isEqualTo(200);
    } else {
      assertThat(response.statusCode()).isEqualTo(expect.intValue());
      assertThat(response.headers().firstValue("WWW-Authenticate"))
          .hasValueSatisfying(challenge -> assertThat(challenge).startsWith("Bearer"));
    }
  }

  @SuppressWarnings("unchecked")
  private static String bearer(JsonNode testCase) throws Exception {
    if (testCase.has("raw")) {
      return testCase.path("raw").asString();
    }
    if (testCase.path("signed_by").isNull()) {
      return null;
    }
    Map<String, Object> claims =
        new LinkedHashMap<>((Map<String, Object>) ConformanceCases.value(FILE.path("base_claims")));
    ((Map<String, Object>) ConformanceCases.value(testCase.path("claims")))
        .forEach(
            (name, value) -> {
              if (value == null) {
                claims.remove(name);
              } else {
                claims.put(name, value);
              }
            });
    long now = Instant.now().getEpochSecond();
    TIMES.forEach(
        name -> {
          if (claims.get(name) instanceof Number offset) {
            claims.put(name, now + offset.longValue());
          }
        });
    Payload payload = new Payload(claims);
    return switch (testCase.path("signed_by").asString()) {
      case "issuer_key" -> signed(JWSAlgorithm.RS256, payload, new RSASSASigner(ISSUER_KEY));
      case "other_key" -> signed(JWSAlgorithm.RS256, payload, new RSASSASigner(OTHER_KEY));
      case "hs256_public" ->
          signed(
              JWSAlgorithm.HS256, payload, new MACSigner(ISSUER_KEY.toRSAPublicKey().getEncoded()));
      case "none" -> new PlainObject(payload).serialize();
      default -> throw new IllegalArgumentException(testCase.path("signed_by").asString());
    };
  }

  private static String signed(JWSAlgorithm algorithm, Payload payload, JWSSigner signer)
      throws Exception {
    JWSObject jws =
        new JWSObject(new JWSHeader.Builder(algorithm).keyID("conformance").build(), payload);
    jws.sign(signer);
    return jws.serialize();
  }

  /** Both under kid {@code conformance}: only the signature tells them apart. */
  private static RSAKey rsaKey() {
    try {
      return new RSAKeyGenerator(2048).keyID("conformance").generate();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static HttpServer jwks() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
      byte[] body =
          new JWKSet(ISSUER_KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
      server.createContext(
          "/certs",
          exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
          });
      server.start();
      return server;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
