package com.reportix.aisolate.controller.reconciler;

import com.reportix.aisolate.controller.api.v1.Airlock;
import com.reportix.aisolate.controller.api.v1.AirlockVersion;
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
 * maps to the airlocks of that space and to the versions of its airlock (and a Space to the Spaces,
 * which discovery lists together); a Global changes every release, so it maps to every primary; a
 * Deployment maps to the AirlockVersion that owns it.
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
            .withSecondaryToPrimaryMapper(resource ->
                context
                    .getPrimaryCache()
                    .list()
                    .map(ResourceID::fromResource)
                    .collect(Collectors.toSet()))
            .withOnUpdateFilter((newer, older) -> generationChanged(older, newer))
            .build());
  }

  /**
   * A change of {@code kind} triggers the primaries keyed by what it names: the airlocks of the
   * space it names, the versions of the airlock it names; the release of that space is all it can
   * change. A primary that moves maps through its old and its new self, so the siblings of both
   * hear of it.
   */
  public static <R extends HasMetadata, P extends HasMetadata> EventSource<R, P> keyed(
      Class<R> kind,
      Function<R, String> keyNamed,
      Function<P, String> keyOf,
      EventSourceContext<P> context) {
    return new InformerEventSource<>(
        configureKeyed(
            kind,
            context.getPrimaryResourceClass(),
            keyNamed,
            keyOf,
            () -> context.getPrimaryCache().list()));
  }

  /** The primaries come from {@code primaries}: the cache, or a list in a test. */
  static <R extends HasMetadata, P extends HasMetadata>
      InformerEventSourceConfiguration<R> configureKeyed(
          Class<R> kind,
          Class<P> primary,
          Function<R, String> keyNamed,
          Function<P, String> keyOf,
          Supplier<Stream<P>> primaries) {
    return InformerEventSourceConfiguration.from(kind, primary)
        .withSecondaryToPrimaryMapper(
            resource -> keyedBy(keyNamed.apply(resource), keyOf, primaries.get()))
        .withOnUpdateFilter((newer, older) -> generationChanged(older, newer))
        .build();
  }

  /** The ids of the primaries keyed {@code key}; none when nothing is named. */
  static <P extends HasMetadata> Set<ResourceID> keyedBy(
      String key, Function<P, String> keyOf, Stream<P> primaries) {
    if (key == null) return Set.of();
    return primaries
        .filter(p -> key.equals(keyOf.apply(p)))
        .map(ResourceID::fromResource)
        .collect(Collectors.toSet());
  }

  /**
   * A change of {@code kind} triggers the one primary it names, in the namespace it changed in; the
   * other primaries have no business with it.
   */
  public static <R extends HasMetadata, P extends HasMetadata> EventSource<R, P> oneNamed(
      Class<R> kind, Class<P> primary, Function<R, String> named) {
    return new InformerEventSource<>(configureOneNamed(kind, primary, named));
  }

  static <R extends HasMetadata, P extends HasMetadata>
      InformerEventSourceConfiguration<R> configureOneNamed(
          Class<R> kind, Class<P> primary, Function<R, String> named) {
    return InformerEventSourceConfiguration.from(kind, primary)
        .withSecondaryToPrimaryMapper(resource -> {
          String name = named.apply(resource);
          return name == null
              ? Set.of()
              : Set.of(new ResourceID(name, resource.getMetadata().getNamespace()));
        })
        .withOnUpdateFilter((newer, older) -> generationChanged(older, newer))
        .build();
  }

  /** The Space {@code airlock} names; null while it has no spec or names none. */
  public static String spaceNamedBy(Airlock airlock) {
    return airlock.getSpec() == null ? null : airlock.getSpec().space();
  }

  /** The Airlock {@code version} names; null while it has no spec or names none. */
  public static String airlockNamedBy(AirlockVersion version) {
    return version.getSpec() == null ? null : version.getSpec().airlock();
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
