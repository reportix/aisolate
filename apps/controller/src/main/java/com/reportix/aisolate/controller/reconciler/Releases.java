package com.reportix.aisolate.controller.reconciler;

import com.reportix.aisolate.config.Configuration;
import com.reportix.aisolate.config.GlobalDocument;
import com.reportix.aisolate.config.Metadata;
import com.reportix.aisolate.controller.api.v1.Airlock;
import com.reportix.aisolate.controller.api.v1.Global;
import com.reportix.aisolate.controller.api.v1.ResourceStatus;
import com.reportix.aisolate.controller.api.v1.Space;
import com.reportix.aisolate.controller.release.Release;
import com.reportix.aisolate.controller.render.ControllerProperties;
import com.reportix.aisolate.controller.render.Owners;
import com.reportix.aisolate.controller.render.Renderer;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentStatus;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.NonDeletingOperation;
import io.javaoperatorsdk.operator.api.reconciler.ErrorStatusUpdateControl;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * What every reconciler shares: a space as one release, the render, create-or-apply, the prune.
 * Each pass reads the API (not a cache) so the three kinds are seen together.
 */
@Slf4j
@RequiredArgsConstructor
public class Releases {

  public static final String FIELD_MANAGER = "aisolate-controller";

  /** The kinds a render produces: what a pass deletes once the render stops producing it. */
  private static final List<Class<? extends HasMetadata>> RENDERED_KINDS =
      List.of(
          ConfigMap.class,
          Secret.class,
          Deployment.class,
          Service.class,
          Ingress.class,
          NetworkPolicy.class);

  /** Writes one object: creates it when missing, brings it to the desired state otherwise. */
  public interface Applier {
    HasMetadata apply(NonDeletingOperation<HasMetadata> object);
  }

  /**
   * Server-side apply, this controller as the field manager, conflicts forced: it owns these. The
   * apply creates a missing object too: a plain create would leave every field to the client's own
   * manager, and a field the renderer stops emitting would then never leave the object.
   */
  public static final Applier BY_SERVER_SIDE_APPLY =
      object -> object.fieldManager(FIELD_MANAGER).forceConflicts().serverSideApply();

  /** Create, else update: the CRUD mock server of the tests speaks no apply-patch. */
  public static final Applier BY_UPDATE = object -> object.createOr(NonDeletingOperation::update);

  private final KubernetesClient client;
  private final Renderer renderer;
  private final String namespace;
  private final Applier applier;
  private final ControllerProperties settings;

  /**
   * The Global and every Space and Airlock of the namespace, listed in one pass; an install without
   * a Global reads as an empty one.
   */
  public record Snapshot(GlobalDocument global, List<Space> spaces, List<Airlock> airlocks) {

    /** The Space {@code airlock} names; empty while either does not exist. */
    public Optional<String> findSpaceOf(String airlock) {
      return airlocks.stream()
          .filter(a -> name(a).equals(airlock) && a.getSpec() != null)
          .map(a -> a.getSpec().space())
          .filter(space -> spaces.stream().anyMatch(s -> name(s).equals(space)))
          .findFirst();
    }
  }

  // Every pass lists the namespace and re-validates; cache by resourceVersion if the
  // airlocks grow many
  public Snapshot read() {
    Global global =
        client.resources(Global.class).inNamespace(namespace).withName(GlobalDocument.NAME).get();
    return new Snapshot(
        global == null
            ? GlobalDocument.empty()
            : new GlobalDocument(new Metadata(GlobalDocument.NAME), global.getSpec()),
        client.resources(Space.class).inNamespace(namespace).list().getItems(),
        client.resources(Airlock.class).inNamespace(namespace).list().getItems());
  }

  /** One space's release: a broken space elsewhere leaves it alone. */
  public Release load(Snapshot snapshot, String space) {
    return Release.of(
        space,
        snapshot.global(),
        snapshot.spaces(),
        snapshot.airlocks(),
        settings.egress(),
        settings.otlpEndpoint());
  }

  /** What discovery lists to a chat client, every space whose release loads, and its pods. */
  public List<HasMetadata> renderDiscovery(Snapshot snapshot) {
    List<Configuration> valid = new ArrayList<>();
    for (Space space : snapshot.spaces()) {
      Release release = load(snapshot, space.getMetadata().getName());
      if (release.valid()) {
        valid.add(release.configuration());
      }
    }
    // Not the controller of it: several owners, and garbage collection waits for the last one.
    List<OwnerReference> owners =
        snapshot.spaces().stream()
            .map(
                space ->
                    new OwnerReferenceBuilder()
                        .withApiVersion(space.getApiVersion())
                        .withKind(space.getKind())
                        .withName(name(space))
                        .withUid(space.getMetadata().getUid())
                        .build())
            .toList();
    return renderer.renderDiscovery(valid, owners);
  }

  /**
   * The airlocks of {@code space} the release leaves out, each told: such an airlock serves no one
   * until it is valid again.
   */
  public static List<String> heldBack(Snapshot snapshot, Release release, String space) {
    return snapshot.airlocks().stream()
        .filter(a -> a.getSpec() != null && space.equals(a.getSpec().space()))
        .map(Releases::name)
        .filter(a -> !release.serves("Airlock", a))
        .sorted()
        .map(a -> "Airlock " + a + " is held back: " + HELD_BACK)
        .toList();
  }

  /** Why a held-back airlock runs nothing: its pods hold access lists the documents may narrow. */
  public static final String HELD_BACK = "it serves no one until it is valid again";

  public List<HasMetadata> render(Release release, String airlock, Owners owners) {
    return renderer.render(release.configuration(), airlock, owners);
  }

  public void apply(List<HasMetadata> objects) {
    for (HasMetadata object : objects) {
      applier.apply(client.resource(object).inNamespace(namespace));
    }
  }

  /**
   * Deletes what the controller rendered for the Airlock {@code name} and {@code keep} does not
   * name: an earlier render's objects. Each of the six lists asks the API for that airlock's
   * objects alone (the controller's label and the airlock's), not for the namespace's; the owner
   * reference then confirms whose they are. The backend's Secrets carry no managed-by label, so
   * they are never listed. {@code kind} is {@code Airlock}: the label names an airlock.
   */
  public void deleteOwnedExcept(String kind, String name, List<HasMetadata> keep) {
    Set<String> kept =
        keep.stream()
            .map(o -> o.getKind() + "/" + o.getMetadata().getName())
            .collect(Collectors.toSet());
    for (Class<? extends HasMetadata> type : RENDERED_KINDS) {
      for (HasMetadata object :
          client
              .resources(type)
              .inNamespace(namespace)
              .withLabel(Renderer.MANAGED_BY, "controller")
              .withLabel(Renderer.AIRLOCK, name)
              .list()
              .getItems()) {
        boolean owned =
            object.getMetadata().getOwnerReferences().stream()
                .anyMatch(owner -> kind.equals(owner.getKind()) && name.equals(owner.getName()));
        if (owned && !kept.contains(object.getKind() + "/" + object.getMetadata().getName())) {
          client.resource(object).delete();
          log.info(
              "deleted {} {}: {} {} no longer renders it",
              object.getKind(),
              object.getMetadata().getName(),
              kind,
              name);
        }
      }
    }
  }

  /** The new template's pods serving: not one pod of the old template still ready meanwhile. */
  public record Rollout(Integer readyReplicas, boolean complete) {}

  public Rollout readRollout(String deployment) {
    Deployment d = client.apps().deployments().inNamespace(namespace).withName(deployment).get();
    if (d == null || d.getStatus() == null) {
      return new Rollout(null, false);
    }
    DeploymentStatus status = d.getStatus();
    Long generation = d.getMetadata().getGeneration();
    int replicas = d.getSpec().getReplicas() == null ? 1 : d.getSpec().getReplicas();
    boolean observed =
        generation == null
            || (status.getObservedGeneration() != null
                && status.getObservedGeneration() >= generation);
    boolean complete =
        observed
            && Objects.equals(status.getUpdatedReplicas(), replicas)
            && Objects.equals(status.getReadyReplicas(), replicas)
            && (status.getUnavailableReplicas() == null || status.getUnavailableReplicas() == 0);
    return new Rollout(status.getReadyReplicas(), complete);
  }

  private static String name(HasMetadata resource) {
    return resource.getMetadata().getName();
  }

  /** The status patched only when it changed. */
  public static <R extends CustomResource<?, ResourceStatus>> UpdateControl<R> patchIfChanged(
      R resource, ResourceStatus status) {
    if (status.sameAs(resource.getStatus())) {
      return UpdateControl.noUpdate();
    }
    resource.setStatus(status);
    return UpdateControl.patchStatus(resource);
  }

  /** An apply the API server refused: said on the resource, not hidden behind the last good one. */
  public static <R extends CustomResource<?, ResourceStatus>>
      ErrorStatusUpdateControl<R> patchApplyFailed(R resource, Exception e) {
    resource.setStatus(
        ResourceStatus.invalid(
            resource.getMetadata().getGeneration(), List.of("apply failed: " + extractMessage(e))));
    return ErrorStatusUpdateControl.patchStatus(resource);
  }

  /** The API server's own words when it refused, else the exception's. */
  public static String extractMessage(Exception e) {
    if (e instanceof KubernetesClientException k
        && k.getStatus() != null
        && k.getStatus().getMessage() != null) {
      return k.getStatus().getMessage();
    }
    return String.valueOf(e.getMessage());
  }
}
