package com.reportix.aisolate.backend.domain.airlock;

import com.reportix.aisolate.backend.domain.Creator;
import com.reportix.aisolate.backend.domain.deploy.JarInspector;
import com.reportix.aisolate.backend.domain.global.GlobalRepository;
import com.reportix.aisolate.backend.domain.space.Space;
import com.reportix.aisolate.backend.domain.space.SpaceRepository;
import com.reportix.aisolate.backend.infrastructure.cluster.ClusterSyncService;
import com.reportix.aisolate.config.DataSource;
import com.reportix.aisolate.config.GlobalDocument;
import com.reportix.aisolate.config.Llm;
import com.reportix.aisolate.config.Names;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Airlocks as the AIsolate UI edits them, always inside one space, and as a deploy saves them with
 * their code. The UI's form never changes what runs (the handlers, the LLM, the prompt, the tools):
 * only a deploy does, and a deploy always brings the code. A save that brings data source
 * credentials or a model's API key stores them in one transaction with the row: the row, the
 * Airlock resource, the Secrets, then their names in the row ({@link DataSourceSecrets}).
 */
@Service
@RequiredArgsConstructor
public class AirlockService {

  private final SpaceRepository spaces;
  private final AirlockRepository airlocks;
  private final GlobalRepository globals;
  private final ClusterSyncService clusterSync;
  private final DataSourceSecrets secrets;
  private final TransactionOperations transactions;

  public List<Airlock> listAirlocks(String spaceId) {
    Space space = spaces.require(spaceId);
    GlobalDocument global = globals.document();
    return airlocks.list(spaceId).stream()
        .map(airlock -> resolved(airlock, global, space))
        .toList();
  }

  public Airlock findAirlock(String spaceId, String id) {
    return resolved(airlocks.require(spaceId, id), globals.document(), spaces.require(spaceId));
  }

  /** An airlock created in the AIsolate UI: nothing runs until a deploy brings the code. */
  public Airlock createAirlock(String spaceId, AirlockFormDTO submitted, Creator actor) {
    return createAirlock(spaceId, submitted.withRunOf(null), null, actor);
  }

  /**
   * A new airlock at revision 1, on its own endpoint. Its space's roles may ask it; it adds the
   * roles the form names, none by default, and admits the callers the form names, else any caller.
   * Airlock ids are global, unique across the install. An id derived from the name steps past a
   * cluster name as past a taken id, since the form has no id field to fix it in.
   *
   * @param code what a deploy brings; null for none
   * @throws ConflictException the id is taken, or would collide
   */
  public Airlock createAirlock(
      String spaceId, AirlockFormDTO submitted, AirlockRepository.Code code, Creator actor) {
    spaces.require(spaceId);
    return transactions.execute(
        status -> {
          spaces.lockNames();
          AirlockFormDTO form =
              submitted
                  .withIdFromName(
                      spaceId,
                      id ->
                          airlocks.find(id).isPresent()
                              || clusterNameHolder(spaceId + "-" + id).isPresent())
                  .withDefaultCallers()
                  .withDefaultPrompt();
          DataSourceSecrets.Owner owner = DataSourceSecrets.Owner.ofAirlock(spaceId, form.id());
          Space space = spaces.require(spaceId);
          GlobalDocument global = globals.document();
          List<String> errors =
              new ArrayList<>(
                  validateAirlock(global, space, form, code == null ? null : code.contents()));
          if (form.id() != null && form.id().length() > Names.maxAirlockId(spaceId)) {
            errors.add(
                "id: at most "
                    + Names.maxAirlockId(spaceId)
                    + " characters in the space "
                    + spaceId
                    + ": it runs as <space>-<airlock>-mcp-server, and Kubernetes allows "
                    + Names.MAX_NAME);
          }
          errors.addAll(secrets.problems(owner, form.llms(), List.of()));
          secrets.requireValid(owner, errors, form.dataSources(), List.of());
          airlocks
              .find(form.id())
              .ifPresent(
                  taken -> {
                    throw ConflictException.airlockTaken(form.id(), spaceId, taken.spaceId());
                  });
          clusterNameHolder(spaceId + "-" + form.id())
              .ifPresent(
                  other -> {
                    throw ConflictException.collision("id", "id", form.id(), other);
                  });
          return resolved(
              storeCredentials(airlocks.insert(spaceId, form, code, actor), form, owner),
              global,
              space);
        });
  }

  /**
   * Cluster objects are named {@code <space>-…} and {@code <space>-<airlock>-…}, and every id
   * allows dashes, so {@code ab} with {@code cd-ef} and {@code ab-cd} with {@code ef} would share
   * them: whichever comes second is refused. The naming stays; the check runs once, on create.
   *
   * @return the airlock already named so, as a message names it: {@code the airlock ef in space
   *     ab-cd}
   */
  public Optional<String> clusterNameOwner(String name) {
    return airlocks
        .findByClusterName(name)
        .map(other -> "the airlock " + other.id() + " in space " + other.spaceId());
  }

  /** {@link #clusterNameOwner}, or the space whose id is the name. */
  private Optional<String> clusterNameHolder(String name) {
    return clusterNameOwner(name)
        .or(() -> spaces.find(name).map(space -> "the space " + space.id()));
  }

  /**
   * An airlock edited in the AIsolate UI: what runs, and its code, stay.
   *
   * @param ifMatch the {@code updated_at} the form was read at, or null ({@link
   *     ConflictException#requireUnchanged})
   */
  public Airlock updateAirlock(
      String spaceId, String id, AirlockFormDTO submitted, String ifMatch) {
    return updateAirlock(spaceId, id, submitted, null, ifMatch);
  }

  /**
   * Saves the airlock in place; its id is its endpoint path and stays; its revision counts the
   * save. Its own roles may be none; unlike a create, a save names its callers: a list left empty
   * is refused, never widened. The stored row is read locked, so a concurrent save cannot keep a
   * Secret this one replaces. A save from the UI that would leave the airlock without what it
   * resolves today (its LLM, a data source its code reads) is refused ({@link
   * #requireAirlocksStillResolve}).
   *
   * @param code what a deploy brings in place of the stored code, with the form's handlers, LLM,
   *     prompt and tools; null keeps all of them
   */
  public Airlock updateAirlock(
      String spaceId,
      String id,
      AirlockFormDTO submitted,
      AirlockRepository.Code code,
      String ifMatch) {
    DataSourceSecrets.Owner owner = DataSourceSecrets.Owner.ofAirlock(spaceId, id);
    return transactions.execute(
        status -> {
          Airlock stored = airlocks.lock(spaceId, id);
          if (!id.equals(submitted.id())) {
            throw new InvalidFormException(List.of("id: cannot be changed"));
          }
          ConflictException.requireUnchanged("updated_at", ifMatch, stored.updatedAt());
          AirlockFormDTO form =
              (code == null ? submitted.withRunOf(stored.form()) : submitted)
                  .withDefaultPrompt()
                  .withDataSources(
                      DataSourceSecrets.keepRefs(stored.dataSources(), submitted.dataSources()))
                  .withLlms(DataSourceSecrets.keepRefs(stored.llms(), submitted.llms()));
          Space space = spaces.require(spaceId);
          GlobalDocument global = globals.document();
          // The UI's save changes nothing the code is checked against: its form's own checks do.
          List<String> errors =
              new ArrayList<>(
                  code == null
                      ? form.validate()
                      : validateAirlock(global, space, form, code.contents()));
          errors.addAll(secrets.problems(owner, form.llms(), stored.llms()));
          secrets.requireValid(owner, errors, form.dataSources(), stored.dataSources());
          Airlock updated = airlocks.update(id, form, code);
          if (code == null) {
            requireAirlocksStillResolve(
                space, space, List.of(stored), airlock -> updated, "on the space", "space");
          }
          Airlock saved = storeCredentials(updated, form, owner);
          secrets.publishReplaced(stored.dataSources(), saved.dataSources());
          secrets.publishReplaced(stored.llms(), saved.llms());
          return resolved(saved, global, space);
        });
  }

  /**
   * The form's own checks; its tools and handlers are its code's; and what it resolves over its
   * space and the global level resolves ({@link #unresolved}).
   *
   * @param jar the airlock's code, the stored one or the one a deploy brings; null for none
   */
  static List<String> validateAirlock(
      GlobalDocument global, Space space, AirlockFormDTO form, JarInspector.Contents jar) {
    List<String> errors = new ArrayList<>(form.validate());
    checkCode("tools", "a tool", form.tools(), jar == null ? List.of() : jar.toolNames(), errors);
    checkCode(
        "context_preparators",
        "a context preparator",
        form.contextPreparators(),
        handlerNames(jar, "context_prep"),
        errors);
    checkCode(
        "memory_handlers",
        "a conversation memory",
        form.memoryHandlers(),
        handlerNames(jar, "memory"),
        errors);
    checkCode("verifiers", "a verifier", form.verifiers(), handlerNames(jar, "verifier"), errors);
    unresolved(global, space, form, jar).forEach(problem -> errors.add(problem.ownError()));
    return errors;
  }

  /**
   * Something an airlock resolves over itself, its space and the global level, and finds nowhere:
   * the controller holds such an airlock back and it serves no one.
   *
   * @param field the field of a space or airlock save that would have taken it: {@code llms} or
   *     {@code data_sources}
   * @param lack what the airlock is left with, as a parent's refusal says it
   * @param ownError the airlock's own refusal
   */
  public record Unresolved(String field, String lack, String ownError) {}

  /** An airlock's LLMs by level, nearest first, as {@link Airlock.MainLlm#of} reads them. */
  static Map<String, List<Llm>> llmLevels(GlobalDocument global, Space space, AirlockFormDTO form) {
    Map<String, List<Llm>> levels = new LinkedHashMap<>();
    levels.put("airlock", LlmFormDTO.toLlms(form.llms()));
    levels.put("space", LlmFormDTO.toLlms(space.llms()));
    levels.put("global", global.spec().llms());
    return levels;
  }

  /**
   * The LLM that answers is defined on the airlock, its space or globally; and every plugin of the
   * code that names a data source, as each one with entities must, names one the three define: the
   * tool server refuses to start otherwise, whichever tools the model may call. Ids that code names
   * at run time ({@code aisolate.llm("nomic")}) are not checked.
   */
  static List<Unresolved> unresolved(
      GlobalDocument global, Space space, AirlockFormDTO form, JarInspector.Contents jar) {
    List<Unresolved> found = new ArrayList<>();
    if (!form.llm().isEmpty()
        && Airlock.MainLlm.of(form.llm(), llmLevels(global, space, form)) == null) {
      found.add(
          new Unresolved(
              "llms",
              "no model " + form.llm(),
              "llm: no model named " + form.llm() + " in this airlock's space or globally"));
    }
    if (jar != null) {
      Set<String> defined =
          Stream.concat(
                  global.spec().dataSources().stream().map(DataSource::id),
                  Stream.of(space.dataSources(), form.dataSources())
                      .flatMap(List::stream)
                      .map(DataSourceFormDTO::id))
              .collect(Collectors.toSet());
      jar.tools().stream()
          .filter(tool -> tool.dataSource() != null && !defined.contains(tool.dataSource()))
          .map(
              tool ->
                  new Unresolved(
                      "data_sources",
                      "no data source "
                          + tool.dataSource()
                          + ", which its plugin "
                          + tool.plugin()
                          + " reads",
                      "tools: plugin "
                          + tool.plugin()
                          + " reads data source "
                          + tool.dataSource()
                          + ", which this airlock does not define"))
          .distinct()
          .forEach(found::add);
    }
    return found;
  }

  /**
   * What a save leaves the airlocks beneath it without that they resolve today, each with the
   * airlocks it hits, by label. Problems an airlock already had are not this save's.
   *
   * @param stored the airlocks beneath the save, as stored
   * @param edit each stored airlock as the save leaves it
   */
  public Map<Unresolved, Set<String>> lostBy(
      GlobalDocument globalBefore,
      GlobalDocument globalAfter,
      Space before,
      Space after,
      List<Airlock> stored,
      UnaryOperator<Airlock> edit) {
    Map<Unresolved, Set<String>> lost = new LinkedHashMap<>();
    for (Airlock airlock : stored) {
      Airlock edited = edit.apply(airlock);
      List<Unresolved> now = unresolved(globalBefore, before, airlock.form(), airlock.jar());
      unresolved(globalAfter, after, edited.form(), edited.jar()).stream()
          .filter(problem -> !now.contains(problem))
          .forEach(
              problem -> lost.computeIfAbsent(problem, p -> new TreeSet<>()).add(airlock.label()));
    }
    return lost;
  }

  /**
   * Refuses a save of the space, or of one airlock, that would leave an airlock beneath it without
   * what it resolves today (its LLM, a data source its code reads): the controller would hold that
   * airlock back and it would serve no one, while the save itself looked applied.
   *
   * @param elsewhere where else a data source may be defined: {@code on the airlock}
   * @param theirs where else an LLM may be: {@code airlock}
   * @throws InvalidFormException one message per field, naming the airlocks
   */
  public void requireAirlocksStillResolve(
      Space before,
      Space after,
      List<Airlock> stored,
      UnaryOperator<Airlock> edit,
      String elsewhere,
      String theirs) {
    GlobalDocument global = globals.document();
    Map<Unresolved, Set<String>> lost = lostBy(global, global, before, after, stored, edit);
    if (!lost.isEmpty()) {
      throw new InvalidFormException(
          lost.entrySet().stream()
              .map(
                  entry ->
                      entry.getKey().field().equals("llms")
                          ? "llms: "
                              + joinNames(entry.getValue())
                              + " would have "
                              + entry.getKey().lack()
                              + "; define it on their "
                              + theirs
                              + " first"
                          : entry.getKey().field()
                              + ": "
                              + joinNames(entry.getValue())
                              + (entry.getValue().size() == 1
                                  ? " would have "
                                  : " would each have ")
                              + entry.getKey().lack()
                              + "; keep it, or define it "
                              + elsewhere
                              + " first")
              .toList());
    }
  }

  /** {@code s/a}, {@code s/a and s/b}, {@code s/a, s/b and s/c}. */
  public static String joinNames(Collection<String> names) {
    List<String> all = List.copyOf(names);
    return all.size() == 1
        ? all.getFirst()
        : String.join(", ", all.subList(0, all.size() - 1)) + " and " + all.getLast();
  }

  /** Each listed name is one the code carries; blank names are the form's own error. */
  private static void checkCode(
      String field, String what, List<String> listed, List<String> inCode, List<String> errors) {
    Names.notInCode(what, listed, inCode).forEach(problem -> errors.add(field + ": " + problem));
  }

  private static List<String> handlerNames(JarInspector.Contents jar, String stage) {
    return jar == null ? List.of() : jar.handlerNames(stage);
  }

  private Airlock storeCredentials(
      Airlock airlock, AirlockFormDTO form, DataSourceSecrets.Owner owner) {
    Airlock withLogins =
        secrets.storeIfAny(
            owner,
            form.dataSources(),
            airlock,
            () -> clusterSync.findOrCreate(Documents.toAirlock(airlock)),
            refs -> airlocks.updateDataSources(airlock.id(), refs));
    return secrets.storeIfAny(
        owner,
        form.llms(),
        withLogins,
        () -> clusterSync.findOrCreate(Documents.toAirlock(withLogins)),
        refs -> airlocks.updateLlms(airlock.id(), refs));
  }

  /** The stored airlock with the LLM its {@code llm} names, as the controller resolves it. */
  private static Airlock resolved(Airlock airlock, GlobalDocument global, Space space) {
    return airlock.withMainLlm(
        Airlock.MainLlm.of(airlock.form().llm(), llmLevels(global, space, airlock.form())));
  }
}
