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
import com.reportix.aisolate.identity.IdentityProperties;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * Airlocks and their versions as the AIsolate UI edits them, always inside one space, and the
 * versions a deploy saves with their code. A save that brings data source credentials or a model's
 * API key stores them in one transaction with the row: the row, the Airlock resource, the Secrets,
 * then their names in the row ({@link DataSourceSecrets}). A version holds no credential.
 */
@Service
@RequiredArgsConstructor
public class AirlockService {

  private final SpaceRepository spaces;
  private final AirlockRepository airlocks;
  private final AirlockVersionRepository versions;
  private final GlobalRepository globals;
  private final JsonMapper json;
  private final IdentityProperties properties;
  private final ClusterSyncService clusterSync;
  private final DataSourceSecrets secrets;
  private final TransactionOperations transactions;

  public List<Airlock> listAirlocks(String spaceId) {
    spaces.require(spaceId);
    return airlocks.list(spaceId);
  }

  public Airlock findAirlock(String spaceId, String id) {
    return airlocks.require(spaceId, id);
  }

  /**
   * The airlock without versions: they are added on its page, or by a deploy. Its space's roles may
   * ask it; it adds the roles the form names, none by default, and admits the callers the form
   * names, else any caller. Airlock ids are global, unique across the install. An id derived from
   * the name steps past a cluster name as past a taken id, since the form has no id field to fix it
   * in.
   *
   * @throws ConflictException the id is taken, or would collide
   */
  public Airlock createAirlock(String spaceId, AirlockFormDTO submitted, Creator actor) {
    spaces.require(spaceId);
    return transactions.execute(status -> {
      spaces.lockNames();
      AirlockFormDTO form =
          submitted
              .withIdFromName(
                  spaceId,
                  id ->
                      airlocks.find(id).isPresent()
                          || clusterNameHolder(spaceId + "-" + id).isPresent())
              .withDefaultCallers();
      DataSourceSecrets.Owner owner = DataSourceSecrets.Owner.ofAirlock(spaceId, form.id());
      List<String> errors = new ArrayList<>(form.validate());
      if (form.id() != null && form.id().length() > Names.maxAirlockId(spaceId)) {
        errors.add(
            "id: at most "
                + Names.maxAirlockId(spaceId)
                + " characters in the space "
                + spaceId
                + ": its versions run as <space>-<airlock>-<version>-mcp-server, and Kubernetes"
                + " allows "
                + Names.MAX_NAME);
      }
      errors.addAll(secrets.problems(owner, form.llms(), List.of()));
      secrets.requireValid(owner, errors, form.dataSources(), List.of());
      airlocks
          .find(form.id())
          .ifPresent(taken -> {
            throw ConflictException.airlockTaken(form.id(), spaceId, taken.spaceId());
          });
      clusterNameHolder(spaceId + "-" + form.id())
          .ifPresent(other -> {
            throw ConflictException.collision("id", "id", form.id(), other);
          });
      return storeCredentials(airlocks.insert(spaceId, form, actor), form, owner);
    });
  }

  /**
   * Cluster objects are named {@code <space>-…}, {@code <space>-<airlock>-…} and {@code
   * <space>-<airlock>-<version>-…}, and every id allows dashes, so {@code ab} with {@code cd-ef}
   * and {@code ab-cd} with {@code ef} would share them, as would {@code ab}/{@code cd-ef}/{@code
   * gh-ij} and {@code ab-cd}/{@code ef-gh}/{@code ij}: whichever comes second is refused. The
   * naming stays; the check runs once, on create.
   *
   * @return the airlock or version already named so, as a message names it: {@code the airlock ef
   *     in space ab-cd}
   */
  public Optional<String> clusterNameOwner(String name) {
    return airlocks
        .findByClusterName(name)
        .map(other -> "the airlock " + other.id() + " in space " + other.spaceId())
        .or(() -> versions.findByClusterName(name).map(AirlockService::describe));
  }

  /** {@link #clusterNameOwner}, or the space whose id is the name. */
  private Optional<String> clusterNameHolder(String name) {
    return clusterNameOwner(name)
        .or(() -> spaces.find(name).map(space -> "the space " + space.id()));
  }

  private static String describe(AirlockVersionRepository.Row version) {
    return "the version " + version.label() + " in space " + version.spaceId();
  }

  /**
   * Saves the airlock in place; its id is its endpoint path and stays; its revision counts the
   * save. Its own roles may be none; unlike a create, a save names its callers: a list left empty
   * is refused, never widened. The stored row is read locked, so a concurrent save cannot keep a
   * Secret this one replaces. A save that would leave a version of it without what it resolves
   * today (its LLM, a data source its code reads) is refused ({@link
   * #requireVersionsStillResolve}).
   *
   * @param ifMatch the {@code updated_at} the form was read at, or null ({@link
   *     ConflictException#requireUnchanged})
   */
  public Airlock updateAirlock(
      String spaceId, String id, AirlockFormDTO submitted, String ifMatch) {
    DataSourceSecrets.Owner owner = DataSourceSecrets.Owner.ofAirlock(spaceId, id);
    return transactions.execute(status -> {
      Airlock stored = airlocks.lock(spaceId, id);
      if (!id.equals(submitted.id())) {
        throw new InvalidFormException(List.of("id: cannot be changed"));
      }
      ConflictException.requireUnchanged("updated_at", ifMatch, stored.updatedAt());
      AirlockFormDTO form =
          submitted
              .withDataSources(
                  DataSourceSecrets.keepRefs(stored.dataSources(), submitted.dataSources()))
              .withLlms(DataSourceSecrets.keepRefs(stored.llms(), submitted.llms()));
      List<String> errors = new ArrayList<>(form.validate());
      errors.addAll(secrets.problems(owner, form.llms(), stored.llms()));
      secrets.requireValid(owner, errors, form.dataSources(), stored.dataSources());
      Space space = spaces.require(spaceId);
      Airlock updated = airlocks.update(id, form);
      requireVersionsStillResolve(
          space, space, List.of(stored), airlock -> updated, "on the space", "space or version");
      Airlock saved = storeCredentials(updated, form, owner);
      secrets.publishReplaced(stored.dataSources(), saved.dataSources());
      secrets.publishReplaced(stored.llms(), saved.llms());
      return saved;
    });
  }

  public List<AirlockVersion> listVersions(String spaceId, String airlockId) {
    Airlock airlock = airlocks.require(spaceId, airlockId);
    Space space = spaces.require(spaceId);
    GlobalDocument global = globals.document();
    return versions.list(airlockId).stream()
        .map(row -> buildVersion(row, airlock, space, global))
        .toList();
  }

  public AirlockVersion findVersion(String spaceId, String airlockId, String version) {
    Airlock airlock = airlocks.require(spaceId, airlockId);
    return buildVersion(
        requireVersion(airlockId, version), airlock, spaces.require(spaceId), globals.document());
  }

  /** A version saved in the AIsolate UI: no code until a deploy brings it. */
  public AirlockVersion createVersion(
      String spaceId, String airlockId, VersionFormDTO form, Creator actor) {
    return createVersion(spaceId, airlockId, form, null, actor);
  }

  /**
   * A new version at revision 1, on its own endpoint, under a name no version of the install has. A
   * name and its LLM are enough: no prompt is the default one ({@link
   * VersionFormDTO#DEFAULT_PROMPT}).
   *
   * @param code what a deploy brings; null for none
   * @throws ConflictException the name is taken on the airlock, or would collide
   */
  public AirlockVersion createVersion(
      String spaceId,
      String airlockId,
      VersionFormDTO submitted,
      AirlockVersionRepository.Code code,
      Creator actor) {
    VersionFormDTO form = submitted.withDefaultPrompt();
    Airlock airlock = airlocks.require(spaceId, airlockId);
    Space space = spaces.require(spaceId);
    GlobalDocument global = globals.document();
    List<String> errors =
        validateVersion(global, space, airlock, form, code == null ? null : code.contents());
    if (!errors.isEmpty()) {
      throw new InvalidFormException(errors);
    }
    return transactions.execute(status -> {
      spaces.lockNames();
      requireNameFree(spaceId, airlockId, form.version());
      return buildVersion(versions.insert(airlockId, form, code, actor), airlock, space, global);
    });
  }

  /**
   * A version edited in the AIsolate UI: its code stays.
   *
   * @param ifMatch the {@code updated_at} the form was read at, or null ({@link
   *     ConflictException#requireUnchanged})
   */
  public AirlockVersion updateVersion(
      String spaceId, String airlockId, String version, VersionFormDTO form, String ifMatch) {
    return updateVersion(spaceId, airlockId, version, form, null, ifMatch);
  }

  /**
   * Saves the version in place; its revision counts the save. Its name is fixed once created. The
   * stored row is read locked, as for an airlock.
   *
   * @param code what a deploy brings in place of the stored code; null keeps it
   */
  public AirlockVersion updateVersion(
      String spaceId,
      String airlockId,
      String version,
      VersionFormDTO submitted,
      AirlockVersionRepository.Code code,
      String ifMatch) {
    Airlock airlock = airlocks.require(spaceId, airlockId);
    return transactions.execute(status -> {
      AirlockVersionRepository.Row stored =
          versions
              .lock(airlockId, version)
              .orElseThrow(
                  () -> new AirlockNotFoundException(airlockId + "/" + version, "unknown version"));
      if (!version.equals(submitted.version())) {
        throw new InvalidFormException(List.of("version: cannot change; create a version"));
      }
      ConflictException.requireUnchanged("updated_at", ifMatch, stored.updatedAt());
      VersionFormDTO form = submitted.withDefaultPrompt();
      Space space = spaces.require(spaceId);
      GlobalDocument global = globals.document();
      List<String> errors =
          validateVersion(
              global, space, airlock, form, code == null ? stored.jar() : code.contents());
      if (!errors.isEmpty()) {
        throw new InvalidFormException(errors);
      }
      return buildVersion(versions.update(airlockId, form, code), airlock, space, global);
    });
  }

  /**
   * The form's own checks; its pods' names fit Kubernetes; its tools and handlers are its code's;
   * and what it resolves over its airlock, its space and the global level resolves ({@link
   * #unresolved}).
   *
   * @param jar the version's code, the stored one or the one a deploy brings; null for none
   */
  static List<String> validateVersion(
      GlobalDocument global,
      Space space,
      Airlock airlock,
      VersionFormDTO form,
      JarInspector.Contents jar) {
    List<String> errors = new ArrayList<>(form.validate());
    String tooLong =
        form.version().isBlank()
            ? null
            : Names.clusterNameProblem(space.id(), airlock.id(), form.version());
    if (tooLong != null) {
      errors.add("version: " + tooLong);
    }
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
    unresolved(global, space, airlock, form, jar)
        .forEach(problem -> errors.add(problem.ownError()));
    return errors;
  }

  /**
   * Something a version resolves over itself, its airlock, its space and the global level, and
   * finds nowhere: the controller holds such a version back and it serves no one.
   *
   * @param field the field of a space or airlock save that would have taken it: {@code llms} or
   *     {@code data_sources}
   * @param lack what the version is left with, as a parent's refusal says it
   * @param ownError the version's own refusal
   */
  public record Unresolved(String field, String lack, String ownError) {}

  /** A version's LLMs by level, nearest first, as {@link AirlockVersion.MainLlm#of} reads them. */
  static Map<String, List<Llm>> llmLevels(
      GlobalDocument global, Space space, Airlock airlock, VersionFormDTO form) {
    Map<String, List<Llm>> levels = new LinkedHashMap<>();
    levels.put("version", LlmFormDTO.toLlms(form.llms()));
    levels.put("airlock", LlmFormDTO.toLlms(airlock.llms()));
    levels.put("space", LlmFormDTO.toLlms(space.llms()));
    levels.put("global", global.spec().llms());
    return levels;
  }

  /**
   * The LLM that answers is defined on the version, its airlock, its space or globally; and every
   * plugin of the code that names a data source, as each one with entities must, names one the
   * airlock, the space or the global level define: the tool server refuses to start otherwise,
   * whichever tools the model may call. Ids that code names at run time ({@code
   * aisolate.llm("nomic")}) are not checked.
   */
  static List<Unresolved> unresolved(
      GlobalDocument global,
      Space space,
      Airlock airlock,
      VersionFormDTO form,
      JarInspector.Contents jar) {
    List<Unresolved> found = new ArrayList<>();
    if (!form.llm().isEmpty()
        && AirlockVersion.MainLlm.of(form.llm(), llmLevels(global, space, airlock, form)) == null) {
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
                  Stream.of(space.dataSources(), airlock.dataSources())
                      .flatMap(List::stream)
                      .map(DataSourceFormDTO::id))
              .collect(Collectors.toSet());
      jar.tools().stream()
          .filter(tool -> tool.dataSource() != null && !defined.contains(tool.dataSource()))
          .map(tool ->
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
   * What a save leaves the versions beneath it without that they resolve today, each with the
   * versions it hits, by label ({@code space/airlock/version}). Problems a version already had are
   * not this save's.
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
      for (AirlockVersionRepository.Row row : versions.list(airlock.id())) {
        VersionFormDTO form = VersionFormDTO.from(row, json);
        List<Unresolved> now = unresolved(globalBefore, before, airlock, form, row.jar());
        unresolved(globalAfter, after, edited, form, row.jar()).stream()
            .filter(problem -> !now.contains(problem))
            .forEach(problem ->
                lost.computeIfAbsent(problem, p -> new TreeSet<>())
                    .add(airlock.label() + "/" + row.version()));
      }
    }
    return lost;
  }

  /**
   * Refuses a save of the space, or of one airlock, that would leave a version beneath it without
   * what it resolves today (its LLM, a data source its code reads): the controller would hold that
   * version back and it would serve no one, while the save itself looked applied.
   *
   * @param elsewhere where else a data source may be defined: {@code on the airlock}
   * @param theirs where else an LLM may be: {@code airlock or version}
   * @throws InvalidFormException one message per field, naming the versions
   */
  public void requireVersionsStillResolve(
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
              .map(entry ->
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
                          + (entry.getValue().size() == 1 ? " would have " : " would each have ")
                          + entry.getKey().lack()
                          + "; keep it, or define it "
                          + elsewhere
                          + " first")
              .toList());
    }
  }

  /** {@code s/a/v1}, {@code s/a/v1 and s/b/v1}, {@code s/a/v1, s/b/v1 and s/c/v1}. */
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

  /**
   * The name is free on the airlock, in full across the install (the full name is the version's
   * resource name) and as a cluster name.
   *
   * @throws ConflictException it is not
   */
  private void requireNameFree(String spaceId, String airlockId, String version) {
    if (versions.find(airlockId, version).isPresent()) {
      throw new ConflictException(
          "version", "A version named " + version + " already exists in this airlock.");
    }
    versions
        .findByFullName(airlockId + "-" + version, airlockId)
        .map(AirlockService::describe)
        .or(() -> clusterNameHolder(spaceId + "-" + airlockId + "-" + version))
        .ifPresent(other -> {
          throw ConflictException.collision("version", "name", version, other);
        });
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

  private AirlockVersionRepository.Row requireVersion(String airlockId, String version) {
    return versions
        .find(airlockId, version)
        .orElseThrow(
            () -> new AirlockNotFoundException(airlockId + "/" + version, "unknown version"));
  }

  /** The stored version with the LLM its {@code llm} names, as the controller resolves it. */
  private AirlockVersion buildVersion(
      AirlockVersionRepository.Row row, Airlock airlock, Space space, GlobalDocument global) {
    VersionFormDTO form = VersionFormDTO.from(row, json);
    return new AirlockVersion(
        row.airlockId(),
        row.version(),
        row.description(),
        row.revision(),
        properties.chatEndpoint(row.spaceId(), row.airlockId(), row.version()),
        row.createdBy(),
        row.createdByName(),
        row.createdAt(),
        row.updatedAt(),
        form,
        AirlockVersion.MainLlm.of(form.llm(), llmLevels(global, space, airlock, form)),
        row.jar());
  }
}
