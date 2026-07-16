package com.reportix.aisolate.controller.reconciler.space;

import com.reportix.aisolate.controller.api.v1.Airlock;
import com.reportix.aisolate.controller.api.v1.Global;
import com.reportix.aisolate.controller.api.v1.ResourceStatus;
import com.reportix.aisolate.controller.api.v1.Space;
import com.reportix.aisolate.controller.reconciler.ReleaseEvents;
import com.reportix.aisolate.controller.reconciler.Releases;
import com.reportix.aisolate.controller.release.Release;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ErrorStatusUpdateControl;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceContext;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.javaoperatorsdk.operator.processing.event.source.EventSource;
import java.util.List;
import lombok.RequiredArgsConstructor;

/**
 * One space: whether its release loads and which of its airlocks it holds back, and discovery (the
 * {@code aisolate-discovery} ConfigMap and its pods), which only this reconciler writes. Each
 * airlock runs its own pods.
 */
@RequiredArgsConstructor
public class SpaceReconciler implements Reconciler<Space> {

  private final Releases releases;

  @Override
  public UpdateControl<Space> reconcile(Space space, Context<Space> context) {
    String name = space.getMetadata().getName();
    Long generation = space.getMetadata().getGeneration();
    Releases.Snapshot snapshot = releases.read();
    // Every pass, valid or not: a broken space leaves discovery, a fixed one comes back.
    releases.apply(releases.renderDiscovery(snapshot));
    Release release = releases.load(snapshot, name);
    ResourceStatus status =
        release.valid()
            ? ResourceStatus.ready(
                release.configuration().revision(),
                generation,
                null,
                Releases.heldBack(snapshot, release, name))
            : ResourceStatus.invalid(generation, release.problemsOf("Space", name));
    return Releases.patchIfChanged(space, status);
  }

  @Override
  public ErrorStatusUpdateControl<Space> updateErrorStatus(
      Space space, Context<Space> context, Exception e) {
    return Releases.patchApplyFailed(space, e);
  }

  /**
   * The Global and every Space change discovery, which a Space reconcile alone writes and which
   * lists every valid space: a deleted Space is gone from the cache, and only the fan-out to the
   * others takes it out. An Airlock changes the release of its own space, so it reaches that Space.
   */
  @Override
  public List<EventSource<?, Space>> prepareEventSources(EventSourceContext<Space> context) {
    return List.of(
        ReleaseEvents.all(Global.class, context),
        ReleaseEvents.all(Space.class, context),
        ReleaseEvents.spaceOf(Airlock.class, ReleaseEvents::spaceNamedBy));
  }
}
