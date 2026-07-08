package com.reportix.aisolate.backend.domain.deploy;

import com.reportix.aisolate.backend.domain.Creator;
import com.reportix.aisolate.backend.domain.airlock.Airlock;
import com.reportix.aisolate.backend.domain.airlock.AirlockFormDTO;
import com.reportix.aisolate.backend.domain.airlock.AirlockRepository;
import com.reportix.aisolate.backend.domain.airlock.AirlockService;
import com.reportix.aisolate.backend.domain.airlock.ConflictException;
import com.reportix.aisolate.backend.domain.airlock.InvalidFormException;
import com.reportix.aisolate.backend.domain.global.GlobalRepository;
import com.reportix.aisolate.backend.domain.space.Space;
import com.reportix.aisolate.backend.domain.space.SpaceRepository;
import com.reportix.aisolate.backend.infrastructure.cluster.ClusterProperties;
import com.reportix.aisolate.backend.infrastructure.cluster.ClusterSyncService;
import com.reportix.aisolate.config.AirlockDocument;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.multipart.MultipartFile;

/**
 * One deploy of a project into its space: the project is one Airlock, saved with its jar in place
 * when it exists, created when it does not, and a Secret for each data source whose credentials the
 * file carries. Each field the file writes replaces the stored one, as the UI's form would; a field
 * it leaves out keeps what is stored. The file and the jar must agree: what the file lists, the
 * code carries; the loader and the airlock's own checks refuse otherwise, each once. Everything or
 * nothing: one transaction.
 */
@Service
@RequiredArgsConstructor
public class DeployService {

  /** The handler lists of the form, with their stage in the code and what warnings call it. */
  private record Stage(Function<AirlockFormDTO, List<String>> listed, String code, String word) {}

  private static final List<Stage> STAGES =
      List.of(
          new Stage(AirlockFormDTO::contextPreparators, "context_prep", "context preparator"),
          new Stage(AirlockFormDTO::memoryHandlers, "memory", "conversation memory"),
          new Stage(AirlockFormDTO::verifiers, "verifier", "verifier"));

  private final SpaceRepository spaces;
  private final AirlockRepository airlocks;
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
   * @throws ConflictException the airlock id is another space's
   * @throws InvalidFormException the jar, the file or the airlock is refused; nothing is written
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
    Airlock stored = found.orElse(null);
    AirlockFormDTO form =
        DocumentForms.toForm(project, contents, globals.document(), space, stored);
    AirlockRepository.Code code = new AirlockRepository.Code(file, contents);
    Airlock saved;
    try {
      saved =
          stored == null
              ? airlockService.createAirlock(spaceId, form, code, actor)
              : airlockService.updateAirlock(spaceId, stored.id(), form, code, null);
    } catch (InvalidFormException refused) {
      throw prefixed("Airlock " + project.airlock(), refused);
    }
    return new DeployResultDTO(
        new DeployResultDTO.AirlockOutcome(
            saved.id(), saved.name(), saved.revision(), saved.endpoint(), stored == null),
        new DeployResultDTO.Jar(
            contents.digest(),
            contents.toolNames(),
            contents.handlers().stream().map(JarInspector.Handler::name).toList()),
        warnings(form, contents),
        null);
  }

  private static InvalidFormException prefixed(String document, InvalidFormException refused) {
    return new InvalidFormException(
        refused.getErrors().stream().map(error -> document + ": " + error).toList());
  }

  /**
   * What the code carries and the airlock lists nowhere: the model cannot call that tool, that
   * handler never runs. A name listed and the code lacks is refused by the loader (tools) and the
   * airlock's own check (handlers).
   *
   * @param form the airlock as the deploy saves it: the file's lists over the stored ones
   */
  static List<String> warnings(AirlockFormDTO form, JarInspector.Contents code) {
    List<String> warnings = new ArrayList<>();
    code.toolNames().stream()
        .filter(tool -> !form.tools().contains(tool))
        .forEach(
            tool ->
                warnings.add(
                    "tool "
                        + tool
                        + " of this project is not in tools, so the model cannot call it"));
    for (Stage stage : STAGES) {
      List<String> listed = stage.listed().apply(form);
      code.handlerNames(stage.code()).stream()
          .filter(handler -> !listed.contains(handler))
          .forEach(
              handler ->
                  warnings.add(
                      stage.word()
                          + " "
                          + handler
                          + " of this project is listed nowhere and runs nowhere"));
    }
    return warnings;
  }

  /**
   * @throws InvalidFormException a language AIsolate runs no code in
   */
  private static boolean isPython(String language) {
    if (language == null || language.isBlank() || language.equals(AirlockDocument.Jar.JAVA)) {
      return false;
    }
    if (language.equals(AirlockDocument.Jar.PYTHON)) {
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
      Path file = Files.createTempFile("code-", ".jar");
      // The container's part is on disk already: this moves it when it can, else copies.
      jar.transferTo(file.toFile());
      return file;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
