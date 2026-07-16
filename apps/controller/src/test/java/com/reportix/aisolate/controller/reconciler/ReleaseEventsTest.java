package com.reportix.aisolate.controller.reconciler;

import static org.assertj.core.api.Assertions.assertThat;

import com.reportix.aisolate.config.AirlockDocument;
import com.reportix.aisolate.config.SpaceDocument;
import com.reportix.aisolate.controller.api.v1.Airlock;
import com.reportix.aisolate.controller.api.v1.Space;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.javaoperatorsdk.operator.api.config.informer.InformerEventSourceConfiguration;
import io.javaoperatorsdk.operator.processing.event.ResourceID;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * A status patch bumps no generation; a Deployment's status must still reach its airlock. A
 * document change reaches the airlocks of its own space, not every airlock.
 */
class ReleaseEventsTest {

  private static final String NS = "aisolate";

  private static final List<Airlock> AIRLOCKS =
      List.of(
          airlockIn("hr-assistant", "hr"),
          airlockIn("hr-payroll", "hr"),
          airlockIn("fin-reports", "fin"),
          airlockWithoutSpec("draft"));

  @ParameterizedTest
  @CsvSource(
      value = {"1, 1, false", "1, 2, true", "null, 1, true"},
      nullValues = "null")
  void givenTwoStatesOfADocument_whenFiltered_thenOnlyAGenerationChangeIsAnEvent(
      Long before, Long after, boolean event) {
    // Arrange
    Airlock old = new Airlock();
    old.setMetadata(new ObjectMetaBuilder().withName("a").withGeneration(before).build());
    Airlock now = new Airlock();
    now.setMetadata(new ObjectMetaBuilder().withName("a").withGeneration(after).build());

    // Act
    boolean changed = ReleaseEvents.generationChanged(old, now);

    // Assert
    assertThat(changed).isEqualTo(event);
  }

  @Test
  void givenDeploymentOwnedByAnAirlock_whenMapped_thenItsAirlockAndNoUpdateFilter() {
    // Arrange
    Deployment deployment =
        new DeploymentBuilder()
            .withNewMetadata()
            .withName("hr-acme-hr-assistant-airlock")
            .withNamespace("aisolate")
            .addNewOwnerReference()
            .withApiVersion("aisolate.dev/v1")
            .withKind("Airlock")
            .withName("hr-assistant")
            .withUid("uid-hr")
            .endOwnerReference()
            .endMetadata()
            .build();

    // Act
    InformerEventSourceConfiguration<Deployment> owned =
        ReleaseEvents.configureOwned(Deployment.class, Airlock.class);

    // Assert: every update counts, a status change included
    assertThat(owned.getSecondaryToPrimaryMapper().toPrimaryResourceIDs(deployment))
        .containsExactly(new ResourceID("hr-assistant", "aisolate"));
    assertThat(owned.getInformerConfig().getOnUpdateFilter()).isNull();
  }

  @Test
  void givenSpaceChanged_whenMapped_thenOnlyItsAirlocks() {
    // Arrange
    InformerEventSourceConfiguration<Space> source =
        ReleaseEvents.configureAirlocksOfSpace(
            Space.class, s -> s.getMetadata().getName(), AIRLOCKS::stream);

    // Act
    var airlocks = source.getSecondaryToPrimaryMapper().toPrimaryResourceIDs(space("hr"));

    // Assert: the namespace travels with the id, the other spaces' airlocks are not woken
    assertThat(airlocks)
        .containsExactlyInAnyOrder(
            new ResourceID("hr-assistant", NS), new ResourceID("hr-payroll", NS));
  }

  @Test
  void givenAirlockWithoutSpec_whenMapped_thenNothing() {
    // Arrange
    InformerEventSourceConfiguration<Airlock> source =
        ReleaseEvents.configureAirlocksOfSpace(
            Airlock.class, ReleaseEvents::spaceNamedBy, AIRLOCKS::stream);
    InformerEventSourceConfiguration<Airlock> ofItsSpace =
        ReleaseEvents.configureSpaceOf(Airlock.class, ReleaseEvents::spaceNamedBy);

    // Act
    var airlocks =
        source.getSecondaryToPrimaryMapper().toPrimaryResourceIDs(airlockWithoutSpec("x"));
    var spaces =
        ofItsSpace.getSecondaryToPrimaryMapper().toPrimaryResourceIDs(airlockWithoutSpec("x"));

    // Assert: no space named, no primary; an airlock in the cache with no spec matches nothing
    assertThat(airlocks).isEmpty();
    assertThat(spaces).isEmpty();
    assertThat(ReleaseEvents.airlocksOf(null, AIRLOCKS.stream())).isEmpty();
    assertThat(ReleaseEvents.airlocksOf("hr", Stream.of(airlockWithoutSpec("draft")))).isEmpty();
  }

  @Test
  void givenAirlockChanged_whenMappedForSpaces_thenItsSpace() {
    // Arrange
    InformerEventSourceConfiguration<Airlock> source =
        ReleaseEvents.configureSpaceOf(Airlock.class, ReleaseEvents::spaceNamedBy);

    // Act
    var spaces =
        source.getSecondaryToPrimaryMapper().toPrimaryResourceIDs(airlockIn("hr-assistant", "hr"));

    // Assert
    assertThat(spaces).containsExactly(new ResourceID("hr", NS));
  }

  @Test
  void givenAnyMapper_whenOnlyStatusChanged_thenNoEvent() {
    // Arrange
    Space before = space("hr");
    before.getMetadata().setGeneration(3L);
    Space statusOnly = space("hr");
    statusOnly.getMetadata().setGeneration(3L);
    Space edited = space("hr");
    edited.getMetadata().setGeneration(4L);
    InformerEventSourceConfiguration<Space> airlocksOfSpace =
        ReleaseEvents.configureAirlocksOfSpace(
            Space.class, s -> s.getMetadata().getName(), AIRLOCKS::stream);
    InformerEventSourceConfiguration<Space> spaceOf =
        ReleaseEvents.configureSpaceOf(Space.class, s -> s.getMetadata().getName());

    // Act + Assert: like every document source, only a new generation is an event
    for (var source : List.of(airlocksOfSpace, spaceOf)) {
      assertThat(source.getInformerConfig().getOnUpdateFilter().accept(statusOnly, before))
          .isFalse();
      assertThat(source.getInformerConfig().getOnUpdateFilter().accept(edited, before)).isTrue();
    }
  }

  private static Space space(String name) {
    Space space = new Space();
    space.setMetadata(new ObjectMetaBuilder().withName(name).withNamespace(NS).build());
    space.setSpec(new SpaceDocument.Spec(null, null, null, null, null));
    return space;
  }

  private static Airlock airlockIn(String name, String space) {
    Airlock airlock = airlockWithoutSpec(name);
    airlock.setSpec(
        new AirlockDocument.Spec(
            space, null, null, null, null, null, null, null, null, null, null, null));
    return airlock;
  }

  private static Airlock airlockWithoutSpec(String name) {
    Airlock airlock = new Airlock();
    airlock.setMetadata(new ObjectMetaBuilder().withName(name).withNamespace(NS).build());
    return airlock;
  }
}
