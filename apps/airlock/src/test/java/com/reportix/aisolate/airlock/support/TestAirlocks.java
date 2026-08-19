package com.reportix.aisolate.airlock.support;

import com.reportix.aisolate.airlock.domain.airlock.AirlockDefinition;
import com.reportix.aisolate.config.AirlockDocument;
import java.util.List;

/**
 * The airlock the unit tests talk to: hr-acme/acme-hr, listing Eric, on the acme_hr data source.
 */
public final class TestAirlocks {

  public static final AirlockDefinition.HandlerSelection NO_HANDLERS =
      new AirlockDefinition.HandlerSelection(null, null, null, null);

  public static AirlockDefinition acmeHr(
      String revision,
      String model,
      List<String> tools,
      AirlockDefinition.HandlerSelection handlers) {
    return new AirlockDefinition(
        "acme-hr",
        "",
        revision,
        "hr-acme",
        "Acme HR assistant",
        new AirlockDocument.Access(List.of("eric@example.com"), List.of("acme-chat")),
        model,
        tools,
        List.of("acme_hr"),
        "You are the Acme HR assistant.",
        handlers);
  }

  private TestAirlocks() {}
}
