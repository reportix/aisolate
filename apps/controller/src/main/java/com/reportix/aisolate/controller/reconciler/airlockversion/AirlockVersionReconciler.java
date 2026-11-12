package com.reportix.aisolate.controller.reconciler.airlockversion;

import com.reportix.aisolate.config.Names;
import com.reportix.aisolate.controller.api.v1.Airlock;
import com.reportix.aisolate.controller.api.v1.AirlockVersion;
import com.reportix.aisolate.controller.api.v1.Global;
import com.reportix.aisolate.controller.api.v1.ResourceStatus;
import com.reportix.aisolate.controller.api.v1.Space;
import com.reportix.aisolate.controller.reconciler.ReleaseEvents;
import com.reportix.aisolate.controller.reconciler.Releases;
import com.reportix.aisolate.controller.release.Release;
import com.reportix.aisolate.controller.render.Owners;
import com.reportix.aisolate.controller.render.Renderer;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ErrorStatusUpdateControl;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceContext;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.javaoperatorsdk.operator.processing.event.source.EventSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;

/**
 * One airlock version: its pod pair (airlock pod with its Service, Ingress rule and NetworkPolicy;
 * MCP server with its Service and NetworkPolicy) and its ConfigMap, from its space's release loaded
 * fresh. Whatever it owns and the render no longer produces goes, and a version whose Airlock or
 * Space does not exist runs nothing. A version its release leaves out (invalid itself, or its
 * Airlock or Space) fails closed: everything it owns goes and it says why. Its pods would otherwise
 * keep the last good Space and Airlock, so a role removed since would still be admitted. A
 * sibling's problem never holds it back.
 */
@RequiredArgsConstructor
public class AirlockVersionReconciler implements Reconciler<AirlockVersion> {

  private static final String KIND = "AirlockVersion";

  private final Releases releases;

  @Override
  public UpdateControl<AirlockVersion> reconcile(
      AirlockVersion version, Context<AirlockVersion> context) {
    return Releases.patchIfChanged(version, serve(version));
  }

  private ResourceStatus serve(AirlockVersion version) {
    String name = version.getMetadata().getName();
    Long generation = version.getMetadata().getGeneration();
    Releases.Snapshot snapshot = releases.read();
    String named = version.getSpec() == null ? null : version.getSpec().airlock();
    Optional<String> airlock = snapshot.findAirlockOf(name);
    Optional<String> space = airlock.flatMap(snapshot::findSpaceOf);
    if (space.isEmpty()) {
      releases.deleteOwnedExcept(KIND, name, airlock.orElse(named), List.of());
      return ResourceStatus.orphaned(
          generation,
          airlock
              .map(a -> "the Space of its Airlock " + a + " does not exist")
              .orElse(
                  named == null
                      ? "spec.airlock is required"
                      : "its Airlock " + named + " does not exist"));
    }
    Release release = releases.load(snapshot, space.get());
    if (!release.serves(KIND, name)) {
      releases.deleteOwnedExcept(KIND, name, airlock.get(), List.of());
      List<String> problems = new ArrayList<>(release.problemsOf(KIND, name));
      problems.add(Releases.HELD_BACK);
      return ResourceStatus.invalid(generation, problems);
    }
    List<HasMetadata> objects = releases.render(release, name, Owners.of(List.of(version)));
    releases.apply(objects);
    releases.deleteOwnedExcept(KIND, name, airlock.get(), objects);
    String revision = Renderer.selectServedDocuments(release.configuration(), name).revision();
    // Ready once both pods serve; the count is the airlock pods ready
    boolean complete = true;
    int ready = 0;
    for (HasMetadata object : objects) {
      if (!(object instanceof Deployment)) continue;
      Releases.Rollout rollout = releases.readRollout(object.getMetadata().getName());
      complete &= rollout.complete();
      if (Names.AIRLOCK_ROLE.equals(object.getMetadata().getLabels().get(Renderer.ROLE))
          && rollout.readyReplicas() != null) {
        ready += rollout.readyReplicas();
      }
    }
    return complete
        ? ResourceStatus.ready(revision, generation, ready)
        : ResourceStatus.pending(revision, generation, ready);
  }

  @Override
  public ErrorStatusUpdateControl<AirlockVersion> updateErrorStatus(
      AirlockVersion version, Context<AirlockVersion> context, Exception e) {
    return Releases.patchApplyFailed(version, e);
  }

  /**
   * The Global changes every release; a Space changes its own release, and the versions hold no map
   * of airlocks to spaces, so every version hears of it; an Airlock reaches its versions; a
   * Deployment's status changes its owner's readiness.
   */
  @Override
  public List<EventSource<?, AirlockVersion>> prepareEventSources(
      EventSourceContext<AirlockVersion> context) {
    return List.of(
        ReleaseEvents.all(Global.class, context),
        ReleaseEvents.all(Space.class, context),
        ReleaseEvents.keyed(
            Airlock.class, a -> a.getMetadata().getName(), ReleaseEvents::airlockNamedBy, context),
        ReleaseEvents.owned(Deployment.class, context));
  }
}
