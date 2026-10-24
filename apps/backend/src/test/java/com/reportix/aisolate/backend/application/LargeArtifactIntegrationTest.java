package com.reportix.aisolate.backend.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.reportix.aisolate.backend.BackendApplication;
import com.reportix.aisolate.backend.domain.deploy.PythonArtifacts;
import com.reportix.aisolate.backend.support.BackendTestConfig;
import com.reportix.aisolate.backend.support.OwnDatabase;
import com.reportix.aisolate.config.AuditIngestToken;
import com.reportix.aisolate.identity.testing.Http;
import com.reportix.aisolate.identity.testing.Tokens;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.catalina.connector.Connector;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ContextConfiguration;
import tools.jackson.databind.json.JsonMapper;

/**
 * A 50 MB artifact deployed and fetched end to end. It runs in the {@code largeArtifactTest} task,
 * a JVM whose heap cannot hold the artifact a few times over: held whole on the way in or out, it
 * fails with an OutOfMemoryError, so passing is the streaming's proof.
 */
@Tag("large-artifact")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = BackendApplication.class,
    properties = "aisolate.test.database=large")
@ContextConfiguration(initializers = {BackendTestConfig.class, OwnDatabase.class})
class LargeArtifactIntegrationTest {

  static final int MIB = 1024 * 1024;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @LocalServerPort int port;
  @Autowired Connector auditIngestConnector;
  @TempDir Path dir;

  @Test
  void givenA50MegabyteArtifact_whenDeployedAndFetched_thenTheSameBytesStreamedBothWays()
      throws Exception {
    // Arrange: random bytes do not deflate, so the zip itself is 50 MB
    Path zip = writeZip(dir.resolve("big.zip"), 50 * MIB);
    String rita = Tokens.forOperator("andrew", "password");
    String jasmine = Tokens.forOperator("jasmine", "password");
    post(rita, "/v1/spaces", CodeIntegrationTest.SPACE_FORM.formatted("large-lab", "large-lab"));
    post(rita, "/v1/spaces/large-lab/airlocks", "{\"id\":\"hr-assistant\",\"name\":\"HR\"}");

    // Act
    HttpResponse<String> deployed = deploy(jasmine, zip);
    String digest = JSON.readTree(deployed.body()).path("jar").path("digest").asString();
    String fetchedDigest;
    long contentLength;
    int fetchedStatus;
    // A client of its own: Http.send closes its client before a streamed body is read
    try (HttpClient client = HttpClient.newHttpClient()) {
      HttpResponse<InputStream> fetched =
          client.send(
              Http.bearer(
                      HttpRequest.newBuilder(
                          URI.create(
                              "http://localhost:"
                                  + auditIngestConnector.getLocalPort()
                                  + "/v1/code/large-lab/hr-assistant/"
                                  + digest)),
                      AuditIngestToken.ofCode(
                          BackendTestConfig.INGEST_KEY, "large-lab", "hr-assistant"))
                  .build(),
              HttpResponse.BodyHandlers.ofInputStream());
      fetchedStatus = fetched.statusCode();
      contentLength = fetched.headers().firstValueAsLong("content-length").orElse(-1);
      fetchedDigest = sha256(fetched.body());
    }

    // Assert
    assertThat(deployed.statusCode()).as(deployed.body()).isEqualTo(200);
    assertThat(digest).isEqualTo(sha256(Files.newInputStream(zip)));
    assertThat(fetchedStatus).isEqualTo(200);
    assertThat(contentLength).isEqualTo(Files.size(zip));
    assertThat(fetchedDigest).isEqualTo(digest);
  }

  /** The HR manifest's zip plus {@code size} random bytes, written a mebibyte at a time. */
  private static Path writeZip(Path file, int size) throws IOException {
    Random random = new Random(7);
    byte[] buffer = new byte[MIB];
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
      out.setLevel(0);
      out.putNextEntry(new ZipEntry("aisolate-manifest.json"));
      out.write(
          JSON.writeValueAsString(PythonArtifacts.manifest()).getBytes(StandardCharsets.UTF_8));
      for (Map.Entry<String, byte[]> entry : PythonArtifacts.files().entrySet()) {
        out.putNextEntry(new ZipEntry(entry.getKey()));
        out.write(entry.getValue());
      }
      out.putNextEntry(new ZipEntry("vendor/blob.bin"));
      for (int written = 0; written < size; written += MIB) {
        random.nextBytes(buffer);
        out.write(buffer);
      }
    }
    return file;
  }

  /** The multipart body streamed from the file: the test's heap holds no copy either. */
  private HttpResponse<String> deploy(String jasmine, Path zip) throws IOException {
    String boundary = "aisolate-large-artifact";
    String head =
        part(boundary, "language", "python")
            + part(boundary, "documents", CodeIntegrationTest.PROJECT)
            + "--"
            + boundary
            + "\r\nContent-Disposition: form-data; name=\"jar\"; filename=\"project.zip\"\r\n"
            + "Content-Type: application/zip\r\n\r\n";
    HttpRequest.Builder request =
        HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/v1/spaces/large-lab/deploy"))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(
                HttpRequest.BodyPublishers.concat(
                    HttpRequest.BodyPublishers.ofString(head),
                    HttpRequest.BodyPublishers.ofFile(zip),
                    HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n")));
    return Http.send(Http.bearer(request, jasmine).build());
  }

  private static String part(String boundary, String name, String value) {
    return "--"
        + boundary
        + "\r\nContent-Disposition: form-data; name=\""
        + name
        + "\"\r\n\r\n"
        + value
        + "\r\n";
  }

  private void post(String bearer, String path, String json) {
    HttpResponse<String> response =
        Http.send(
            Http.bearer(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json)),
                    bearer)
                .build());
    assertThat(response.statusCode()).as(response.body()).isIn(201, 409);
  }

  private static String sha256(InputStream body) throws Exception {
    MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
    try (InputStream in = new DigestInputStream(body, sha256)) {
      in.transferTo(OutputStream.nullOutputStream());
    }
    return "sha256:" + HexFormat.of().formatHex(sha256.digest());
  }
}
