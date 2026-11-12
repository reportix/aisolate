package com.reportix.aisolate.controller.reconciler.airlock;

import com.reportix.aisolate.controller.api.v1.Airlock;
import com.reportix.aisolate.controller.api.v1.AirlockVersion;
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
import java.util.Set;
import lombok.RequiredArgsConstructor;

/**
 * One airlock: whether its space's release loads, and which of its versions it holds back. The
 * airlock owns no object; each of its versions runs its own pods.
 */
@RequiredArgsConstructor
public class AirlockReconciler implements Reconciler<Airlock> {

  private static final String KIND = "Airlock";

  private final Releases releases;

  @Override
  public UpdateControl<Airlock> reconcile(Airlock airlock, Context<Airlock> context) {
    String name = airlock.getMetadata().getName();
    Long generation = airlock.getMetadata().getGeneration();
    // Before versions, the airlock ran a pod pair of its own: an install upgraded still holds it
    // until this sweeps it, and its first version brings the new pair.
    // ponytail: swept every pass (six narrowed lists); drop once no install predates versions
    releases.deleteOwnedExcept(KIND, name, name, List.of());
    Releases.Snapshot snapshot = releases.read();
    String named = airlock.getSpec() == null ? null : airlock.getSpec().space();
    ResourceStatus status =
        snapshot
            .findSpaceOf(name)
            .map(space -> releases.load(snapshot, space))
            .map(release -> statusOf(snapshot, release, name, generation))
            .orElseGet(() ->
                ResourceStatus.orphaned(
                    generation,
                    named == null
                        ? "spec.space is required"
                        : "its Space " + named + " does not exist"));
    return Releases.patchIfChanged(airlock, status);
  }

  private static ResourceStatus statusOf(
      Releases.Snapshot snapshot, Release release, String name, Long generation) {
    return release.serves(KIND, name)
        ? ResourceStatus.ready(
            release.configuration().revision(),
            generation,
            null,
            Releases.heldBack(snapshot, release, Set.of(name)))
        : ResourceStatus.invalid(generation, release.problemsOf(KIND, name));
  }

  @Override
  public ErrorStatusUpdateControl<Airlock> updateErrorStatus(
      Airlock airlock, Context<Airlock> context, Exception e) {
    return Releases.patchApplyFailed(airlock, e);
  }

  /**
   * The Global changes every release; a Space or an Airlock changes the release of its own space,
   * so it reaches that space's airlocks; a version reaches the airlock it names.
   */
  @Override
  public List<EventSource<?, Airlock>> prepareEventSources(EventSourceContext<Airlock> context) {
    return List.of(
        ReleaseEvents.all(Global.class, context),
        ReleaseEvents.keyed(
            Space.class, s -> s.getMetadata().getName(), ReleaseEvents::spaceNamedBy, context),
        ReleaseEvents.keyed(
            Airlock.class, ReleaseEvents::spaceNamedBy, ReleaseEvents::spaceNamedBy, context),
        ReleaseEvents.oneNamed(AirlockVersion.class, Airlock.class, ReleaseEvents::airlockNamedBy));
  }
}
