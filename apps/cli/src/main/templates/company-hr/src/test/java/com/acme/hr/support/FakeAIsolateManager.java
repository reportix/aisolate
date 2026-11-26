package com.acme.hr.support;

import com.reportix.aisolate.sdk.AIsolateManager;
import com.reportix.aisolate.sdk.AirlockRef;
import com.reportix.aisolate.sdk.Llm;
import com.reportix.aisolate.sdk.Principal;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;

/**
 * A hand-written {@link AIsolateManager} for handler tests: a fixed user, no platform behind, and
 * optionally a stand-in for the data source {@code hr}.
 *
 * @param hr the data source {@code hr}; null for none
 */
public record FakeAIsolateManager(Principal principal, AirlockRef airlock, DataSource hr)
    implements AIsolateManager {

  /** Eric, an employee, on the hr airlock, which defines no data source. */
  public FakeAIsolateManager() {
    this((DataSource) null);
  }

  /** Eric, with {@code hr} as the data source {@code hr}. */
  public FakeAIsolateManager(DataSource hr) {
    this(
        new Principal("eric", "eric@example.com", Set.of("employee")),
        new AirlockRef("test-space", "hr", "v1", "test"),
        hr);
  }

  public FakeAIsolateManager(Principal principal, AirlockRef airlock) {
    this(principal, airlock, null);
  }

  /** Every id is an LLM; a vector is one dimension, the text's length: enough to tell two apart. */
  @Override
  public Llm llm(String id) {
    return new Llm() {
      @Override
      public float[] embed(String text) {
        return new float[] {text.length()};
      }

      @Override
      public List<float[]> embed(List<String> texts) {
        return texts.stream().map(this::embed).toList();
      }

      @Override
      public String chat(String prompt) {
        return "";
      }
    };
  }

  /** {@code hr} when given; every other id is one this airlock does not define. */
  @Override
  public DataSource dataSource(String id) {
    if (hr != null && id.equals("hr")) {
      return hr;
    }
    throw new IllegalArgumentException(
        airlock.space() + "/" + airlock.airlock() + " has no data source " + id);
  }

  @Override
  public System.Logger log() {
    return System.getLogger(FakeAIsolateManager.class.getName());
  }
}
