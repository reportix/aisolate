package com.reportix.aisolate.backend.domain.space;

import com.reportix.aisolate.backend.domain.Creator;
import com.reportix.aisolate.backend.domain.airlock.AirlockRepository;
import com.reportix.aisolate.backend.domain.airlock.AirlockService;
import com.reportix.aisolate.backend.domain.airlock.ConflictException;
import com.reportix.aisolate.backend.domain.airlock.DataSourceSecrets;
import com.reportix.aisolate.backend.domain.airlock.Documents;
import com.reportix.aisolate.backend.domain.airlock.InvalidFormException;
import com.reportix.aisolate.backend.domain.directory.DirectoryService;
import com.reportix.aisolate.backend.infrastructure.cluster.ClusterProperties;
import com.reportix.aisolate.backend.infrastructure.cluster.ClusterSyncService;
import java.util.ArrayList;
import java.util.List;
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
  private final AirlockService airlockService;
  private final DataSourceSecrets secrets;
  private final DirectoryService directory;
  private final ClusterSyncService clusterSync;
  private final ClusterProperties cluster;
  private final TransactionOperations transactions;

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
    // Asked before the names lock is taken: the directory is a network call.
    List<String> notInDirectory =
        directory.notInDirectory("members", List.of(), submitted.emails());
    return transactions.execute(
        status -> {
          spaces.lockNames();
          SpaceFormDTO form =
              submitted.withIdFromName(
                  id ->
                      spaces.find(id).isPresent()
                          || airlockService.clusterNameOwner(id).isPresent());
          DataSourceSecrets.Owner owner = DataSourceSecrets.Owner.ofSpace(form.id());
          List<String> errors = new ArrayList<>(form.validate());
          errors.addAll(notInDirectory);
          errors.addAll(secrets.problems(owner, form.llms(), List.of()));
          secrets.requireValid(owner, errors, form.dataSources(), List.of());
          if (spaces.find(form.id()).isPresent()) {
            throw new ConflictException(
                "id", "A space with the id " + form.id() + " already exists.");
          }
          airlockService
              .clusterNameOwner(form.id())
              .ifPresent(
                  other -> {
                    throw ConflictException.collision("id", "id", form.id(), other);
                  });
          return storeCredentials(spaces.insert(form, actor), form, owner);
        });
  }

  /**
   * The stored row is read locked, so a concurrent save cannot keep a Secret this one replaces. A
   * save that would leave an airlock without its main LLM or a data source its code reads is
   * refused ({@link AirlockService#requireAirlocksStillResolve}).
   *
   * @param ifMatch the {@code updated_at} the form was read at, or null ({@link
   *     ConflictException#requireUnchanged})
   */
  public Space update(String id, SpaceFormDTO submitted, String ifMatch) {
    if (!id.equals(submitted.id())) {
      throw new InvalidFormException(List.of("id: cannot be changed"));
    }
    DataSourceSecrets.Owner owner = DataSourceSecrets.Owner.ofSpace(id);
    // Outside the row lock: the directory is a network call. A save that came between is
    // If-Match's.
    List<String> notInDirectory =
        directory.notInDirectory("members", spaces.require(id).users(), submitted.emails());
    return transactions.execute(
        status -> {
          Space stored = spaces.lock(id);
          ConflictException.requireUnchanged("updated_at", ifMatch, stored.updatedAt());
          SpaceFormDTO form =
              submitted
                  .withDataSources(
                      DataSourceSecrets.keepRefs(stored.dataSources(), submitted.dataSources()))
                  .withLlms(DataSourceSecrets.keepRefs(stored.llms(), submitted.llms()));
          List<String> errors = new ArrayList<>(form.validate());
          errors.addAll(notInDirectory);
          errors.addAll(secrets.problems(owner, form.llms(), stored.llms()));
          secrets.requireValid(owner, errors, form.dataSources(), stored.dataSources());
          Space updated = spaces.update(id, form);
          airlockService.requireAirlocksStillResolve(
              stored, updated, airlocks.list(id), airlock -> airlock, "on the airlock", "airlock");
          Space saved = storeCredentials(updated, form, owner);
          secrets.publishReplaced(stored.dataSources(), saved.dataSources());
          secrets.publishReplaced(stored.llms(), saved.llms());
          return saved;
        });
  }

  /**
   * Every space with all it holds, children first: no foreign key cascades. With a cluster, then
   * everything applied there, once the delete committed.
   */
  public SpacesDeletedDTO deleteAll() {
    SpacesDeletedDTO deleted = transactions.execute(status -> deleteRows());
    return cluster.enabled()
        ? deleted.withCluster(clusterSync.clear(ClusterSyncService.PODS_PATIENCE))
        : deleted;
  }

  private SpacesDeletedDTO deleteRows() {
    int airlockCount = airlocks.deleteAll();
    return new SpacesDeletedDTO(spaces.deleteAll(), airlockCount, null);
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
