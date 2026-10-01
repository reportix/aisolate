package com.reportix.aisolate.cli;

import java.io.FileNotFoundException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The one deploy call: {@code multipart/form-data} with the code as the file part {@code jar} (a
 * jar, or a Python project's zip) and its {@code language}, {@code aisolate.yaml} as a text field
 * and the variables it reads as a JSON field, the way the backend reads them.
 */
@RequiredArgsConstructor
final class DeployClient {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final HttpClient client;

  HttpResponse<String> deploy(
      String endpoint,
      String space,
      String token,
      Path jar,
      Language language,
      String documentsYaml,
      Map<String, String> variables)
      throws FileNotFoundException {
    String boundary = "aisolate-" + UUID.randomUUID();
    ObjectNode values = JSON.createObjectNode();
    variables.forEach(values::put);
    return Auth.send(
        client,
        HttpRequest.newBuilder(URI.create(endpoint + "/v1/spaces/" + space + "/deploy"))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(
                BodyPublishers.concat(
                    BodyPublishers.ofString(
                        "--"
                            + boundary
                            + "\r\nContent-Disposition: form-data; name=\"jar\"; filename=\""
                            + jar.getFileName()
                            + "\"\r\nContent-Type: "
                            + (language == Language.python
                                ? "application/zip"
                                : "application/java-archive")
                            + "\r\n\r\n"),
                    BodyPublishers.ofFile(jar),
                    BodyPublishers.ofString(
                        "\r\n--"
                            + boundary
                            + "\r\nContent-Disposition: form-data; name=\"language\""
                            + "\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n"
                            + language
                            + "\r\n--"
                            + boundary
                            + "\r\nContent-Disposition: form-data; name=\"documents\""
                            + "\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n"
                            + documentsYaml
                            + "\r\n--"
                            + boundary
                            + "\r\nContent-Disposition: form-data; name=\"variables\""
                            + "\r\nContent-Type: application/json\r\n\r\n"
                            + JSON.writeValueAsString(values)
                            + "\r\n--"
                            + boundary
                            + "--\r\n")))
            .build());
  }
}
