package com.reportix.aisolate.backend.infrastructure.cluster;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.reportix.aisolate.backend.domain.airlock.AirlockRepository;
import com.reportix.aisolate.backend.domain.airlock.Documents;
import com.reportix.aisolate.backend.domain.deploy.DeployResultDTO.Cluster;
import com.reportix.aisolate.backend.domain.global.GlobalRepository;
import com.reportix.aisolate.backend.domain.space.Space;
import com.reportix.aisolate.backend.domain.space.SpaceRepository;
import com.reportix.aisolate.backend.domain.space.SpacesDeletedDTO;
import com.reportix.aisolate.config.AirlockDocument;
import com.reportix.aisolate.config.ConfigurationLoader;
import com.reportix.aisolate.config.Names;
import com.reportix.aisolate.config.SpaceDocument;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.NonDeletingOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import java.net.HttpURLConnection;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The cluster follows the database: after a write, the space's documents (the Global, the Space,
 * then its Airlocks), each created or replaced, and every space again now and then, so a failed
 * apply heals. An airlock's code stays here: its pods fetch it from the ingest port. Failures are
 * reported, never thrown: the database is the source of truth.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ClusterSyncService {

  static final ResourceDefinitionContext GLOBALS = buildContext("Global", "globals");
  static final ResourceDefinitionContext SPACES = buildContext("Space", "spaces");
  static final ResourceDefinitionContext AIRLOCKS = buildContext("Airlock", "airlocks");

  /** Under ingress-nginx's default 60 s read timeout, so the answer still reaches the caller. */
  public static final Duration PODS_PATIENCE = Duration.ofSeconds(45);

  private static final int CONFLICT_TRIES = 5;

  private static final Set<String> RENDERED_ROLES =
      Set.of(Names.AIRLOCK_ROLE, Names.TOOL_SERVER_ROLE);

  /** Each kind names its parent: the parent goes first. The Global has its own apply. */
  private static final List<Class<?>> APPLY_ORDER =
      List.of(SpaceDocument.class, AirlockDocument.class);

  private static final Map<String, ResourceDefinitionContext> CONTEXTS =
      Map.of("Global", GLOBALS, "Space", SPACES, "Airlock", AIRLOCKS);

  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  // The CRD's strings are plain: a null field must vanish, not become "null".
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .changeDefaultPropertyInclusion(
              inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_NULL))
          .build();

  private final ClusterProperties properties;
  private final ObjectProvider<KubernetesClient> client;
  private final SpaceRepository spaces;
  private final AirlockRepository airlocks;
  private final GlobalRepository globals;
  private final ApplicationEventPublisher events;
  private final Map<String, Object> spaceLocks = new ConcurrentHashMap<>();

  /** Every apply of the Global reads it under this lock, so the last one holds the latest. */
  private final Object globalLock = new Object();

  /** Syncs share it, a reset holds it alone: no sync applies rows it read before the reset. */
  private final ReadWriteLock resetLock = new ReentrantReadWriteLock();

  /**
   * After an AIsolate UI write committed: whether the cluster took the space, empty without one.
   * Why not is logged; the response only says so.
   */
  public Optional<Boolean> syncAfterWrite(String spaceId) {
    if (!properties.enabled()) {
      return Optional.empty();
    }
    Cluster outcome = sync(spaceId);
    if (!outcome.applied()) {
      log.warn("space {} not applied to the cluster: {}", spaceId, outcome.problem());
    }
    return Optional.of(outcome.applied());
  }

  /** After a write of a global LLM or data source: whether the cluster took the Global. */
  public Optional<Boolean> syncGlobalAfterWrite() {
    if (!properties.enabled()) {
      return Optional.empty();
    }
    resetLock.readLock().lock();
    try {
      applyGlobal(client.getObject());
      return Optional.of(true);
    } catch (RuntimeException failed) {
      log.warn("the Global could not be applied to the cluster", failed);
      return Optional.of(false);
    } finally {
      resetLock.readLock().unlock();
    }
  }

  /**
   * Heals what a failed apply left behind, an API server that was away included. The Global is
   * applied once, before the spaces; if it fails, the spaces wait for the next round.
   */
  @Scheduled(
      initialDelayString = "${aisolate.cluster.resync:PT5M}",
      fixedDelayString = "${aisolate.cluster.resync:PT5M}")
  public void resyncAll() {
    if (!properties.enabled()) {
      return;
    }
    // With no space yet, nothing else writes the Global.
    if (!syncGlobalAfterWrite().orElse(false)) {
      return;
    }
    for (Space space : spaces.list()) {
      Cluster outcome = syncSpace(space.id());
      if (!outcome.applied()) {
        log.warn("space {} not applied to the cluster: {}", space.id(), outcome.problem());
      }
    }
  }

  /**
   * Everything of one space, the Global first. One space's syncs run one at a time, each reading
   * what is committed when it starts, so the last to apply holds every save that came before it.
   */
  public Cluster sync(String spaceId) {
    if (!properties.enabled()) {
      return Cluster.failed("no cluster configured");
    }
    // Limit: per-JVM locks, as the chart runs one backend; Postgres advisory locks if it runs more.
    resetLock.readLock().lock();
    try {
      // Every airlock sees it: a space must never exist without it, so it goes before the space,
      // and a space is not applied over a Global that failed.
      try {
        applyGlobal(client.getObject());
      } catch (RuntimeException failed) {
        log.warn("the Global could not be applied to the cluster", failed);
        return Cluster.failed(describe(failed));
      }
      return applySpaceLocked(spaceId);
    } finally {
      resetLock.readLock().unlock();
    }
  }

  /** One space with the same locks as {@link #sync}, for a caller that applied the Global. */
  private Cluster syncSpace(String spaceId) {
    resetLock.readLock().lock();
    try {
      return applySpaceLocked(spaceId);
    } finally {
      resetLock.readLock().unlock();
    }
  }

  private Cluster applySpaceLocked(String spaceId) {
    synchronized (spaceLocks.computeIfAbsent(spaceId, id -> new Object())) {
      return applySpace(spaceId);
    }
  }

  private Cluster applySpace(String spaceId) {
    try {
      List<Object> documents =
          Documents.toDocuments(spaces.require(spaceId), airlocks.list(spaceId));
      KubernetesClient k8s = client.getObject();
      for (Class<?> kind : APPLY_ORDER) {
        documents.stream()
            .filter(kind::isInstance)
            .forEach(
                document ->
                    createOrUpdate(
                        k8s.genericKubernetesResources(
                                CONTEXTS.get(ConfigurationLoader.kind(document)))
                            .inNamespace(properties.namespace())
                            .resource(toResource(document))));
      }
      log.info(
          "space {} applied to namespace {}: {} document(s)",
          spaceId,
          properties.namespace(),
          documents.size());
      return Cluster.ok();
    } catch (RuntimeException failed) {
      log.warn("space {} could not be applied to the cluster", spaceId, failed);
      return Cluster.failed(describe(failed));
    }
  }

  private void applyGlobal(KubernetesClient k8s) {
    synchronized (globalLock) {
      createOrUpdate(
          k8s.genericKubernetesResources(GLOBALS)
              .inNamespace(properties.namespace())
              .resource(toResource(globals.document())));
    }
  }

  /**
   * The document's resource, created from it when the cluster has none: the owner of the Secrets of
   * its data sources. An existing one is left as it is; the sync after the save updates it. A Space
   * or Airlock created here goes again if the save rolls back.
   *
   * @param document a Global, Space or Airlock document
   */
  public GenericKubernetesResource findOrCreate(Object document) {
    ResourceDefinitionContext kind = CONTEXTS.get(ConfigurationLoader.kind(document));
    var resources =
        client.getObject().genericKubernetesResources(kind).inNamespace(properties.namespace());
    GenericKubernetesResource resource = toResource(document);
    String name = resource.getMetadata().getName();
    GenericKubernetesResource existing = resources.withName(name).get();
    if (existing != null) {
      return existing;
    }
    GenericKubernetesResource created;
    try {
      created = resources.resource(resource).create();
    } catch (KubernetesClientException raced) {
      // A sync created it between the read and the create.
      if (raced.getCode() != HttpURLConnection.HTTP_CONFLICT) {
        throw raced;
      }
      return resources.withName(name).get();
    }
    // Not the Global: the install's, another save may already own Secrets by it.
    if (!GLOBALS.getKind().equals(kind.getKind())) {
      events.publishEvent(new ResourceCreated(kind, name, created.getMetadata().getUid()));
    }
    return created;
  }

  /**
   * No row defines a resource a rolled-back save created. The uid guard keeps one someone deleted
   * and created again under its name since. It cannot keep one a same-id save adopted between the
   * rollback and this delete: that save's sync or the scheduled resync brings the resource back,
   * but garbage collection takes its Secrets, whose credentials wait for its next save. Failures
   * are logged: the controller runs it until the next reset.
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_ROLLBACK)
  public void handleResourceCreated(ResourceCreated event) {
    try {
      Resource<GenericKubernetesResource> resource =
          client
              .getObject()
              .genericKubernetesResources(event.kind())
              .inNamespace(properties.namespace())
              .withName(event.name());
      GenericKubernetesResource current = resource.get();
      if (current != null && event.uid().equals(current.getMetadata().getUid())) {
        resource.delete();
      }
    } catch (RuntimeException failed) {
      log.warn(
          "{} {} could not be deleted: {}",
          event.kind().getKind(),
          event.name(),
          failed.getMessage());
    }
  }

  /**
   * The cluster's half of the reset, after the database's: the Secrets the Spaces and Airlocks
   * name, then every such resource in the namespace; the controller's objects follow their owners.
   * Waits for the airlock and tool-server pods, so a deploy right after does not meet the old
   * Deployments on their way out.
   */
  public SpacesDeletedDTO.Cluster clear(Duration patience) {
    String namespace = properties.namespace();
    resetLock.writeLock().lock();
    try {
      KubernetesClient k8s = client.getObject();
      List<ResourceDefinitionContext> kinds = List.of(AIRLOCKS, SPACES);
      // By name: the backend may create and delete Secrets, never list them.
      for (ResourceDefinitionContext kind : kinds) {
        for (GenericKubernetesResource resource :
            k8s.genericKubernetesResources(kind).inNamespace(namespace).list().getItems()) {
          for (String list : List.of("dataSources", "llms")) {
            if (resource.get("spec", list) instanceof List<?> rows) {
              rows.stream()
                  .filter(Map.class::isInstance)
                  .map(row -> ((Map<?, ?>) row).get("secretRef"))
                  .filter(String.class::isInstance)
                  .forEach(
                      ref -> k8s.secrets().inNamespace(namespace).withName((String) ref).delete());
            }
          }
        }
      }
      for (ResourceDefinitionContext kind : kinds) {
        // One by one: a collection delete needs the deletecollection verb as well.
        for (GenericKubernetesResource resource :
            k8s.genericKubernetesResources(kind).inNamespace(namespace).list().getItems()) {
          k8s.genericKubernetesResources(kind)
              .inNamespace(namespace)
              .withName(resource.getMetadata().getName())
              .delete();
        }
      }
      boolean podsGone = waitForPods(k8s, namespace, patience);
      log.info("namespace {} cleared; airlock and tool-server pods gone: {}", namespace, podsGone);
      return new SpacesDeletedDTO.Cluster(podsGone, null);
    } catch (RuntimeException failed) {
      log.warn("namespace {} could not be cleared", namespace, failed);
      return new SpacesDeletedDTO.Cluster(false, describe(failed));
    } finally {
      resetLock.writeLock().unlock();
    }
  }

  /**
   * Created, or updated over the latest resourceVersion. The space lock keeps this backend's own
   * syncs apart; the controller's status write between update's read and its PUT is still a 409:
   * read again and retry, as client-go's RetryOnConflict does, instead of leaving the cluster a
   * save behind.
   */
  static <T extends HasMetadata> void createOrUpdate(Resource<T> resource) {
    for (int attempt = 1; ; attempt++) {
      try {
        resource.createOr(NonDeletingOperation::update);
        return;
      } catch (KubernetesClientException failed) {
        if (failed.getCode() != HttpURLConnection.HTTP_CONFLICT || attempt == CONFLICT_TRIES) {
          throw failed;
        }
      }
    }
  }

  // Polls inside one request; an async status endpoint if pods outlive PODS_PATIENCE.
  private static boolean waitForPods(KubernetesClient k8s, String namespace, Duration patience) {
    long deadline = System.nanoTime() + patience.toNanos();
    while (true) {
      // The controller's own pod carries the role label too: only the two kinds it renders count.
      // Filtered here, not by an `in` selector, which the fabric8 test mock does not parse.
      boolean gone =
          k8s.pods().inNamespace(namespace).withLabel(Names.ROLE_LABEL).list().getItems().stream()
              .map(pod -> pod.getMetadata().getLabels().get(Names.ROLE_LABEL))
              .noneMatch(RENDERED_ROLES::contains);
      if (gone || System.nanoTime() >= deadline) {
        return gone;
      }
      try {
        Thread.sleep(Math.min(1000, patience.toMillis()));
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
  }

  /** One document as its resource: our frame (apiVersion, kind) around its metadata and spec. */
  private GenericKubernetesResource toResource(Object document) {
    Map<String, Object> fields = JSON.convertValue(document, MAP);
    GenericKubernetesResource resource = new GenericKubernetesResource();
    resource.setApiVersion(ConfigurationLoader.API_VERSION);
    resource.setKind(ConfigurationLoader.kind(document));
    resource.setMetadata(
        new ObjectMetaBuilder()
            .withName((String) ((Map<?, ?>) fields.get("metadata")).get("name"))
            .withNamespace(properties.namespace())
            .build());
    resource.setAdditionalProperty("spec", fields.get("spec"));
    return resource;
  }

  /** The failure's message, else its type: what the caller is told. */
  private static String describe(RuntimeException failed) {
    return failed.getMessage() == null ? failed.getClass().getSimpleName() : failed.getMessage();
  }

  private static ResourceDefinitionContext buildContext(String kind, String plural) {
    return new ResourceDefinitionContext.Builder()
        .withGroup(ConfigurationLoader.GROUP)
        .withVersion("v1")
        .withKind(kind)
        .withPlural(plural)
        .withNamespaced(true)
        .build();
  }
}
