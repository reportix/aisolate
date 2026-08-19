package com.reportix.aisolate.airlock.support;

import com.reportix.aisolate.identity.testing.Keycloak;
import java.util.List;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/** Points the airlock at the shared Keycloak; the model and tool server are stubbed per test. */
public final class AirlockTestConfig
    implements ApplicationContextInitializer<ConfigurableApplicationContext> {

  /**
   * The catalog the controller renders for the documents' airlock: its provider is the one they
   * name, and a test's @Primary scripted model answers for it.
   */
  public static final List<String> LLMS =
      List.of(
          "AISOLATE_LLM=granite",
          "AISOLATE_LLM_0_ID=granite",
          "AISOLATE_LLM_0_PROVIDER=ollama",
          "AISOLATE_LLM_0_URL=http://localhost:11434",
          "AISOLATE_LLM_0_MODEL=granite4.1:8b");

  @Override
  public void initialize(ConfigurableApplicationContext context) {
    TestPropertyValues.of(
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + Keycloak.issuerUri())
        .and(LLMS)
        .applyTo(context);
  }
}
