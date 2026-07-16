package com.reportix.aisolate.controller.api.v1;

import io.fabric8.kubernetes.api.model.Condition;
import io.fabric8.kubernetes.api.model.ConditionBuilder;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * What the controller reports after a pass: {@code Pending} until the pods are up or while nothing
 * of it runs (its Space does not exist, no deploy yet), {@code Ready} (a Space naming any airlock
 * of it that is held back), or {@code Invalid} with the release's problems that concern this
 * resource; the same as one Kubernetes {@code Ready} condition, for tools that read conditions.
 */
public record ResourceStatus(
    String phase,
    String revision,
    Long observedGeneration,
    List<String> problems,
    Integer readyReplicas,
    List<Condition> conditions) {

  public static final String PENDING = "Pending";
  public static final String READY = "Ready";
  public static final String INVALID = "Invalid";

  public ResourceStatus {
    problems = problems == null ? List.of() : List.copyOf(problems);
    conditions = conditions == null ? List.of() : List.copyOf(conditions);
  }

  public static ResourceStatus ready(String revision, Long generation, Integer readyReplicas) {
    return ready(revision, generation, readyReplicas, List.of());
  }

  /**
   * @param heldBack what it serves but one of its airlocks does not: that airlock serves no one
   */
  public static ResourceStatus ready(
      String revision, Long generation, Integer readyReplicas, List<String> heldBack) {
    return new ResourceStatus(
        READY,
        revision,
        generation,
        heldBack,
        readyReplicas,
        List.of(condition("True", READY, heldBack.isEmpty() ? "" : heldBack.getFirst())));
  }

  public static ResourceStatus pending(String revision, Long generation, Integer readyReplicas) {
    return new ResourceStatus(
        PENDING,
        revision,
        generation,
        List.of(),
        readyReplicas,
        List.of(condition("False", PENDING, "waiting for the pods")));
  }

  /**
   * Not an error: its Space does not exist (yet, or any more), or no deploy has brought its model
   * yet, so nothing of it runs.
   */
  public static ResourceStatus runsNothing(Long generation, String problem) {
    return new ResourceStatus(
        PENDING,
        null,
        generation,
        List.of(problem),
        null,
        List.of(condition("False", PENDING, problem)));
  }

  public static ResourceStatus invalid(Long generation, List<String> problems) {
    return new ResourceStatus(
        INVALID,
        null,
        generation,
        problems,
        null,
        List.of(condition("False", INVALID, problems.isEmpty() ? "" : problems.getFirst())));
  }

  /** Same phase, revision and problems: the condition's timestamp does not make a new status. */
  public boolean sameAs(ResourceStatus other) {
    return other != null
        && phase.equals(other.phase)
        && Objects.equals(revision, other.revision)
        && Objects.equals(observedGeneration, other.observedGeneration)
        && problems.equals(other.problems)
        && Objects.equals(readyReplicas, other.readyReplicas);
  }

  private static Condition condition(String status, String reason, String message) {
    return new ConditionBuilder()
        .withType(READY)
        .withStatus(status)
        .withReason(reason)
        .withMessage(message)
        .withLastTransitionTime(Instant.now().toString())
        .build();
  }
}
