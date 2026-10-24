package com.reportix.aisolate.backend.domain.deploy;

import com.reportix.aisolate.backend.domain.airlock.Airlock;
import com.reportix.aisolate.backend.domain.airlock.AirlockFormDTO;
import com.reportix.aisolate.backend.domain.airlock.DataSourceFormDTO;
import com.reportix.aisolate.backend.domain.airlock.Documents;
import com.reportix.aisolate.backend.domain.airlock.InvalidFormException;
import com.reportix.aisolate.backend.domain.airlock.LlmFormDTO;
import com.reportix.aisolate.backend.domain.space.Space;
import com.reportix.aisolate.config.AirlockDocument;
import com.reportix.aisolate.config.ConfigurationException;
import com.reportix.aisolate.config.ConfigurationLoader;
import com.reportix.aisolate.config.DataSource;
import com.reportix.aisolate.config.GlobalDocument;
import com.reportix.aisolate.config.Llm;
import com.reportix.aisolate.config.ProjectDocuments;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MappingIterator;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * A project's {@code aisolate.yaml} as the deploy stores it: one Airlock, which the deploy saves as
 * the AIsolate UI's form would. {@link ProjectDocuments#split} reads the variables and takes the
 * credentials out; a lenient scan finds the airlock; then the loader the pods read releases with
 * checks the document, with the code the deploy brings, together with the stored Global and Space,
 * so unknown fields and dangling references are refused in its own words, an LLM provider the
 * airlock pod has no client for included; a main LLM that resolves to nothing is refused as the
 * AIsolate UI's form refuses it. Only the stored row reaches the pods: the project's Airlock
 * becomes one, never a file of its own.
 */
public final class DocumentForms {

  /** The project file's name, what the messages call the documents. */
  static final String FILE = "aisolate.yaml";

  private static final String STORED = "00-synthesised.yaml";

  private static final YAMLMapper YAML =
      YAMLMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static final TypeReference<Map<String, String>> VARIABLES = new TypeReference<>() {};

  /**
   * The project's Airlock before the strict parse.
   *
   * @param airlock its {@code metadata.name}, the airlock's id
   * @param document as the split left it: variables read, credentials out
   * @param credentials what the split took out, by {@link ProjectDocuments#dataSourceKey}
   */
  public record Project(
      String airlock, ObjectNode document, Map<String, ProjectDocuments.Credentials> credentials) {}

  /**
   * The {@code variables} part: names to values; no value is ever echoed.
   *
   * @throws InvalidFormException not a JSON object of names to values
   */
  public static Map<String, String> readVariables(String variablesJson) {
    if (variablesJson == null || variablesJson.isBlank()) {
      return Map.of();
    }
    try {
      Map<String, String> variables = new LinkedHashMap<>(JSON.readValue(variablesJson, VARIABLES));
      variables.values().removeIf(Objects::isNull);
      return variables;
    } catch (RuntimeException invalid) {
      throw new InvalidFormException(List.of("variables: a JSON object of names to values"));
    }
  }

  /**
   * The project file split and scanned: one Airlock, named by its id, with no space, no Secret name
   * and no jar of its own.
   *
   * @param documentsYaml the project file as one YAML stream; null or blank for none
   * @throws InvalidFormException an unset variable, a key written twice, a document of another kind
   *     or API group, not exactly one Airlock, or a field that is not the developer's to set
   */
  public static Project scan(String documentsYaml, Map<String, String> variables) {
    ProjectDocuments.Split split;
    try {
      split = ProjectDocuments.split(documentsYaml == null ? "" : documentsYaml, variables);
    } catch (ConfigurationException unset) {
      throw new InvalidFormException(unset.problems());
    } catch (RuntimeException unreadable) {
      throw new InvalidFormException(
          List.of(FILE + ": " + ConfigurationLoader.firstLine(unreadable.getMessage())));
    }
    List<ObjectNode> airlocks = new ArrayList<>();
    boolean otherKind = false;
    try (MappingIterator<JsonNode> stream =
        YAML.readerFor(JsonNode.class).readValues(split.documents())) {
      while (stream.hasNext()) {
        if (stream.next() instanceof ObjectNode node) {
          if ("Airlock".equals(node.path("kind").asString(""))) {
            airlocks.add(node);
          } else {
            // A Space or a misspelt kind.
            otherKind = true;
          }
        }
      }
    }
    if (otherKind || airlocks.size() != 1) {
      throw new InvalidFormException(
          List.of(FILE + ": a project is one Airlock; the Space is defined in the AIsolate UI"));
    }
    ObjectNode document = airlocks.getFirst();
    String name = document.path("metadata").path("name").asString("");
    JsonNode spec = document.path("spec");
    String where = FILE + ": " + ("Airlock " + name).strip() + ": ";
    List<String> refused = new ArrayList<>();
    // The loader skips another group's document as not its own: the deploy would find no airlock.
    String apiVersion = document.path("apiVersion").asString("");
    if (!ConfigurationLoader.API_VERSION.equals(apiVersion)) {
      refused.add(
          where + "apiVersion " + apiVersion + " is not " + ConfigurationLoader.API_VERSION);
    }
    if (name.isBlank()) {
      refused.add(where + "metadata.name is required");
    }
    if (spec.has("space")) {
      refused.add(where + "spec.space is AISOLATE_SPACE's to set; leave it out");
    }
    refuseSecretRefs(spec, where, refused);
    if (spec.has("jar")) {
      refused.add(where + "spec.jar is the deploy's to set");
    }
    if (!refused.isEmpty()) {
      throw new InvalidFormException(refused);
    }
    return new Project(name, document, split.credentials());
  }

  // A Secret name is AIsolate's to give: a sent one could name any Secret in reach.
  private static void refuseSecretRefs(JsonNode spec, String where, List<String> refused) {
    JsonNode dataSources = spec.path("dataSources");
    for (int i = 0; dataSources.isArray() && i < dataSources.size(); i++) {
      if (!dataSources.path(i).path("secretRef").asString("").isBlank()) {
        refused.add(
            where
                + "spec.dataSources["
                + i
                + "].secretRef is AIsolate's to set; give username and password");
      }
    }
    JsonNode llms = spec.path("llms");
    for (int i = 0; llms.isArray() && i < llms.size(); i++) {
      if (!llms.path(i).path("secretRef").asString("").isBlank()) {
        refused.add(
            where
                + "spec.llms["
                + i
                + "].secretRef is AIsolate's to set; give the model's API key in the AIsolate UI");
      }
    }
  }

  /**
   * The project's Airlock with the code the deploy brings, parsed strictly together with the stored
   * Global and Space it sees, as the AIsolate UI's form saves it: each field the document writes
   * replaces the stored airlock's, a field it leaves out keeps it, so roles added in the UI survive
   * a document without roles, and the prompt a deploy without one. A new airlock starts with its id
   * as its name and the default callers. Each data source lands with the credentials the split took
   * out; the Secret names are the server's.
   *
   * @param stored the airlock in the space; null when the deploy creates it
   * @throws InvalidFormException every loader problem, verbatim but for the main LLM's, which reads
   *     as the AIsolate UI's form reads it
   */
  public static AirlockFormDTO toForm(
      Project project,
      JarInspector.Contents code,
      GlobalDocument global,
      Space space,
      Airlock stored) {
    JsonNode written = project.document().path("spec");
    ObjectNode copy = project.document().deepCopy();
    ObjectNode spec = copy.path("spec") instanceof ObjectNode s ? s : copy.putObject("spec");
    spec.put("space", space.id());
    // The loader wants a caller; a document without them keeps the stored ones, or the default.
    ObjectNode access = spec.path("access") instanceof ObjectNode a ? a : spec.putObject("access");
    if (!access.has("callers")) {
      access.putArray("callers").add("*");
    }
    ObjectNode jar = spec.putObject("jar");
    jar.put("digest", code.digest());
    ArrayNode tools = jar.putArray("tools");
    code.toolNames().forEach(tools::add);
    String language = Documents.documentLanguage(code);
    if (language != null) {
      jar.put("language", language);
    }
    Map<String, String> files = new LinkedHashMap<>();
    files.put(STORED, ConfigurationLoader.toYaml(List.of(global, Documents.toSpace(space))));
    files.put(FILE, toYaml(copy));
    AirlockDocument parsed;
    try {
      parsed = ConfigurationLoader.parse(files).airlocks().get(project.airlock());
    } catch (ConfigurationException invalid) {
      throw new InvalidFormException(
          invalid.problems().stream()
              .map(
                  problem ->
                      problem.replaceFirst(
                          ": llm (\\S+) is no model of it, .*",
                          ": llm: no model named $1 in this airlock's space or globally"))
              .toList());
    }
    AirlockFormDTO base =
        stored == null
            ? new AirlockFormDTO(
                project.airlock(),
                project.airlock(),
                "",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null)
            : stored.form();
    JsonNode writtenAccess = written.path("access");
    return new AirlockFormDTO(
        project.airlock(),
        written.has("displayName") ? parsed.displayName() : base.name(),
        written.has("description") ? parsed.spec().description() : base.description(),
        writtenAccess.has("roles") ? parsed.spec().access().roles() : base.roles(),
        writtenAccess.has("callers") ? parsed.spec().access().callers() : base.callers(),
        writtenAccess.has("clients") ? parsed.spec().access().clients() : base.clients(),
        writes(written, "/preLLM/contextPreparator/handlers")
            ? parsed.contextPreparatorHandlers()
            : base.contextPreparators(),
        writes(written, "/preLLM/conversationMemory/handlers")
            ? parsed.memoryHandlers()
            : base.memoryHandlers(),
        written.has("llm") ? parsed.spec().llm() : base.llm(),
        written.has("prompt") ? parsed.spec().prompt() : base.prompt(),
        written.has("tools") ? parsed.tools() : base.tools(),
        writes(written, "/postLLM/answerVerifier/handlers")
            ? parsed.verifierHandlers()
            : base.verifiers(),
        written.has("llms") ? rows(parsed.spec().llms()) : base.llms(),
        written.has("dataSources")
            ? rows(project.airlock(), parsed.spec().dataSources(), project.credentials())
            : base.dataSources());
  }

  private static boolean writes(JsonNode spec, String pointer) {
    return !spec.at(pointer).isMissingNode();
  }

  /** A document's models as form rows: no key, which a project never holds. */
  private static List<LlmFormDTO> rows(List<Llm> llms) {
    return llms.stream().map(LlmFormDTO::of).toList();
  }

  /** A document's data sources as form rows, each with the credentials the split took out. */
  private static List<DataSourceFormDTO> rows(
      String document,
      List<DataSource> dataSources,
      Map<String, ProjectDocuments.Credentials> credentials) {
    return dataSources.stream()
        .map(
            dataSource -> {
              ProjectDocuments.Credentials login =
                  credentials.get(ProjectDocuments.dataSourceKey(document, dataSource.id()));
              return new DataSourceFormDTO(
                  dataSource.id(),
                  dataSource.url(),
                  null,
                  login == null ? null : login.username(),
                  login == null ? null : login.password());
            })
        .toList();
  }

  private static String toYaml(JsonNode node) {
    String text = YAML.writeValueAsString(node);
    return (text.startsWith("---") ? "" : "---\n") + text;
  }

  private DocumentForms() {}
}
