package com.reportix.aisolate.airlock.infrastructure.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.reportix.aisolate.airlock.domain.chat.ChatEvent;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class SseStreamTest {

  private final JsonMapper jsonMapper = JsonMapper.builder().build();
  private final MockHttpServletResponse response = new MockHttpServletResponse();

  @Test
  void givenResponse_whenOpened_thenStreamHeadersSetAndResponseCommitted() throws IOException {
    // Arrange
    // Act
    new SseStream(response, jsonMapper).open();

    // Assert
    assertThat(response.getContentType()).startsWith("text/event-stream");
    assertThat(response.getCharacterEncoding()).isEqualToIgnoringCase("UTF-8");
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-cache");
    assertThat(response.getHeader("X-Accel-Buffering")).isEqualTo("no");
    assertThat(response.isCommitted()).isTrue();
  }

  @Test
  void givenAnswerWithANewline_whenAccepted_thenOneDataLine() throws IOException {
    // Arrange: a newline inside the payload would silently split the frame
    SseStream stream = new SseStream(response, jsonMapper).open();

    // Act
    stream.accept(new ChatEvent.AnswerChunk("line one\nline two"));

    // Assert: event, data, and the blank line that terminates the frame
    List<String> lines = response.getContentAsString().lines().toList();
    assertThat(lines).hasSize(3);
    assertThat(lines.getFirst()).isEqualTo("event: answer_chunk");
    assertThat(lines.get(1)).startsWith("data: {").endsWith("}").contains("line one\\nline two");
    assertThat(lines.get(2)).isEmpty();
  }

  @Test
  void givenNonAsciiAnswer_whenAccepted_thenBytesAreUtf8() throws IOException {
    // Arrange: the servlet default is ISO-8859-1, which would mangle both of these
    SseStream stream = new SseStream(response, jsonMapper).open();

    // Act
    stream.accept(new ChatEvent.AnswerChunk("Zoë earns 72 000 €"));

    // Assert
    String written = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
    assertThat(written).contains("Zoë earns 72 000 €");
  }

  @Test
  void givenClientGoneBeforeTheStreamOpens_whenOpened_thenNothingThrowsAndNothingIsWritten()
      throws IOException {
    // Arrange: the browser left within milliseconds of the 200
    HttpServletResponse gone =
        new HttpServletResponseWrapper(response) {
          @Override
          public void flushBuffer() throws IOException {
            throw new IOException("broken pipe");
          }
        };

    // Act: the exchange must still run, so its audit line is written
    SseStream stream = new SseStream(gone, jsonMapper).open();
    stream.accept(new ChatEvent.AnswerChunk("one"));

    // Assert
    assertThat(response.getContentAsString()).isEmpty();
  }

  @Test
  void givenClientGone_whenMoreEventsAccepted_thenNothingIsWrittenAndNothingThrows()
      throws IOException {
    // Arrange: MockHttpServletResponse narrows flushBuffer(), the wrapper restores the throw
    HttpServletResponse failing =
        new HttpServletResponseWrapper(response) {
          private boolean opened;

          @Override
          public void flushBuffer() throws IOException {
            if (opened) {
              throw new IOException("broken pipe");
            }
            opened = true;
            super.flushBuffer();
          }
        };
    SseStream stream = new SseStream(failing, jsonMapper).open();

    // Act
    assertThatNoException().isThrownBy(() -> stream.accept(new ChatEvent.AnswerChunk("one")));
    assertThatNoException().isThrownBy(() -> stream.accept(new ChatEvent.AnswerChunk("two")));

    // Assert: the first failed write is the last one attempted
    assertThat(response.getContentAsString()).doesNotContain("two");
  }
}
