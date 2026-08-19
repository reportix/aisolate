package com.reportix.aisolate.airlock.support;

import com.reportix.aisolate.sdk.AIsolateManager;
import com.reportix.aisolate.sdk.AirlockRef;
import com.reportix.aisolate.sdk.Llm;
import com.reportix.aisolate.sdk.LlmException;
import com.reportix.aisolate.sdk.Principal;
import java.util.Set;
import javax.sql.DataSource;

/**
 * The manager as a handler under test sees it: Eric, employee, on hr-acme/acme-hr, with no data
 * source and no model. The e-mail is the one the identity tests assert never reaches a prompt.
 */
public record FakeAIsolateManager(Principal principal, AirlockRef airlock)
    implements AIsolateManager {

  public static final Principal ERIC =
      new Principal("sub-eric", "eric@example.com", Set.of("employee"));

  public static FakeAIsolateManager forEric() {
    return new FakeAIsolateManager(ERIC, new AirlockRef("hr-acme", "acme-hr", "1"));
  }

  @Override
  public Llm llm(String id) {
    throw new LlmException("no model named " + id + " in this airlock's space or globally");
  }

  @Override
  public DataSource dataSource(String id) {
    throw new IllegalArgumentException(
        airlock.space() + "/" + airlock.airlock() + " has no data source " + id);
  }

  @Override
  public System.Logger log() {
    return System.getLogger("aisolate.handlers.test");
  }
}
