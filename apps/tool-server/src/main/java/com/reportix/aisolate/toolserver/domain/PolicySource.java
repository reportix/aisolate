package com.reportix.aisolate.toolserver.domain;

import com.reportix.aisolate.config.AirlockDocument;
import com.reportix.aisolate.config.Configuration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where this server learns an airlock's policy: the release's documents, which the controller
 * mounts into this pod and into the airlock pods alike, parsed once at start; never AIsolate's
 * database. Only that revision answers: another revision or an unknown airlock is empty, a silent
 * deny. Who may call is its space's users and its own, its calling clients and its machine clients;
 * the tools are the airlock's. The rules are not in the documents: they ship in the airlock's code.
 */
public final class PolicySource {

  private final String revision;
  private final Map<String, Policy> policies = new LinkedHashMap<>();

  public PolicySource(Configuration configuration) {
    this.revision = configuration.revision();
    for (AirlockDocument airlock : configuration.airlocks().values()) {
      policies.put(
          airlock.name(),
          new Policy(
              airlock.name(), revision, configuration.accessOf(airlock.name()), airlock.tools()));
    }
  }

  /**
   * The policy of an airlock at the revision the call named. Empty when the release has no such
   * airlock at that revision: unknown and replaced look the same, and a silent deny follows.
   */
  public Optional<Policy> findPolicy(String airlockId, String revision) {
    if (!this.revision.equals(revision)) {
      return Optional.empty();
    }
    return Optional.ofNullable(policies.get(airlockId));
  }

  /** Every airlock this server serves: what {@code tools/list} publishes the tools of. */
  public List<Policy> listPolicies() {
    return List.copyOf(policies.values());
  }
}
