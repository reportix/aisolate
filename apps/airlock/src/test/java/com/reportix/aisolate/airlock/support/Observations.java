package com.reportix.aisolate.airlock.support;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;

/** Observation registries for tests that read spans. */
public final class Observations {

  /** A registry that adds every stopped observation's context to {@code spans}. */
  public static ObservationRegistry recording(List<Observation.Context> spans) {
    ObservationRegistry registry = ObservationRegistry.create();
    registry
        .observationConfig()
        .observationHandler(
            new ObservationHandler<>() {
              @Override
              public boolean supportsContext(Observation.Context context) {
                return true;
              }

              @Override
              public void onStop(Observation.Context context) {
                spans.add(context);
              }
            });
    return registry;
  }

  private Observations() {}
}
