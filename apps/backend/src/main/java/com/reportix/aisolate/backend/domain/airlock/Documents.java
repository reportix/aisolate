package com.reportix.aisolate.backend.domain.airlock;

import com.reportix.aisolate.backend.domain.deploy.JarInspector;
import com.reportix.aisolate.backend.domain.space.Space;
import com.reportix.aisolate.config.AirlockDocument;
import com.reportix.aisolate.config.AirlockVersionDocument;
import com.reportix.aisolate.config.DataSource;
import com.reportix.aisolate.config.Metadata;
import com.reportix.aisolate.config.SpaceDocument;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/**
 * The rows of one space as {@code aisolate.dev/v1} documents, the shapes the AIsolate UI defines:
 * the Space with its roles, LLMs and data sources; every Airlock naming its space, with who may ask
 * it besides the space's roles and its own LLMs and data sources, versions or not; and every
 * AirlockVersion naming its airlock, what runs, from its definition, with its code's digest and
 * tools once a deploy brought them.
 */
public final class Documents {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /**
   * @param versions every version of the space's airlocks
   * @return the Space, then the Airlocks, then the AirlockVersions: the Global is the install's,
   *     applied once, not per space
   */
  public static List<Object> toDocuments(
      Space space, List<Airlock> airlocks, List<AirlockVersionRepository.Row> versions) {
    List<Object> documents = new ArrayList<>();
    documents.add(toSpace(space));
    airlocks.forEach(airlock -> documents.add(toAirlock(airlock)));
    versions.forEach(row -> documents.add(toAirlockVersion(row)));
    return documents;
  }

  public static SpaceDocument toSpace(Space space) {
    return new SpaceDocument(
        new Metadata(space.id()),
        new SpaceDocument.Spec(
            space.name(),
            space.description(),
            new SpaceDocument.Access(space.roles()),
            LlmFormDTO.toLlms(space.llms()),
            toDataSources(space.dataSources())));
  }

  public static AirlockDocument toAirlock(Airlock airlock) {
    return new AirlockDocument(
        new Metadata(airlock.id()),
        new AirlockDocument.Spec(
            airlock.spaceId(),
            airlock.name(),
            airlock.description(),
            new AirlockDocument.Access(airlock.roles(), airlock.callers(), airlock.clients()),
            LlmFormDTO.toLlms(airlock.llms()),
            toDataSources(airlock.dataSources())));
  }

  /** One stored version as its document: the flat definition, grouped as the CRD holds it. */
  public static AirlockVersionDocument toAirlockVersion(AirlockVersionRepository.Row row) {
    VersionFormDTO form = VersionFormDTO.from(row, JSON);
    return new AirlockVersionDocument(
        new Metadata(row.name()),
        new AirlockVersionDocument.Spec(
            row.airlockId(),
            row.version(),
            row.description(),
            new AirlockVersionDocument.PreLlm(
                new AirlockVersionDocument.ContextPreparator(form.contextPreparators()),
                new AirlockVersionDocument.ConversationMemory(form.memoryHandlers())),
            orNull(form.llm()),
            form.prompt(),
            form.tools(),
            new AirlockVersionDocument.PostLlm(
                new AirlockVersionDocument.AnswerVerifier(form.verifiers())),
            LlmFormDTO.toLlms(form.llms()),
            row.jar() == null
                ? null
                : new AirlockVersionDocument.Jar(
                    row.jar().digest(), row.jar().toolNames(), documentLanguage(row.jar()))));
  }

  /**
   * {@code python} for Python code; null for a jar, so a Java version's document, and its revision,
   * stay as they were before languages.
   */
  public static String documentLanguage(JarInspector.Contents code) {
    return AirlockVersionDocument.Jar.PYTHON.equals(code.language()) ? code.language() : null;
  }

  private static List<DataSource> toDataSources(List<DataSourceFormDTO> rows) {
    return rows.stream().map(DataSourceFormDTO::toDataSource).toList();
  }

  /** A blank optional field leaves the document instead of reading as a value. */
  private static String orNull(String value) {
    return value == null || value.isEmpty() ? null : value;
  }

  private Documents() {}
}
