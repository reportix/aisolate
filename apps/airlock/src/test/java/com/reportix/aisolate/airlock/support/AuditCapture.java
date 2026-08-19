package com.reportix.aisolate.airlock.support;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.reportix.aisolate.airlock.domain.chat.audit.AuditLog;
import java.util.List;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Listens on the audit logger, so a test reads the lines the pod would print without scraping
 * stdout. Attached before each test, after the Spring context has set up logging.
 */
public final class AuditCapture implements BeforeEachCallback, AfterEachCallback {

  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private final Logger logger = (Logger) LoggerFactory.getLogger(AuditLog.LOGGER);

  @Override
  public void beforeEach(ExtensionContext context) {
    appender.list.clear();
    appender.start();
    logger.addAppender(appender);
  }

  @Override
  public void afterEach(ExtensionContext context) {
    logger.detachAppender(appender);
    appender.stop();
  }

  /** What each line says, as the pod prints it: the message alone. */
  public List<String> lines() {
    return List.copyOf(appender.list).stream().map(ILoggingEvent::getFormattedMessage).toList();
  }

  /** Every line parsed; one that is not a JSON object fails the test that reads it. */
  public List<JsonNode> events() {
    JsonMapper json = JsonMapper.builder().build();
    return lines().stream().map(json::readTree).toList();
  }
}
