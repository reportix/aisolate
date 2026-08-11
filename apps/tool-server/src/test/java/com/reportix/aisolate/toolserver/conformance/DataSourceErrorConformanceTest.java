package com.reportix.aisolate.toolserver.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import com.reportix.aisolate.toolserver.application.ToolError;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/** {@code conformance/cases/errors/data-source.json}: what a failed read tells the airlock. */
class DataSourceErrorConformanceTest {

  private static final JsonNode FILE = ConformanceCases.file("errors", "data-source.json");

  static Stream<Arguments> cases() {
    return ConformanceCases.cases(FILE);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void givenFailedRead_whenClassified_thenErrorAndItsSentenceAsPython(JsonNode testCase) {
    // Arrange: wrapped once more, as Spring's JDBC layer or the pool hands it on
    RuntimeException failure = new RuntimeException(failure(testCase.path("failure")));

    // Act
    ToolError error = ToolError.of(failure);

    // Assert
    String expected = testCase.path("error").asString();
    assertThat(error.code()).isEqualTo(expected);
    assertThat(error.text()).isEqualTo(FILE.path("texts").path(expected).asString());
  }

  /** The failure as pgjdbc raises it; the message quotes a statement, as a driver's may. */
  private static Throwable failure(JsonNode spec) {
    Throwable cause = spec.has("cause") ? failure(spec.path("cause")) : null;
    if (spec.has("sqlstate")) {
      return new SQLException("ERROR at SELECT secret", spec.path("sqlstate").asString(), cause);
    }
    if (spec.has("network")) {
      Exception socket =
          switch (spec.path("network").asString()) {
            case "refused" -> new ConnectException("Connection refused");
            case "unknown_host" -> new UnknownHostException("db.example");
            case "no_route" -> new NoRouteToHostException("No route to host");
            case "timeout" -> new SocketTimeoutException("Read timed out");
            default -> throw new IllegalArgumentException(spec.toString());
          };
      return new SQLException("The connection attempt failed.", null, socket);
    }
    return new IllegalStateException("plugin returned no Result");
  }
}
