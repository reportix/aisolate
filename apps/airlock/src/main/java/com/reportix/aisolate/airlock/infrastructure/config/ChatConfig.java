package com.reportix.aisolate.airlock.infrastructure.config;

import com.reportix.aisolate.airlock.domain.chat.AgentLoopService;
import com.reportix.aisolate.airlock.domain.chat.ExchangeTracing;
import com.reportix.aisolate.airlock.domain.chat.HandlerCatalog;
import com.reportix.aisolate.airlock.domain.chat.McpToolClient;
import com.reportix.aisolate.airlock.domain.chat.audit.AuditLog;
import com.reportix.aisolate.airlock.domain.chat.audit.AuditSink;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationFilter;
import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** Wires the chat domain: the agent loop with its model and its configured budget. */
@Configuration
public class ChatConfig {

  /** Spring AI's token counter by airlock, as the example dashboard reads it. */
  @Bean
  ObservationFilter modelCallsByAirlock() {
    return ExchangeTracing.MODEL_CALLS_BY_AIRLOCK;
  }

  /** The backend's ingest the controller rendered, or none. */
  @Bean(initMethod = "start", destroyMethod = "close")
  AuditSink auditSink(AirlockProperties properties) {
    AirlockProperties.Audit audit = properties.audit();
    if (audit.url() == null || audit.url().isBlank()) {
      return AuditSink.OFF;
    }
    return new AuditSink(
        URI.create(audit.url()),
        Path.of(audit.tokenFile()),
        Path.of(audit.bufferDir()),
        audit.bufferMax().toBytes(),
        audit.failClosed(),
        Duration.ofMillis(500),
        Duration.ofSeconds(30));
  }

  @Bean
  AuditLog auditLog(
      JsonMapper jsonMapper, MeterRegistry meters, AuditSink sink, AirlockProperties properties) {
    return new AuditLog(jsonMapper, meters, sink, properties.audit().stdout());
  }

  /** No registry (a slice without actuator): no spans, no trace id. */
  @Bean
  ExchangeTracing exchangeTracing(ObjectProvider<ObservationRegistry> observations) {
    return new ExchangeTracing(observations.getIfAvailable(() -> ObservationRegistry.NOOP));
  }

  /**
   * One client per LLM of the catalog the controller rendered; a catalog it could not have rendered
   * refuses the start. A discovery pod serves no airlock and reads none.
   */
  @Bean
  Llms llms(
      Environment env,
      AirlockProperties properties,
      RestClient.Builder http,
      ObjectProvider<ObservationRegistry> observations,
      RetryTemplate retry) {
    if (properties.config().discoveryOnly()) {
      return Llms.NONE;
    }
    return Llms.connect(
        LlmCatalog.read(env::getProperty),
        http,
        observations.getIfAvailable(() -> ObservationRegistry.NOOP),
        retry,
        env.getProperty(
            "spring.http.client.settings.read-timeout", Duration.class, Duration.ofSeconds(90)));
  }

  /**
   * Built here rather than annotated, so the exchange budget stays configuration, not a field. The
   * main LLM answers; a {@link ChatModel} bean, a test's scripted one, replaces it.
   */
  @Bean
  AgentLoopService agentLoopService(
      ObjectProvider<ChatModel> chatModels,
      Llms llms,
      McpToolClient toolServer,
      HandlerCatalog handlerCatalog,
      JsonMapper jsonMapper,
      AirlockProperties properties,
      ExchangeTracing tracing,
      AuditLog auditLog) {
    CatalogLlm main = llms.main();
    return new AgentLoopService(
        chatModels.getIfUnique(() -> main == null ? null : main.chatModel()),
        main == null ? "none" : main.provider(),
        toolServer,
        handlerCatalog,
        jsonMapper,
        properties.exchange().maxDuration(),
        tracing,
        auditLog);
  }
}
