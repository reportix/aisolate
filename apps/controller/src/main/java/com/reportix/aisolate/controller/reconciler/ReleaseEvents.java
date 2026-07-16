package com.reportix.aisolate.controller.reconciler;

import com.reportix.aisolate.controller.api.v1.Airlock;
import com.reportix.aisolate.controller.api.v1.Space;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.javaoperatorsdk.operator.api.config.informer.InformerEventSourceConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceContext;
import io.javaoperatorsdk.operator.processing.event.ResourceID;
import io.javaoperatorsdk.operator.processing.event.source.EventSource;
import io.javaoperatorsdk.operator.processing.event.source.informer.InformerEventSource;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * How a change reaches the reconcilers: a document can change the release of its own space, so it
 * maps to the airlocks of that space (and a Space to the Spaces, which discovery lists together); a
 * Global changes every release, so it maps to every primary; a Deployment maps to the Airlock that
 * owns it.
 */
public final class ReleaseEvents {

  /**
   * A status patch bumps no generation: only a document change is a new revision of the release.
   */
  static <R extends HasMetadata> boolean generationChanged(R before, R after) {
    Long b = before.getMetadata().getGeneration();
    Long a = after.getMetadata().getGeneration();
    return b == null || a == null || !b.equals(a);
  }

  /** Every document change of {@code kind} triggers every primary in the cache. */
  public static <R extends HasMetadata, P extends HasMetadata> EventSource<R, P> all(
      Class<R> kind, EventSourceContext<P> context) {
    return new InformerEventSource<>(
        InformerEventSourceConfiguration.from(kind, context.getPrimaryResourceClass())
            .withSecondaryToPrimaryMapper(
                resource ->
                    context
                        .getPrimaryCache()
                        .list()
                        .map(ResourceID::fromResource)
                        .collect(Collectors.toSet()))
            .withOnUpdateFilter((newer, older) -> generationChanged(older, newer))
            .build());
  }

  /**
   * A change of {@code kind} triggers the airlocks of the space it names: the release of that space
   * is all it can change. An Airlock that moves space maps through its old and its new self, so the
   * siblings of both spaces hear of it.
   */
  public static <R extends HasMetadata> EventSource<R, Airlock> airlocksOfSpace(
      Class<R> kind, Function<R, String> spaceNamed, EventSourceContext<Airlock> context) {
    return new InformerEventSource<>(
        configureAirlocksOfSpace(kind, spaceNamed, () -> context.getPrimaryCache().list()));
  }

  /** The primaries come from {@code airlocks}: the cache, or a list in a test. */
  static <R extends HasMetadata> InformerEventSourceConfiguration<R> configureAirlocksOfSpace(
      Class<R> kind, Function<R, String> spaceNamed, Supplier<Stream<Airlock>> airlocks) {
    return InformerEventSourceConfiguration.from(kind, Airlock.class)
        .withSecondaryToPrimaryMapper(
            resource -> airlocksOf(spaceNamed.apply(resource), airlocks.get()))
        .withOnUpdateFilter((newer, older) -> generationChanged(older, newer))
        .build();
  }

  /** The ids of the airlocks naming {@code space}; none when it names none. */
  static Set<ResourceID> airlocksOf(String space, Stream<Airlock> airlocks) {
    if (space == null) return Set.of();
    return airlocks
        .filter(a -> space.equals(spaceNamedBy(a)))
        .map(ResourceID::fromResource)
        .collect(Collectors.toSet());
  }

  /**
   * A change of {@code kind} triggers the one Space it names, in the namespace it changed in; the
   * other Spaces have no business with it.
   */
  public static <R extends HasMetadata> EventSource<R, Space> spaceOf(
      Class<R> kind, Function<R, String> spaceNamed) {
    return new InformerEventSource<>(configureSpaceOf(kind, spaceNamed));
  }

  static <R extends HasMetadata> InformerEventSourceConfiguration<R> configureSpaceOf(
      Class<R> kind, Function<R, String> spaceNamed) {
    return InformerEventSourceConfiguration.from(kind, Space.class)
        .withSecondaryToPrimaryMapper(
            resource -> {
              String space = spaceNamed.apply(resource);
              return space == null
                  ? Set.of()
                  : Set.of(new ResourceID(space, resource.getMetadata().getNamespace()));
            })
        .withOnUpdateFilter((newer, older) -> generationChanged(older, newer))
        .build();
  }

  /** The Space {@code airlock} names; null while it has no spec or names none. */
  public static String spaceNamedBy(Airlock airlock) {
    return airlock.getSpec() == null ? null : airlock.getSpec().space();
  }

  /** An owned object triggers its owner; every update counts, so its status changes too. */
  public static <R extends HasMetadata, P extends HasMetadata> EventSource<R, P> owned(
      Class<R> kind, EventSourceContext<P> context) {
    return new InformerEventSource<>(configureOwned(kind, context.getPrimaryResourceClass()));
  }

  /** The default mapper follows the owner references of the primary's kind; no update filter. */
  static <R extends HasMetadata> InformerEventSourceConfiguration<R> configureOwned(
      Class<R> kind, Class<? extends HasMetadata> primary) {
    return InformerEventSourceConfiguration.from(kind, primary).build();
  }

  private ReleaseEvents() {}
}
