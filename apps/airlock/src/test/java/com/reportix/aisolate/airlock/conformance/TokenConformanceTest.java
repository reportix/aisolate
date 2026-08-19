package com.reportix.aisolate.airlock.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.PlainHeader;
import com.nimbusds.jose.PlainObject;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.reportix.aisolate.airlock.AirlockApplication;
import com.reportix.aisolate.airlock.support.AirlockTestConfig;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code conformance/cases/admission/token.json}: gates 1 and 4, the airlock's real resource-server
 * chain against a JWKS this test serves as the issuer's. Shared with the Python runtimes.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = AirlockApplication.class)
@ContextConfiguration(initializers = TokenConformanceTest.Issuer.class)
class TokenConformanceTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String PUBLIC_URL = "http://aisolate.conformance";
  private static final Set<String> TIMES = Set.of("iat", "exp", "nbf");

  private static final RSAKey ISSUER_KEY = rsaKey();
  private static final RSAKey OTHER_KEY = rsaKey();
  private static final JsonNode FILE = read();

  /** The issuer's JWKS, served for the decoder as the controller's JWKS override points it. */
  static final class Issuer
      implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
      TestPropertyValues.of(
              "spring.security.oauth2.resourceserver.jwt.issuer-uri="
                  + FILE.path("issuer").asString(),
              "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=" + serveJwks(),
              "aisolate.public-url=" + PUBLIC_URL,
              "aisolate.config.dir="
                  + Path.of("src/test/resources/documents-security").toAbsolutePath())
          .and(AirlockTestConfig.LLMS)
          .applyTo(context);
    }

    private static String serveJwks() {
      try {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] jwks =
            new JWKSet(ISSUER_KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        server.createContext(
            "/certs",
            exchange -> {
              exchange.getResponseHeaders().add("Content-Type", "application/json");
              exchange.sendResponseHeaders(200, jwks.length);
              exchange.getResponseBody().write(jwks);
              exchange.close();
            });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/certs";
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  @LocalServerPort int port;

  static Stream<Arguments> cases() {
    List<Arguments> cases = new ArrayList<>();
    FILE.path("cases").forEach(c -> cases.add(arguments(named(c.path("id").asString(), c))));
    return cases.stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void givenBearer_whenChat_thenValidOr401WithTheChallenge(JsonNode token) throws Exception {
    String id = token.path("id").asString();
    // Arrange: an airlock this pod does not serve, so a valid token stops at 404, never the model
    String bearer = bearer(token);
    HttpRequest.Builder request =
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/v1/spaces/none/airlocks/none/chat"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"hi\"}"));
    if (bearer != null) {
      request.header("Authorization", "Bearer " + bearer);
    }

    // Act
    HttpResponse<String> response =
        HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());

    // Assert
    if (token.path("expect").isString()) {
      assertThat(response.statusCode()).isNotEqualTo(401);
    } else {
      assertThat(response.statusCode()).isEqualTo(token.path("expect").asInt());
      assertThat(response.headers().firstValue("WWW-Authenticate"))
          .hasValue(
              "Bearer resource_metadata=\""
                  + PUBLIC_URL
                  + "/.well-known/oauth-protected-resource\"");
      assertThat(response.body()).isEqualTo("{\"status\":401,\"title\":\"Unauthorized\"}");
    }
  }

  /** The case's bearer value; null for no Authorization header. */
  private static String bearer(JsonNode token) throws Exception {
    if (token.has("raw")) {
      return token.path("raw").asString();
    }
    String signedBy = token.path("signed_by").isNull() ? null : token.path("signed_by").asString();
    if (signedBy == null) {
      return null;
    }
    Map<String, Object> claims =
        JSON.treeToValue(
            FILE.path("base_claims"), new TypeReference<LinkedHashMap<String, Object>>() {});
    token
        .path("claims")
        .properties()
        .forEach(
            claim -> {
              if (claim.getValue().isNull()) {
                claims.remove(claim.getKey());
              } else {
                claims.put(claim.getKey(), JSON.treeToValue(claim.getValue(), Object.class));
              }
            });
    long now = Instant.now().getEpochSecond();
    TIMES.forEach(
        name -> {
          if (claims.get(name) instanceof Number relative) {
            claims.put(name, now + relative.longValue());
          }
        });
    Payload payload = new Payload(claims);
    return switch (signedBy) {
      case "issuer_key" -> rs256(payload, new RSASSASigner(ISSUER_KEY));
      case "other_key" -> rs256(payload, new RSASSASigner(OTHER_KEY));
      case "none" -> new PlainObject(new PlainHeader(), payload).serialize();
      case "hs256_public" -> {
        JWSObject jws =
            new JWSObject(
                new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("conformance").build(), payload);
        jws.sign(new MACSigner(ISSUER_KEY.toRSAPublicKey().getEncoded()));
        yield jws.serialize();
      }
      default -> throw new IllegalArgumentException("unknown signed_by: " + signedBy);
    };
  }

  private static String rs256(Payload payload, JWSSigner signer) throws Exception {
    JWSObject jws =
        new JWSObject(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("conformance").build(), payload);
    jws.sign(signer);
    return jws.serialize();
  }

  private static RSAKey rsaKey() {
    try {
      // Both under the same kid: only the signature tells them apart.
      return new RSAKeyGenerator(2048).keyID("conformance").generate();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static JsonNode read() {
    String cases = System.getProperty("aisolate.conformance.dir");
    if (cases == null) {
      throw new IllegalStateException("the build passes aisolate.conformance.dir");
    }
    return JSON.readTree(Path.of(cases, "admission", "token.json").toFile());
  }
}
