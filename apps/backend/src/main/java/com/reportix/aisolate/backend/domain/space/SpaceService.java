package com.reportix.aisolate.backend.domain.space;

import com.reportix.aisolate.backend.domain.Creator;
import com.reportix.aisolate.backend.domain.airlock.AirlockRepository;
import com.reportix.aisolate.backend.domain.airlock.AirlockService;
import com.reportix.aisolate.backend.domain.airlock.AirlockVersionRepository;
import com.reportix.aisolate.backend.domain.airlock.ConflictException;
import com.reportix.aisolate.backend.domain.airlock.DataSourceSecrets;
import com.reportix.aisolate.backend.domain.airlock.Documents;
import com.reportix.aisolate.backend.domain.airlock.InvalidFormException;
import com.reportix.aisolate.backend.domain.audit.AuditRetention;
import com.reportix.aisolate.backend.domain.credential.CredentialRepository;
import com.reportix.aisolate.backend.domain.credential.CredentialRepository.Event;
import com.reportix.aisolate.backend.infrastructure.cluster.ClusterProperties;
import com.reportix.aisolate.backend.infrastructure.cluster.ClusterSyncService;
import com.reportix.aisolate.backend.infrastructure.metrics.PrometheusClient;
import com.reportix.aisolate.backend.infrastructure.security.Access;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Spaces as the AIsolate UI edits them: an edit is live at once. A save that brings data source
 * credentials or a model's API key stores them in one transaction with the row: the row, the Space
 * resource (the Secrets' owner), the Secrets, then their names in the row.
 */
@Service
@RequiredArgsConstructor
public class SpaceService {

  private final SpaceRepository spaces;
  private final AirlockRepository airlocks;
  private final AirlockVersionRepository versions;
  private final AirlockService airlockService;
  private final DataSourceSecrets secrets;
  private final CredentialRepository rows;
  private final ClusterSyncService clusterSync;
  private final ClusterProperties cluster;
  private final TransactionOperations transactions;
  private final AuditRetention audit;
  private final Optional<PrometheusClient> metrics;

  public List<Space> listSpaces() {
    return spaces.list();
  }

  public Space findSpace(String id) {
    return spaces.require(id);
  }

  /**
   * Its objects are named {@code <space>-…}: no airlock's cluster name may be its id. An id derived
   * from the name steps past one as past a taken id, since the form has no id field to fix it in.
   *
   * @throws ConflictException the id is taken, or would collide
   */
  public Space create(SpaceFormDTO submitted, Creator actor) {
    return transactions.execute(status -> {
      spaces.lockNames();
      SpaceFormDTO form =
          submitted.withIdFromName(
              id -> spaces.find(id).isPresent() || airlockService.clusterNameOwner(id).isPresent());
      DataSourceSecrets.Owner owner = DataSourceSecrets.Owner.ofSpace(form.id());
      List<String> errors = new ArrayList<>(form.validate());
      errors.addAll(secrets.problems(owner, form.llms(), List.of()));
      secrets.requireValid(owner, errors, form.dataSources(), List.of());
      if (spaces.find(form.id()).isPresent()) {
        throw new ConflictException("id", "A space with the id " + form.id() + " already exists.");
      }
      airlockService
          .clusterNameOwner(form.id())
          .ifPresent(other -> {
            throw ConflictException.collision("id", "id", form.id(), other);
          });
      return storeCredentials(spaces.insert(form, actor), form, owner);
    });
  }

  /**
   * The stored row is read locked, so a concurrent save cannot keep a Secret this one replaces. A
   * save that would leave a version without its main LLM or a data source its code reads is refused
   * ({@link AirlockService#requireVersionsStillResolve}).
   *
   * @param ifMatch the {@code updated_at} the form was read at, or null ({@link
   *     ConflictException#requireUnchanged})
   */
  public Space update(String id, SpaceFormDTO submitted, String ifMatch) {
    if (!id.equals(submitted.id())) {
      throw new InvalidFormException(List.of("id: cannot be changed"));
    }
    DataSourceSecrets.Owner owner = DataSourceSecrets.Owner.ofSpace(id);
    return transactions.execute(status -> {
      Space stored = spaces.lock(id);
      ConflictException.requireUnchanged("updated_at", ifMatch, stored.updatedAt());
      SpaceFormDTO form =
          submitted
              .withDataSources(
                  DataSourceSecrets.keepRefs(stored.dataSources(), submitted.dataSources()))
              .withLlms(DataSourceSecrets.keepRefs(stored.llms(), submitted.llms()));
      List<String> errors = new ArrayList<>(form.validate());
      errors.addAll(secrets.problems(owner, form.llms(), stored.llms()));
      secrets.requireValid(owner, errors, form.dataSources(), stored.dataSources());
      Space updated = spaces.update(id, form);
      airlockService.requireVersionsStillResolve(
          stored,
          updated,
          airlocks.list(id),
          airlock -> airlock,
          "on the airlock",
          "airlock or version");
      Space saved = storeCredentials(updated, form, owner);
      secrets.publishReplaced(stored.dataSources(), saved.dataSources());
      secrets.publishReplaced(stored.llms(), saved.llms());
      return saved;
    });
  }

  /**
   * Every space with all it holds, children first: no foreign key cascades. With a cluster, then
   * everything applied there, once the delete committed. Last, Monitoring's stores: the audit trail
   * (its Tracing list and the Audit page) and the product's series in the metrics store.
   */
  public SpacesDeletedDTO deleteAll() {
    List<CredentialRepository.Row> gone = new ArrayList<>();
    SpacesDeletedDTO deleted = transactions.execute(status -> deleteRows(gone));
    // After the commit: each event commits on its own, so none outlives a reset that rolled back.
    String actor = Access.caller();
    gone.forEach(r -> rows.record(Event.of(r, actor, "deleted", null)));
    if (cluster.enabled()) {
      deleted = deleted.withCluster(clusterSync.clear(ClusterSyncService.PODS_PATIENCE));
    }
    // After the pods: their last audit lines are in by then.
    return deleted.withMonitoring(clearMonitoring());
  }

  private SpacesDeletedDTO deleteRows(List<CredentialRepository.Row> gone) {
    gone.addAll(rows.deleteSpacesAndAirlocks());
    int airlockCount = airlocks.deleteAll();
    return new SpacesDeletedDTO(spaces.deleteAll(), airlockCount, null, null);
  }

  private SpacesDeletedDTO.Monitoring clearMonitoring() {
    int lines = audit.dropAll();
    if (metrics.isEmpty()) return new SpacesDeletedDTO.Monitoring(lines, null, null);
    try {
      metrics.get().deleteAll();
      return new SpacesDeletedDTO.Monitoring(lines, true, null);
    } catch (PrometheusClient.UnavailableException kept) {
      return new SpacesDeletedDTO.Monitoring(lines, false, kept.getMessage());
    }
  }

  private Space storeCredentials(Space space, SpaceFormDTO form, DataSourceSecrets.Owner owner) {
    Space withLogins =
        secrets.storeIfAny(
            owner,
            form.dataSources(),
            space,
            () -> clusterSync.findOrCreate(Documents.toSpace(space)),
            refs -> spaces.updateDataSources(space.id(), refs));
    return secrets.storeIfAny(
        owner,
        form.llms(),
        withLogins,
        () -> clusterSync.findOrCreate(Documents.toSpace(withLogins)),
        refs -> spaces.updateLlms(space.id(), refs));
  }
}
