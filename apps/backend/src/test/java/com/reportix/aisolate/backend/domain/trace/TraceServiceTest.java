package com.reportix.aisolate.backend.domain.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.reportix.aisolate.backend.domain.audit.AuditException;
import com.reportix.aisolate.backend.infrastructure.tracing.JaegerClient;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** What TraceIntegrationTest cannot reach with its trace store: none, and a path smuggled in. */
class TraceServiceTest {

  @Test
  void givenNoTraceStore_whenATraceIsAsked_then404() {
    // Arrange
    TraceService service = new TraceService(null, Optional.empty(), JsonMapper.builder().build());

    // Act / Assert
    assertThatThrownBy(() -> service.get("4bf92f3577b34da6a3ce929d0e0e4736", null))
        .isInstanceOfSatisfying(
            AuditException.class, e -> assertThat(e.getStatus().value()).isEqualTo(404));
  }

  @Test
  void givenATraceIdThatIsNotOne_whenAsked_then404BeforeTheStoreIsCalled() {
    // Arrange
    JsonMapper json = JsonMapper.builder().build();
    TraceService service =
        new TraceService(null, Optional.of(new JaegerClient("http://localhost:1", json)), json);

    // Act / Assert
    assertThatThrownBy(() -> service.get("../../api/services", null))
        .isInstanceOfSatisfying(
            AuditException.class, e -> assertThat(e.getMessage()).isEqualTo("unknown trace"));
  }
}
