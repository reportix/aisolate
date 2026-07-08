package com.reportix.aisolate.backend.domain.global;

import com.reportix.aisolate.backend.domain.Creator;
import com.reportix.aisolate.backend.domain.airlock.AirlockNotFoundException;
import com.reportix.aisolate.backend.domain.airlock.AirlockRepository;
import com.reportix.aisolate.backend.domain.airlock.AirlockService;
import com.reportix.aisolate.backend.domain.airlock.ConflictException;
import com.reportix.aisolate.backend.domain.airlock.DataSourceFormDTO;
import com.reportix.aisolate.backend.domain.airlock.DataSourceSecrets;
import com.reportix.aisolate.backend.domain.airlock.InvalidFormException;
import com.reportix.aisolate.backend.domain.airlock.LlmFormDTO;
import com.reportix.aisolate.backend.domain.space.Space;
import com.reportix.aisolate.backend.domain.space.SpaceRepository;
import com.reportix.aisolate.backend.infrastructure.cluster.ClusterSyncService;
import com.reportix.aisolate.config.GlobalDocument;
import com.reportix.aisolate.config.Llm;
import com.reportix.aisolate.config.Metadata;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The install's LLMs and data sources, the admin's: every space inherits them. A write is a row,
 * then the Global the cluster gets; a data source's credentials and a model's API key become a
 * Secret owned by the Global resource, as a space's are owned by its Space. Messages name the field
 * alone: the form is one row.
 */
@Service
@RequiredArgsConstructor
public class GlobalService {

  private final GlobalRepository globals;
  private final SpaceRepository spaces;
  private final AirlockRepository airlocks;
  private final AirlockService airlockService;
  private final DataSourceSecrets secrets;
  private final ClusterSyncService clusterSync;
  private final TransactionOperations transactions;

  public List<GlobalLlm> llms() {
    return globals.llms();
  }

  /**
   * @throws AirlockNotFoundException 404 {@code no model named granite}
   */
  public GlobalLlm llm(String id) {
    return globals.findLlm(id, false).orElseThrow(() -> noLlm(id));
  }

  /**
   * Its API key becomes a Secret; a {@code secret_ref} sent along is not the row's to set.
   *
   * @throws ConflictException the id is taken
   */
  public GlobalLlm createLlm(LlmFormDTO submitted, Creator actor) {
    LlmFormDTO llm = submitted.withSecretRef("");
    List<LlmFormDTO> rows = List.of(llm);
    one(() -> secrets.requireValid(owner(), LlmFormDTO.validate(rows), rows, List.of()));
    return transactions.execute(
        status -> {
          if (globals.findLlm(llm.id(), true).isPresent()) {
            throw new ConflictException("id", "a model " + llm.id() + " already exists");
          }
          globals.insertLlm(llm, actor);
          storeLlmKey(rows);
          return llm(llm.id());
        });
  }

  /**
   * The row keeps its Secret while its url stays and no key replaces it, whatever {@code
   * secret_ref} it was sent with.
   *
   * @param ifMatch the {@code updated_at} the form was read at, or null
   */
  public GlobalLlm updateLlm(String id, LlmFormDTO submitted, String ifMatch) {
    return transactions.execute(
        status -> {
          GlobalLlm stored = globals.findLlm(id, true).orElseThrow(() -> noLlm(id));
          requireIdKept(id, submitted.id());
          ConflictException.requireUnchanged("updated_at", ifMatch, stored.updatedAt());
          LlmFormDTO llm =
              new LlmFormDTO(
                  id,
                  submitted.provider(),
                  submitted.url(),
                  submitted.model(),
                  stored.secretRef(),
                  submitted.apiKey());
          List<LlmFormDTO> rows = List.of(llm);
          List<LlmFormDTO> before = List.of(stored.toRow());
          one(() -> secrets.requireValid(owner(), LlmFormDTO.validate(rows), rows, before));
          globals.updateLlm(llm);
          storeLlmKey(rows);
          GlobalLlm saved = llm(id);
          secrets.publishReplaced(before, List.of(saved.toRow()));
          return saved;
        });
  }

  /** Refused while an airlock answers with it and nothing nearer defines its id. */
  public void deleteLlm(String id) {
    transactions.executeWithoutResult(
        status -> {
          GlobalLlm stored = globals.findLlm(id, true).orElseThrow(() -> noLlm(id));
          GlobalDocument before = globals.document();
          requireNoAirlockLoses(
              before,
              withSpec(
                  before,
                  before.spec().llms().stream().filter(llm -> !llm.id().equals(id)).toList(),
                  before.spec().dataSources()));
          globals.deleteLlm(id);
          secrets.publishReplaced(List.of(stored.toRow()), List.<LlmFormDTO>of());
        });
  }

  public List<GlobalDataSource> dataSources() {
    return globals.dataSources();
  }

  /**
   * @throws AirlockNotFoundException 404 {@code no data source named hr}
   */
  public GlobalDataSource dataSource(String id) {
    return globals.findDataSource(id, false).orElseThrow(() -> noDataSource(id));
  }

  /** Its credentials become a Secret; a {@code secret_ref} sent along is not the row's to set. */
  public GlobalDataSource createDataSource(DataSourceFormDTO submitted, Creator actor) {
    DataSourceFormDTO row = submitted.withSecretRef("");
    List<DataSourceFormDTO> rows = List.of(row);
    one(() -> secrets.requireValid(owner(), DataSourceFormDTO.validate(rows), rows, List.of()));
    return transactions.execute(
        status -> {
          if (globals.findDataSource(row.id(), true).isPresent()) {
            throw new ConflictException("id", "a data source " + row.id() + " already exists");
          }
          globals.insertDataSource(row, actor);
          storeCredentials(rows);
          return dataSource(row.id());
        });
  }

  /**
   * The row keeps its Secret while its url stays and no password replaces it, whatever {@code
   * secret_ref} it was sent with.
   *
   * @param ifMatch the {@code updated_at} the form was read at, or null
   */
  public GlobalDataSource updateDataSource(String id, DataSourceFormDTO submitted, String ifMatch) {
    return transactions.execute(
        status -> {
          GlobalDataSource stored =
              globals.findDataSource(id, true).orElseThrow(() -> noDataSource(id));
          requireIdKept(id, submitted.id());
          ConflictException.requireUnchanged("updated_at", ifMatch, stored.updatedAt());
          DataSourceFormDTO row =
              new DataSourceFormDTO(
                      id, submitted.url(), null, submitted.username(), submitted.password())
                  .withSecretRef(stored.toRow().secretRef());
          List<DataSourceFormDTO> rows = List.of(row);
          List<DataSourceFormDTO> before = List.of(stored.toRow());
          one(() -> secrets.requireValid(owner(), DataSourceFormDTO.validate(rows), rows, before));
          globals.updateDataSource(row);
          storeCredentials(rows);
          GlobalDataSource saved = dataSource(id);
          secrets.publishReplaced(before, List.of(saved.toRow()));
          return saved;
        });
  }

  /** Refused while an airlock's code reads it and nothing nearer defines its id. */
  public void deleteDataSource(String id) {
    transactions.executeWithoutResult(
        status -> {
          GlobalDataSource stored =
              globals.findDataSource(id, true).orElseThrow(() -> noDataSource(id));
          GlobalDocument before = globals.document();
          requireNoAirlockLoses(
              before,
              withSpec(
                  before,
                  before.spec().llms(),
                  before.spec().dataSources().stream()
                      .filter(dataSource -> !dataSource.id().equals(id))
                      .toList()));
          globals.deleteDataSource(id);
          secrets.publishReplaced(List.of(stored.toRow()), List.of());
        });
  }

  /**
   * Every airlock of every space that resolves today and would not with {@code after}: {@code id:
   * hr-space/hr would have no model granite; define it on its space first}.
   */
  private void requireNoAirlockLoses(GlobalDocument before, GlobalDocument after) {
    Map<AirlockService.Unresolved, Set<String>> lost = new LinkedHashMap<>();
    for (Space space : spaces.list()) {
      airlockService
          .lostBy(before, after, space, space, airlocks.list(space.id()), airlock -> airlock)
          .forEach(
              (problem, airlocks) ->
                  lost.computeIfAbsent(problem, p -> new TreeSet<>()).addAll(airlocks));
    }
    if (!lost.isEmpty()) {
      throw new InvalidFormException(
          lost.entrySet().stream()
              .map(
                  entry ->
                      "id: "
                          + AirlockService.joinNames(entry.getValue())
                          + " would have "
                          + entry.getKey().lack()
                          + "; define it on "
                          + (entry.getValue().size() == 1 ? "its" : "their")
                          + " space first")
              .toList());
    }
  }

  /** The Secret of a row that brings a password, owned by the Global resource. */
  private void storeCredentials(List<DataSourceFormDTO> rows) {
    one(
        () ->
            secrets.storeIfAny(
                owner(),
                rows,
                null,
                () -> clusterSync.findOrCreate(globals.document()),
                refs -> {
                  refs.forEach(ref -> globals.updateSecretRef(ref.id(), ref.secretRef()));
                  return null;
                }));
  }

  /** The Secret of a model's typed API key, owned by the Global resource. */
  private void storeLlmKey(List<LlmFormDTO> rows) {
    one(
        () ->
            secrets.storeIfAny(
                owner(),
                rows,
                null,
                () -> clusterSync.findOrCreate(globals.document()),
                refs -> {
                  refs.forEach(ref -> globals.updateLlmSecretRef(ref.id(), ref.secretRef()));
                  return null;
                }));
  }

  private static DataSourceSecrets.Owner owner() {
    return DataSourceSecrets.Owner.ofGlobal();
  }

  private static GlobalDocument withSpec(
      GlobalDocument global, List<Llm> llms, List<com.reportix.aisolate.config.DataSource> rows) {
    return new GlobalDocument(new Metadata(global.name()), new GlobalDocument.Spec(llms, rows));
  }

  private static void requireIdKept(String id, String sent) {
    if (sent != null && !sent.isBlank() && !sent.strip().equals(id)) {
      throw new InvalidFormException(List.of("id: cannot be changed"));
    }
  }

  private static void requireValid(List<String> errors) {
    if (!errors.isEmpty()) {
      throw new InvalidFormException(unprefixed(errors));
    }
  }

  /**
   * The list checks' messages for the one row this form is: {@code url: …}, not {@code llms[0].url:
   * …}.
   */
  private static void one(Runnable check) {
    try {
      check.run();
    } catch (InvalidFormException refused) {
      throw new InvalidFormException(unprefixed(refused.getErrors()));
    }
  }

  private static List<String> unprefixed(List<String> errors) {
    return errors.stream().map(e -> e.replaceFirst("^(llms|data_sources)\\[0]\\.", "")).toList();
  }

  private static AirlockNotFoundException noLlm(String id) {
    return new AirlockNotFoundException(id, "no model named " + id);
  }

  private static AirlockNotFoundException noDataSource(String id) {
    return new AirlockNotFoundException(id, "no data source named " + id);
  }
}
