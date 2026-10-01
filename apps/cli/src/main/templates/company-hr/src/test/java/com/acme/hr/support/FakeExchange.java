package com.acme.hr.support;

import com.reportix.aisolate.sdk.Evidence;
import com.reportix.aisolate.sdk.Exchange;
import com.reportix.aisolate.sdk.Passage;
import java.util.ArrayList;
import java.util.List;

/** A hand-written {@link Exchange} for handler tests: records what a handler adds. */
public final class FakeExchange implements Exchange {

  private final String question;
  public final List<String> context = new ArrayList<>();
  public final List<Evidence> evidence = new ArrayList<>();

  public FakeExchange(String question) {
    this.question = question;
  }

  @Override
  public String question() {
    return question;
  }

  @Override
  public String addEvidence(String source, List<Passage> passages) {
    if (passages.isEmpty()) {
      return null;
    }
    String id = "ev-" + (evidence.size() + 1);
    evidence.add(new Evidence(id, source, passages.toString()));
    return id;
  }

  @Override
  public void addContext(String text) {
    context.add(text);
  }

  @Override
  public List<Evidence> evidence() {
    return evidence;
  }
}
