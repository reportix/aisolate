package com.reportix.aisolate.airlock.domain.airlock;

import com.reportix.aisolate.config.DiscoveredAirlock;
import java.util.List;
import java.util.Optional;

/**
 * Where the runtime finds the airlock versions that serve: the documents of one release. The
 * exchange runtime, admission and discovery read only this.
 */
public interface AirlockCatalog {

  /**
   * The version that serves under this airlock and label; empty when unknown or not served here.
   */
  Optional<AirlockDefinition> findAirlock(String id, String version);

  /** Every airlock discovery lists, each with its versions by label. */
  List<DiscoveredAirlock> listAirlocks();

  /** Every version this process serves, whatever discovery lists. */
  List<AirlockDefinition> served();

  /** What identifies the configuration these airlocks came from; null when it serves none. */
  String revision();
}
