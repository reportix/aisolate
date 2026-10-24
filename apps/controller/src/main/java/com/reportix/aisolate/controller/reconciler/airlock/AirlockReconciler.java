package com.reportix.aisolate.controller.reconciler.airlock;

import com.reportix.aisolate.config.AirlockDocument;
import com.reportix.aisolate.config.Names;
import com.reportix.aisolate.controller.api.v1.Airlock;
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
 * One airlock: its pod pair (airlock pod with its Service, Ingress rule and NetworkPolicy; MCP
 * server with its Service and NetworkPolicy) and its ConfigMap, from its space's release loaded
 * fresh. Whatever it owns and the render no longer produces goes, and an airlock whose Space does
 * not exist, or that no deploy has brought a model and a prompt yet, runs nothing. An airlock its
 * release leaves out (invalid itself, or its Space) fails closed: everything it owns goes and it
 * says why. Its pods would otherwise keep the last good Space, so a role removed since would still
 * be admitted. A sibling's problem never holds it back.
 */
@RequiredArgsConstructor
public class AirlockReconciler implements Reconciler<Airlock> {

  private static final String KIND = "Airlock";

  private final Releases releases;

  @Override
  public UpdateControl<Airlock> reconcile(Airlock airlock, Context<Airlock> context) {
    return Releases.patchIfChanged(airlock, serve(airlock));
  }

  private ResourceStatus serve(Airlock airlock) {
    String name = airlock.getMetadata().getName();
    Long generation = airlock.getMetadata().getGeneration();
    Releases.Snapshot snapshot = releases.read();
    Optional<String> space = snapshot.findSpaceOf(name);
    if (space.isEmpty()) {
      releases.deleteOwnedExcept(KIND, name, List.of());
      String named = airlock.getSpec() == null ? null : airlock.getSpec().space();
      return ResourceStatus.runsNothing(
          generation,
          named == null ? "spec.space is required" : "its Space " + named + " does not exist");
    }
    Release release = releases.load(snapshot, space.get());
    if (!release.serves(KIND, name)) {
      releases.deleteOwnedExcept(KIND, name, List.of());
      List<String> problems = new ArrayList<>(release.problemsOf(KIND, name));
      problems.add(Releases.HELD_BACK);
      return ResourceStatus.invalid(generation, problems);
    }
    String missing = missing(release.configuration().airlocks().get(name));
    if (missing != null) {
      releases.deleteOwnedExcept(KIND, name, List.of());
      return ResourceStatus.runsNothing(generation, missing);
    }
    List<HasMetadata> objects = releases.render(release, name, Owners.of(List.of(airlock)));
    releases.apply(objects);
    releases.deleteOwnedExcept(KIND, name, objects);
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

  /** Why the airlock runs nothing yet, or null: a deploy brings its model and its prompt. */
  static String missing(AirlockDocument airlock) {
    if (blank(airlock.spec().llm())) return "no llm: aisolate deploy brings it";
    if (blank(airlock.spec().prompt())) return "no prompt: aisolate deploy brings it";
    return null;
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  @Override
  public ErrorStatusUpdateControl<Airlock> updateErrorStatus(
      Airlock airlock, Context<Airlock> context, Exception e) {
    return Releases.patchApplyFailed(airlock, e);
  }

  /**
   * The Global changes every release; a Space or an Airlock changes the release of its own space,
   * so it reaches that space's airlocks; a Deployment's status changes its owner's readiness.
   */
  @Override
  public List<EventSource<?, Airlock>> prepareEventSources(EventSourceContext<Airlock> context) {
    return List.of(
        ReleaseEvents.all(Global.class, context),
        ReleaseEvents.airlocksOfSpace(Space.class, s -> s.getMetadata().getName(), context),
        ReleaseEvents.airlocksOfSpace(Airlock.class, ReleaseEvents::spaceNamedBy, context),
        ReleaseEvents.owned(Deployment.class, context));
  }
}
