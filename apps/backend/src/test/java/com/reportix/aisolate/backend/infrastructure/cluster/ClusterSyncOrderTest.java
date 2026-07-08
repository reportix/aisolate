package com.reportix.aisolate.backend.infrastructure.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.reportix.aisolate.backend.domain.airlock.AirlockRepository;
import com.reportix.aisolate.backend.domain.airlock.Documents;
import com.reportix.aisolate.backend.domain.deploy.DeployResultDTO.Cluster;
import com.reportix.aisolate.backend.domain.global.GlobalRepository;
import com.reportix.aisolate.backend.domain.space.Space;
import com.reportix.aisolate.backend.domain.space.SpaceFormDTO;
import com.reportix.aisolate.backend.domain.space.SpaceRepository;
import com.reportix.aisolate.config.GlobalDocument;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

@EnableKubernetesMockClient(crud = true)
class ClusterSyncOrderTest {

  static KubernetesClient client; // injected by the mock server extension
  static KubernetesMockServer server;

  private final SpaceRepository spaces = mock(SpaceRepository.class);
  private final AirlockRepository airlocks = mock(AirlockRepository.class);
  private final GlobalRepository globals = mock(GlobalRepository.class);
  private final List<Object> published = new ArrayList<>();

  private final ClusterSyncService sync =
      new ClusterSyncService(
          new ClusterProperties("aisolate"),
          new StaticListableBeanFactory(Map.of("client", client))
              .getBeanProvider(KubernetesClient.class),
          spaces,
          airlocks,
          globals,
          published::add);

  private static Space space(String... users) {
    return new Space(
        "hr-acme",
        "HR Acme",
        "",
        Stream.of(users).map(u -> new SpaceFormDTO.Member(u, SpaceFormDTO.USER)).toList(),
        List.of(),
        List.of(),
        "jasmine",
        null,
        null,
        null,
        null);
  }

  @Test
  void givenASaveCommitsWhileAnOlderSyncRuns_whenBothSyncsEnd_thenTheClusterHoldsTheLatest()
      throws InterruptedException {
    // Arrange: the first sync has read eric when a save removing him commits and its sync starts.
    // Unserialized, that second sync applies first and the first one's stale read lands last.
    AtomicReference<Space> committed =
        new AtomicReference<>(space("nora@example.com", "eric@example.com"));
    when(spaces.require("hr-acme")).thenAnswer(call -> committed.get());
    when(globals.document()).thenReturn(GlobalDocument.empty());
    AtomicInteger reads = new AtomicInteger();
    CountDownLatch secondRead = new CountDownLatch(1);
    AtomicReference<Thread> second = new AtomicReference<>();
    when(airlocks.list("hr-acme"))
        .thenAnswer(
            call -> {
              if (reads.getAndIncrement() == 0) {
                committed.set(space("nora@example.com"));
                second.set(Thread.startVirtualThread(() -> sync.sync("hr-acme")));
                // Serialized, the second sync cannot read until this one is done: go on alone.
                if (secondRead.await(200, TimeUnit.MILLISECONDS)) {
                  second.get().join();
                }
              } else {
                secondRead.countDown();
              }
              return List.of();
            });

    // Act
    Cluster first = sync.sync("hr-acme");
    second.get().join();

    // Assert
    assertThat(first.applied()).isTrue();
    assertThat(
            client
                .genericKubernetesResources(ClusterSyncService.SPACES)
                .inNamespace("aisolate")
                .withName("hr-acme")
                .get()
                .<Object>get("spec", "access", "users"))
        .isEqualTo(List.of("nora@example.com"));
  }

  /** The resource as the cluster holds it, created or updated: with its uid. */
  private static GenericKubernetesResource stored(ResourceDefinitionContext kind, String name) {
    GenericKubernetesResource resource = new GenericKubernetesResource();
    resource.setApiVersion("aisolate.dev/v1");
    resource.setKind(kind.getKind());
    resource.setMetadata(new ObjectMetaBuilder().withName(name).withNamespace("aisolate").build());
    return client
        .genericKubernetesResources(kind)
        .inNamespace("aisolate")
        .resource(resource)
        .createOr(op -> op.update());
  }

  @Test
  void givenNoGlobal_whenFoundOrCreated_thenCreatedAndNothingPublished() {
    // Arrange: the install's Global, absent
    client
        .genericKubernetesResources(ClusterSyncService.GLOBALS)
        .inNamespace("aisolate")
        .withName(GlobalDocument.NAME)
        .delete();

    // Act
    GenericKubernetesResource created = sync.findOrCreate(GlobalDocument.empty());

    // Assert: a rollback never takes the Global
    assertThat(created.getMetadata().getUid()).isNotBlank();
    assertThat(published).isEmpty();
  }

  @Test
  void givenExistingSpace_whenFoundOrCreated_thenAdoptedAndNothingPublished() {
    // Arrange
    String uid = stored(ClusterSyncService.SPACES, "hr-acme").getMetadata().getUid();

    // Act
    GenericKubernetesResource found = sync.findOrCreate(Documents.toSpace(space()));

    // Assert: another save's, or one already committed
    assertThat(found.getMetadata().getUid()).isEqualTo(uid);
    assertThat(published).isEmpty();
  }

  @Test
  void givenSpaceCreatedBetweenReadAndCreate_whenFoundOrCreated_thenAdoptedAndNothingPublished() {
    // Arrange: a sync's create lands after the read: the read misses it, the create meets a 409
    String uid = stored(ClusterSyncService.SPACES, "hr-acme").getMetadata().getUid();
    server
        .expect()
        .get()
        .withPath("/apis/aisolate.dev/v1/namespaces/aisolate/spaces/hr-acme")
        .andReturn(404, "{}")
        .once();

    // Act
    GenericKubernetesResource found = sync.findOrCreate(Documents.toSpace(space()));

    // Assert
    assertThat(found.getMetadata().getUid()).isEqualTo(uid);
    assertThat(published).isEmpty();
  }

  @Test
  void givenResourceCreated_whenRolledBackWithAnotherUid_thenKept() {
    // Arrange: the rolled-back save's Airlock is gone and another save made it again
    String uid = stored(ClusterSyncService.AIRLOCKS, "hr-assistant").getMetadata().getUid();

    // Act
    sync.handleResourceCreated(
        new ResourceCreated(ClusterSyncService.AIRLOCKS, "hr-assistant", "the-rolled-back-uid"));

    // Assert
    assertThat(
            client
                .genericKubernetesResources(ClusterSyncService.AIRLOCKS)
                .inNamespace("aisolate")
                .withName("hr-assistant")
                .get())
        .extracting(kept -> kept.getMetadata().getUid())
        .isEqualTo(uid);
  }
}
