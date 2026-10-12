package com.aldermoor.ehr.support;

import com.reportix.aisolate.sdk.AIsolateManager;
import com.reportix.aisolate.sdk.AirlockRef;
import com.reportix.aisolate.sdk.Llm;
import com.reportix.aisolate.sdk.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;

/**
 * A hand-written {@link AIsolateManager} for handler tests: a fixed user, no platform behind, the
 * data sources a handler asked for, and optionally a stand-in for the data source {@code ehr}.
 */
public final class FakeAIsolateManager implements AIsolateManager {

  private final Principal principal;
  private final AirlockRef airlock;
  private final DataSource ehr;
  public final List<String> dataSourcesAsked = new ArrayList<>();

  /** Noah, a doctor, on the cardiology airlock. */
  public FakeAIsolateManager() {
    this(
        new Principal("noah", "noah@example.com", Set.of("doctor")),
        new AirlockRef("test-space", "cardiology", "test"));
  }

  public FakeAIsolateManager(Principal principal, AirlockRef airlock) {
    this(principal, airlock, null);
  }

  /** With {@code ehr} as the data source {@code ehr}; null for none. */
  public FakeAIsolateManager(Principal principal, AirlockRef airlock, DataSource ehr) {
    this.principal = principal;
    this.airlock = airlock;
    this.ehr = ehr;
  }

  @Override
  public Principal principal() {
    return principal;
  }

  @Override
  public AirlockRef airlock() {
    return airlock;
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

  /** {@code ehr} when given; every other id is one this airlock does not define. */
  @Override
  public DataSource dataSource(String id) {
    dataSourcesAsked.add(id);
    if (ehr != null && id.equals("ehr")) {
      return ehr;
    }
    throw new IllegalArgumentException(
        airlock.space() + "/" + airlock.airlock() + " has no data source " + id);
  }

  @Override
  public System.Logger log() {
    return System.getLogger(FakeAIsolateManager.class.getName());
  }
}
