package com.reportix.aisolate.airlock.infrastructure.stream;

import com.reportix.aisolate.airlock.domain.chat.ChatEvent;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

/**
 * One exchange's events as server-sent events, written to the response on the request's own thread.
 *
 * <p>The first failed write means the browser left. The exchange keeps running, but nothing is
 * written again.
 */
@Slf4j
@RequiredArgsConstructor
public final class SseStream implements Consumer<ChatEvent> {

  private final HttpServletResponse response;
  private final JsonMapper jsonMapper;
  private boolean clientGone;

  /**
   * Commits the response before the first event, so the browser has the status and the headers
   * while the model is still thinking. A browser already gone is noticed, not thrown: the exchange
   * still runs to its audit line.
   */
  public SseStream open() {
    // The servlet default is ISO-8859-1; answers and evidence carry names and currency symbols.
    response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8");
    response.setCharacterEncoding("UTF-8");
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
    response.setHeader("X-Accel-Buffering", "no");
    try {
      response.flushBuffer();
    } catch (IOException clientLeft) {
      clientGone = true;
      log.debug("client left before the stream opened");
    }
    return this;
  }

  @Override
  public void accept(ChatEvent event) {
    if (clientGone) {
      return;
    }
    try {
      response
          .getWriter()
          .write(
              "event: "
                  + event.type()
                  + "\ndata: "
                  + jsonMapper.writeValueAsString(event)
                  + "\n\n");
      // PrintWriter.flush() swallows IOException; flushBuffer() is how a gone client is noticed.
      response.flushBuffer();
    } catch (IOException clientLeft) {
      clientGone = true;
      log.debug("client left before event {}", event.type());
    }
  }
}
