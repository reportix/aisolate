package com.reportix.aisolate.airlock.domain.airlock;

import com.reportix.aisolate.config.DiscoveredAirlock;
import java.util.List;
import java.util.Optional;

/**
 * Where the runtime finds the airlocks that serve: the documents of one release. The exchange
 * runtime, admission and discovery read only this.
 */
public interface AirlockCatalog {

  /** The airlock that serves under this id; empty when unknown or not served here. */
  Optional<AirlockDefinition> findAirlock(String id);

  /** Every airlock discovery lists. */
  List<DiscoveredAirlock> listAirlocks();

  /** Every airlock this process serves, whatever discovery lists. */
  List<AirlockDefinition> served();

  /** What identifies the configuration these airlocks came from; null when it serves none. */
  String revision();
}
