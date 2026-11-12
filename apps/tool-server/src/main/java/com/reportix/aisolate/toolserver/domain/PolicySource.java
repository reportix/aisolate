package com.reportix.aisolate.toolserver.domain;

import com.reportix.aisolate.config.AirlockVersionDocument;
import com.reportix.aisolate.config.Configuration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where this server learns an airlock version's policy: the release's documents, which the
 * controller mounts into this pod and into the airlock pods alike, parsed once at start; never
 * AIsolate's database. Only that revision answers: another revision, an unknown version or an
 * unknown airlock is empty, a silent deny. Who may call is its space's roles and its airlock's own,
 * its airlock's calling clients and machine clients; the tools are the version's. The rules are not
 * in the documents: they ship in the version's code.
 */
public final class PolicySource {

  private record Key(String airlockId, String version) {}

  private final String revision;
  private final Map<Key, Policy> policies = new LinkedHashMap<>();

  public PolicySource(Configuration configuration) {
    this.revision = configuration.revision();
    for (AirlockVersionDocument version : configuration.airlockVersions().values()) {
      String airlock = version.spec().airlock();
      policies.put(
          new Key(airlock, version.spec().version()),
          new Policy(
              airlock,
              version.spec().version(),
              revision,
              configuration.accessOf(airlock),
              version.tools()));
    }
  }

  /**
   * The policy of an airlock version at the revision the call named. Empty when the release has no
   * such version at that revision: unknown and replaced look the same, and a silent deny follows.
   *
   * @param version the version's label, for example {@code v1}
   */
  public Optional<Policy> findPolicy(String airlockId, String version, String revision) {
    if (!this.revision.equals(revision)) {
      return Optional.empty();
    }
    return Optional.ofNullable(policies.get(new Key(airlockId, version)));
  }

  /** Every version this server serves: what {@code tools/list} publishes the tools of. */
  public List<Policy> listPolicies() {
    return List.copyOf(policies.values());
  }
}
