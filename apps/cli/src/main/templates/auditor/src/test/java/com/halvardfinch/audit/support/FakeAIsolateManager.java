package com.halvardfinch.audit.support;

import com.reportix.aisolate.sdk.AIsolateManager;
import com.reportix.aisolate.sdk.AirlockRef;
import com.reportix.aisolate.sdk.Llm;
import com.reportix.aisolate.sdk.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;

/**
 * A hand-written {@link AIsolateManager} for handler tests: a fixed user, no platform behind, and
 * the data sources a handler asked for.
 */
public final class FakeAIsolateManager implements AIsolateManager {

  private final Principal principal;
  private final AirlockRef airlock;
  public final List<String> dataSourcesAsked = new ArrayList<>();

  /** Hannah, an auditor, on an audited company's airlock. */
  public FakeAIsolateManager() {
    this(
        new Principal("hannah", "hannah@example.com", Set.of("auditor")),
        new AirlockRef("test-space", "audit", "test"));
  }

  public FakeAIsolateManager(Principal principal, AirlockRef airlock) {
    this.principal = principal;
    this.airlock = airlock;
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

  /** No database behind it: every id is one this airlock does not define. */
  @Override
  public DataSource dataSource(String id) {
    dataSourcesAsked.add(id);
    throw new IllegalArgumentException(
        airlock.space() + "/" + airlock.airlock() + " has no data source " + id);
  }

  @Override
  public System.Logger log() {
    return System.getLogger(FakeAIsolateManager.class.getName());
  }
}
