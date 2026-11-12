package com.reportix.aisolate.toolserver.domain;

import com.reportix.aisolate.config.AirlockDocument;
import java.util.List;

/**
 * One airlock version at one revision as the release's documents describe it: who may call (its
 * space's roles and its airlock's own, through its airlock's callers) and which tools it declares.
 * The rules and the data sources are not part of it: the rules ship in the version's code, the data
 * sources are this pod's.
 *
 * @param version the version's label within its airlock, for example {@code v1}
 * @param access its space's roles and its airlock's own, exact, its callers and its machine clients
 */
public record Policy(
    String id, String version, String revision, AirlockDocument.Access access, List<String> tools) {

  /**
   * The pin evidence names as what it was produced under: the release revision, which fixes the
   * version's code and so its rules.
   */
  public String ruleVersion() {
    return revision;
  }
}
