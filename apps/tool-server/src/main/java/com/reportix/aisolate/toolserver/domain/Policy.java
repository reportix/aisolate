package com.reportix.aisolate.toolserver.domain;

import com.reportix.aisolate.config.AirlockDocument;
import java.util.List;

/**
 * One airlock at one revision as the release's documents describe it: who may call (its space's
 * users and its own, through its callers) and which tools it declares. The rules and the data
 * sources are not part of it: the rules ship in the airlock's code, the data sources are this
 * pod's.
 *
 * @param access its space's users and its own, case ignored, its callers and its machine clients
 */
public record Policy(
    String id, String revision, AirlockDocument.Access access, List<String> tools) {

  /**
   * The pin evidence names as what it was produced under: the release revision, which fixes the
   * airlock's code and so its rules.
   */
  public String ruleVersion() {
    return revision;
  }
}
