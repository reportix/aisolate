package com.reportix.aisolate.backend.domain.airlock;

import com.reportix.aisolate.backend.domain.deploy.JarInspector;
import com.reportix.aisolate.backend.domain.space.Space;
import com.reportix.aisolate.config.AirlockDocument;
import com.reportix.aisolate.config.DataSource;
import com.reportix.aisolate.config.Metadata;
import com.reportix.aisolate.config.SpaceDocument;
import java.util.ArrayList;
import java.util.List;

/**
 * The rows of one space as {@code aisolate.dev/v1} documents, the shapes the AIsolate UI defines:
 * the Space with its users, LLMs and data sources; and every Airlock naming its space, with who may
 * ask it besides the space's users, its own LLMs and data sources, what runs, from its definition,
 * and its code's digest and tools once a deploy brought them.
 */
public final class Documents {

  /**
   * @return the Space, then the Airlocks: the Global is the install's, applied once, not per space
   */
  public static List<Object> toDocuments(Space space, List<Airlock> airlocks) {
    List<Object> documents = new ArrayList<>();
    documents.add(toSpace(space));
    airlocks.forEach(airlock -> documents.add(toAirlock(airlock)));
    return documents;
  }

  public static SpaceDocument toSpace(Space space) {
    return new SpaceDocument(
        new Metadata(space.id()),
        new SpaceDocument.Spec(
            space.name(),
            space.description(),
            new SpaceDocument.Access(space.users()),
            LlmFormDTO.toLlms(space.llms()),
            toDataSources(space.dataSources())));
  }

  /** One stored airlock as its document: the flat definition, grouped as the CRD holds it. */
  public static AirlockDocument toAirlock(Airlock airlock) {
    AirlockFormDTO form = airlock.form();
    return new AirlockDocument(
        new Metadata(airlock.id()),
        new AirlockDocument.Spec(
            airlock.spaceId(),
            airlock.name(),
            airlock.description(),
            new AirlockDocument.Access(airlock.users(), airlock.callers(), airlock.clients()),
            new AirlockDocument.PreLlm(
                new AirlockDocument.ContextPreparator(form.contextPreparators()),
                new AirlockDocument.ConversationMemory(form.memoryHandlers())),
            orNull(form.llm()),
            orNull(form.prompt()),
            form.tools(),
            new AirlockDocument.PostLlm(new AirlockDocument.AnswerVerifier(form.verifiers())),
            LlmFormDTO.toLlms(airlock.llms()),
            toDataSources(airlock.dataSources()),
            airlock.jar() == null
                ? null
                : new AirlockDocument.Jar(
                    airlock.jar().digest(),
                    airlock.jar().toolNames(),
                    documentLanguage(airlock.jar()))));
  }

  /**
   * {@code python} for Python code; null for a jar, so a Java airlock's document, and its revision,
   * stay as they were before languages.
   */
  public static String documentLanguage(JarInspector.Contents code) {
    return AirlockDocument.Jar.PYTHON.equals(code.language()) ? code.language() : null;
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
