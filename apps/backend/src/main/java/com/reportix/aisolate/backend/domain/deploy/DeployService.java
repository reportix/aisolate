package com.reportix.aisolate.backend.domain.deploy;

import com.reportix.aisolate.backend.domain.Creator;
import com.reportix.aisolate.backend.domain.airlock.Airlock;
import com.reportix.aisolate.backend.domain.airlock.AirlockFormDTO;
import com.reportix.aisolate.backend.domain.airlock.AirlockNotFoundException;
import com.reportix.aisolate.backend.domain.airlock.AirlockRepository;
import com.reportix.aisolate.backend.domain.airlock.AirlockService;
import com.reportix.aisolate.backend.domain.airlock.AirlockVersion;
import com.reportix.aisolate.backend.domain.airlock.AirlockVersionRepository;
import com.reportix.aisolate.backend.domain.airlock.ConflictException;
import com.reportix.aisolate.backend.domain.airlock.DataSourceFormDTO;
import com.reportix.aisolate.backend.domain.airlock.DataSourceSecrets;
import com.reportix.aisolate.backend.domain.airlock.InvalidFormException;
import com.reportix.aisolate.backend.domain.airlock.VersionFormDTO;
import com.reportix.aisolate.backend.domain.global.GlobalRepository;
import com.reportix.aisolate.backend.domain.space.Space;
import com.reportix.aisolate.backend.domain.space.SpaceRepository;
import com.reportix.aisolate.backend.infrastructure.cluster.ClusterProperties;
import com.reportix.aisolate.backend.infrastructure.cluster.ClusterSyncService;
import com.reportix.aisolate.config.AirlockVersionDocument;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

/**
 * One deploy of a project into its space: the project is one AirlockVersion, saved with its jar in
 * place when the version exists, created when it does not. Its airlock is the space's, made in the
 * AIsolate UI or by a deploy: the file's Airlock, when it has one, creates it, or saves each field
 * it writes over the stored one on every deploy, as the UI's form would, with a Secret for each
 * data source whose credentials the file carries. The file and the jar must agree: what the file
 * lists, the code carries; the loader and the version's own checks refuse otherwise, each once.
 * Everything or nothing: one transaction, the airlock's save included.
 */
@Service
@RequiredArgsConstructor
public class DeployService {

  /** The handler lists of the file, with their stage in the code and what warnings call it. */
  private record Stage(String pointer, String code, String word) {}

  private static final List<Stage> STAGES =
      List.of(
          new Stage(
              "/spec/preLLM/contextPreparator/handlers", "context_prep", "context preparator"),
          new Stage("/spec/preLLM/conversationMemory/handlers", "memory", "conversation memory"),
          new Stage("/spec/postLLM/answerVerifier/handlers", "verifier", "verifier"));

  private final SpaceRepository spaces;
  private final AirlockRepository airlocks;
  private final AirlockVersionRepository versions;
  private final AirlockService airlockService;
  private final GlobalRepository globals;
  private final ClusterSyncService clusterSync;
  private final ClusterProperties cluster;
  private final TransactionOperations transactions;

  /**
   * @param jar the project's code: its jar, or the zip of a Python project
   * @param language {@code python} for a zip; null or {@code java} for a jar
   * @param documentsYaml the project's {@code aisolate.yaml} as one YAML stream
   * @param variablesJson the values of the {@code ${NAME}} the file reads, as a JSON object
   * @throws com.reportix.aisolate.backend.domain.space.SpaceNotFoundException no such space
   * @throws AirlockNotFoundException the airlock the file names is not in the space, and the file
   *     has no Airlock to create it from
   * @throws ConflictException the airlock id is another space's
   * @throws InvalidFormException the jar, the file, the airlock or the version is refused; nothing
   *     is written
   */
  public DeployResultDTO deploy(
      String spaceId,
      MultipartFile jar,
      String language,
      String documentsYaml,
      String variablesJson,
      Creator actor) {
    spaces.require(spaceId);
    boolean python = isPython(language);
    Path file = readUpload(jar);
    try {
      // A jar's code runs in its scan: outside the transaction, so a constructor that never
      // returns holds no database connection. A Python artifact's manifest is only read.
      JarInspector.Contents contents = python ? ManifestReader.read(file) : JarInspector.read(file);
      DocumentForms.Project project =
          DocumentForms.scan(documentsYaml, DocumentForms.readVariables(variablesJson));
      DeployResultDTO saved =
          transactions.execute(status -> save(spaceId, file, contents, project, actor));
      // Committed: the cluster follows what the database holds.
      return cluster.enabled() ? saved.withCluster(clusterSync.sync(spaceId)) : saved;
    } finally {
      try {
        Files.deleteIfExists(file);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  private DeployResultDTO save(
      String spaceId,
      Path file,
      JarInspector.Contents contents,
      DocumentForms.Project project,
      Creator actor) {
    Space space = spaces.require(spaceId);
    Optional<Airlock> found = airlocks.find(project.airlock());
    if (found.isPresent() && !found.get().spaceId().equals(spaceId)) {
      throw ConflictException.airlockTaken(project.airlock(), spaceId, found.get().spaceId());
    }
    if (found.isEmpty() && project.airlockDocument() == null) {
      throw new AirlockNotFoundException(
          project.airlock(),
          "Airlock "
              + project.airlock()
              + " is not in space "
              + spaceId
              + ": add its Airlock document to aisolate.yaml, or create it in the AIsolate UI");
    }
    Airlock airlock = found.orElse(null);
    boolean airlockCreated = false;
    boolean airlockUpdated = false;
    if (project.airlockDocument() != null) {
      AirlockFormDTO form = DocumentForms.toAirlockForm(project, space, airlock);
      try {
        if (airlock == null) {
          airlock = airlockService.createAirlock(spaceId, form, actor);
          airlockCreated = true;
        } else if (changes(airlock, form)) {
          airlock = airlockService.updateAirlock(spaceId, airlock.id(), form, null);
          airlockUpdated = true;
        }
      } catch (InvalidFormException refused) {
        throw prefixed("Airlock " + project.airlock(), refused);
      }
    }
    AirlockVersionDocument document =
        DocumentForms.parse(project, contents, globals.document(), space, airlock);
    VersionFormDTO form = DocumentForms.toForm(document);
    AirlockVersionRepository.Code code = new AirlockVersionRepository.Code(file, contents);
    boolean created = versions.find(airlock.id(), project.version()).isEmpty();
    AirlockVersion saved;
    try {
      saved =
          created
              ? airlockService.createVersion(spaceId, airlock.id(), form, code, actor)
              : airlockService.updateVersion(
                  spaceId, airlock.id(), project.version(), form, code, null);
    } catch (InvalidFormException refused) {
      throw prefixed("AirlockVersion " + project.name(), refused);
    }
    return new DeployResultDTO(
        new DeployResultDTO.AirlockOutcome(
            airlock.id(), airlock.name(), airlockCreated, airlockUpdated),
        new DeployResultDTO.VersionOutcome(
            saved.version(), saved.description(), saved.revision(), saved.endpoint(), created),
        new DeployResultDTO.Jar(
            contents.digest(),
            contents.toolNames(),
            contents.handlers().stream().map(JarInspector.Handler::name).toList()),
        warnings(project.document(), contents),
        null);
  }

  /**
   * Whether saving the form would change the stored airlock: no save when nothing differs, so a
   * form the AIsolate UI has open is not refused as saved by someone else. A password is always a
   * change: the Secret it replaces is not read back to compare. A model the UI gave a key keeps it
   * while the project's row of its id keeps the URL.
   */
  static boolean changes(Airlock stored, AirlockFormDTO form) {
    AirlockFormDTO wanted =
        form.withDataSources(DataSourceSecrets.keepRefs(stored.dataSources(), form.dataSources()))
            .withLlms(DataSourceSecrets.keepRefs(stored.llms(), form.llms()));
    return !wanted.equals(stored.form())
        || wanted.dataSources().stream().anyMatch(DataSourceFormDTO::bringsCredentials);
  }

  private static InvalidFormException prefixed(String document, InvalidFormException refused) {
    return new InvalidFormException(
        refused.getErrors().stream().map(error -> document + ": " + error).toList());
  }

  /**
   * What the code carries and the file lists nowhere: the model cannot call that tool, that handler
   * never runs. A name the file lists and the code lacks is refused by the loader (tools) and the
   * version's own check (handlers).
   *
   * @param version the project's AirlockVersion as the scan read it
   */
  static List<String> warnings(JsonNode version, JarInspector.Contents code) {
    List<String> warnings = new ArrayList<>();
    List<String> tools = names(version, "/spec/tools");
    code.toolNames().stream()
        .filter(tool -> !tools.contains(tool))
        .forEach(tool ->
            warnings.add(
                "tool " + tool + " of this project is not in tools, so the model cannot call it"));
    for (Stage stage : STAGES) {
      List<String> listed = names(version, stage.pointer());
      code.handlerNames(stage.code()).stream()
          .filter(handler -> !listed.contains(handler))
          .forEach(handler ->
              warnings.add(
                  stage.word()
                      + " "
                      + handler
                      + " of this project is listed nowhere and runs nowhere"));
    }
    return warnings;
  }

  /** The names a list of the file holds; blank ones are the loader's to refuse. */
  private static List<String> names(JsonNode version, String pointer) {
    return version
        .at(pointer)
        .valueStream()
        .map(name -> name.asString(""))
        .filter(name -> !name.isBlank())
        .toList();
  }

  /**
   * @throws InvalidFormException a language AIsolate runs no code in
   */
  private static boolean isPython(String language) {
    if (language == null
        || language.isBlank()
        || language.equals(AirlockVersionDocument.Jar.JAVA)) {
      return false;
    }
    if (language.equals(AirlockVersionDocument.Jar.PYTHON)) {
      return true;
    }
    throw new InvalidFormException(List.of("language: java or python, not " + language));
  }

  /**
   * The uploaded jar as a file of its own, which the caller deletes: never in memory whole.
   *
   * @throws InvalidFormException {@code jar: required} without one
   */
  private static Path readUpload(MultipartFile jar) {
    // A browser sends an empty file input as an empty part: no jar either.
    if (jar == null || jar.isEmpty()) {
      throw new InvalidFormException(List.of("jar: required"));
    }
    try {
      Path file = Files.createTempFile("version-", ".jar");
      // The container's part is on disk already: this moves it when it can, else copies.
      jar.transferTo(file.toFile());
      return file;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
