package com.reportix.aisolate.identity.testing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/** HTTP for the integration tests; checked exceptions become test failures. */
public final class Http {

  public static HttpResponse<String> send(HttpRequest request) {
    return send(request, HttpResponse.BodyHandlers.ofString());
  }

  /** For a body that is not text, such as a jar. */
  public static <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> body) {
    // A fresh client per call: a pooled connection the server already closed cannot be reused.
    try (HttpClient client = HttpClient.newHttpClient()) {
      return client.send(request, body);
    } catch (IOException | InterruptedException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Adds the bearer when there is one; a null token is an anonymous call. */
  public static HttpRequest.Builder bearer(HttpRequest.Builder request, String token) {
    return token == null ? request : request.header("Authorization", "Bearer " + token);
  }

  /** One file of a multipart body, as a browser sends a file input. */
  public record FilePart(String filename, byte[] content, String contentType) {
    public FilePart(String filename, byte[] content) {
      this(filename, content, "application/java-archive");
    }
  }

  /**
   * A multipart/form-data body as a browser's FormData sends it: a {@link FilePart} value is a file
   * part, any other value a text field.
   */
  public static HttpRequest.Builder multipart(
      HttpRequest.Builder request, String method, Map<String, ?> parts) {
    String boundary = "aisolate-" + UUID.randomUUID();
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    parts.forEach(
        (name, value) -> {
          String head =
              "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"";
          if (value instanceof FilePart file) {
            head += "; filename=\"" + file.filename() + "\"\r\nContent-Type: " + file.contentType();
            body.writeBytes((head + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.writeBytes(file.content());
          } else {
            body.writeBytes((head + "\r\n\r\n" + value).getBytes(StandardCharsets.UTF_8));
          }
          body.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        });
    body.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    return request
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .method(method, HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
  }

  private Http() {}
}
