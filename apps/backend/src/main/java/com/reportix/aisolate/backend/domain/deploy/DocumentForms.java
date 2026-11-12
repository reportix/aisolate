package com.reportix.aisolate.backend.domain.deploy;

import com.reportix.aisolate.backend.domain.airlock.Airlock;
import com.reportix.aisolate.backend.domain.airlock.AirlockFormDTO;
import com.reportix.aisolate.backend.domain.airlock.DataSourceFormDTO;
import com.reportix.aisolate.backend.domain.airlock.Documents;
import com.reportix.aisolate.backend.domain.airlock.InvalidFormException;
import com.reportix.aisolate.backend.domain.airlock.LlmFormDTO;
import com.reportix.aisolate.backend.domain.airlock.VersionFormDTO;
import com.reportix.aisolate.backend.domain.space.Space;
import com.reportix.aisolate.config.AirlockDocument;
import com.reportix.aisolate.config.AirlockVersionDocument;
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
 * A project's {@code aisolate.yaml} as the deploy stores it: one AirlockVersion, naming the airlock
 * it belongs to and its own name there, and at most that airlock's Airlock, which the deploy saves
 * as the AIsolate UI's form would. {@link ProjectDocuments#split} reads the variables and takes the
 * credentials out; a lenient scan finds the version's airlock; then the loader the pods read
 * releases with checks each document together with the stored Global, Space (and Airlock, for the
 * version), so unknown fields and dangling references are refused in its own words, an LLM provider
 * the airlock pod has no client for included; a main LLM that resolves to nothing is refused as the
 * AIsolate UI's form refuses it. Only the stored rows reach the pods: the project's documents
 * become rows, never files of their own.
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
   * The project's AirlockVersion before the strict parse.
   *
   * @param name its {@code metadata.name}, {@code <airlock>-<version>}
   * @param document as the split left it: variables read, credentials out
   * @param airlockDocument the airlock's Airlock as the split left it; null when the file has none
   * @param credentials what the split took out, by {@link ProjectDocuments#dataSourceKey}
   */
  public record Project(
      String name,
      String airlock,
      String version,
      ObjectNode document,
      ObjectNode airlockDocument,
      Map<String, ProjectDocuments.Credentials> credentials) {}

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
   * The project file split and scanned: one AirlockVersion naming its airlock and its version,
   * under the name the two give it, and at most that airlock's Airlock, with no Secret name and no
   * jar of their own.
   *
   * @param documentsYaml the project file as one YAML stream; null or blank for none
   * @throws InvalidFormException an unset variable, a key written twice, a document of another kind
   *     or API group, not exactly one AirlockVersion, more than one Airlock or one of another
   *     airlock, no airlock, or a field that is not the developer's to set
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
    List<ObjectNode> versions = new ArrayList<>();
    List<ObjectNode> airlocks = new ArrayList<>();
    boolean otherKind = false;
    try (MappingIterator<JsonNode> stream =
        YAML.readerFor(JsonNode.class).readValues(split.documents())) {
      while (stream.hasNext()) {
        if (stream.next() instanceof ObjectNode node) {
          switch (node.path("kind").asString("")) {
            case "AirlockVersion" -> versions.add(node);
            case "Airlock" -> airlocks.add(node);
            // A Space or a misspelt kind.
            default -> otherKind = true;
          }
        }
      }
    }
    if (otherKind || versions.size() != 1 || airlocks.size() > 1) {
      throw new InvalidFormException(
          List.of(
              FILE
                  + ": a project is one AirlockVersion and at most its Airlock; the Space is"
                  + " defined in the AIsolate UI"));
    }
    ObjectNode document = versions.getFirst();
    String name = document.path("metadata").path("name").asString("");
    JsonNode spec = document.path("spec");
    String airlock = spec.path("airlock").asString("");
    String version = spec.path("version").asString("");
    String where = FILE + ": " + ("AirlockVersion " + name).strip() + ": ";
    List<String> refused = new ArrayList<>();
    // The loader skips another group's document as not its own: the deploy would find no version.
    String apiVersion = document.path("apiVersion").asString("");
    if (!ConfigurationLoader.API_VERSION.equals(apiVersion)) {
      refused.add(
          where + "apiVersion " + apiVersion + " is not " + ConfigurationLoader.API_VERSION);
    }
    if (airlock.isBlank()) {
      refused.add(where + "spec.airlock is required");
    }
    // The version and the name are the loader's to check, once the airlock is found.
    refuseSecretRefs(spec, where, refused);
    if (spec.has("jar")) {
      refused.add(where + "spec.jar is the deploy's to set");
    }
    ObjectNode airlockDocument = airlocks.isEmpty() ? null : airlocks.getFirst();
    if (airlockDocument != null) {
      String named = airlockDocument.path("metadata").path("name").asString("");
      String at = FILE + ": " + ("Airlock " + named).strip() + ": ";
      String itsApiVersion = airlockDocument.path("apiVersion").asString("");
      if (!ConfigurationLoader.API_VERSION.equals(itsApiVersion)) {
        refused.add(
            at + "apiVersion " + itsApiVersion + " is not " + ConfigurationLoader.API_VERSION);
      }
      if (!named.equals(airlock)) {
        refused.add(at + "metadata.name must be the AirlockVersion's spec.airlock, " + airlock);
      }
      JsonNode itsSpec = airlockDocument.path("spec");
      if (itsSpec.has("space")) {
        refused.add(at + "spec.space is AISOLATE_SPACE's to set; leave it out");
      }
      refuseSecretRefs(itsSpec, at, refused);
    }
    if (!refused.isEmpty()) {
      throw new InvalidFormException(refused);
    }
    return new Project(name, airlock, version, document, airlockDocument, split.credentials());
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
   * The project's Airlock as the AIsolate UI's form saves it, parsed strictly with the stored
   * Space: each field the document writes replaces the stored airlock's, a field it leaves out
   * keeps it, so roles added in the UI survive a document without roles. A new airlock starts with
   * its id as its name and the default callers. Each data source lands with the credentials the
   * split took out; the Secret names are the server's.
   *
   * @param stored the airlock in the space; null when the deploy creates it
   * @throws InvalidFormException every loader problem, verbatim
   */
  public static AirlockFormDTO toAirlockForm(Project project, Space space, Airlock stored) {
    JsonNode written = project.airlockDocument().path("spec");
    ObjectNode copy = project.airlockDocument().deepCopy();
    ObjectNode spec = copy.path("spec") instanceof ObjectNode s ? s : copy.putObject("spec");
    spec.put("space", space.id());
    // The loader wants a caller; a document without them keeps the stored ones, or the default.
    ObjectNode access = spec.path("access") instanceof ObjectNode a ? a : spec.putObject("access");
    if (!access.has("callers")) {
      access.putArray("callers").add("*");
    }
    Map<String, String> files = new LinkedHashMap<>();
    files.put(STORED, ConfigurationLoader.toYaml(List.of(Documents.toSpace(space))));
    files.put(FILE, toYaml(copy));
    AirlockDocument parsed;
    try {
      parsed = ConfigurationLoader.parse(files).airlocks().get(project.airlock());
    } catch (ConfigurationException invalid) {
      throw new InvalidFormException(invalid.problems());
    }
    AirlockFormDTO base =
        stored == null
            ? new AirlockFormDTO(
                project.airlock(), project.airlock(), "", null, null, null, null, null)
            : stored.form();
    JsonNode writtenAccess = written.path("access");
    return new AirlockFormDTO(
        project.airlock(),
        written.has("displayName") ? parsed.displayName() : base.name(),
        written.has("description") ? parsed.spec().description() : base.description(),
        writtenAccess.has("roles") ? parsed.spec().access().roles() : base.roles(),
        writtenAccess.has("callers") ? parsed.spec().access().callers() : base.callers(),
        writtenAccess.has("clients") ? parsed.spec().access().clients() : base.clients(),
        written.has("llms") ? rows(parsed.spec().llms()) : base.llms(),
        written.has("dataSources")
            ? rows(project.airlock(), parsed.spec().dataSources(), project.credentials())
            : base.dataSources());
  }

  /**
   * The project's AirlockVersion with the code the deploy brings, parsed strictly together with the
   * stored Global, Space and Airlock it sees.
   *
   * @throws InvalidFormException every loader problem, verbatim but for the main LLM's, which reads
   *     as the AIsolate UI's form reads it
   */
  public static AirlockVersionDocument parse(
      Project project,
      JarInspector.Contents code,
      GlobalDocument global,
      Space space,
      Airlock airlock) {
    ObjectNode document = project.document().deepCopy();
    ObjectNode spec =
        document.path("spec") instanceof ObjectNode s ? s : document.putObject("spec");
    ObjectNode jar = spec.putObject("jar");
    jar.put("digest", code.digest());
    ArrayNode tools = jar.putArray("tools");
    code.toolNames().forEach(tools::add);
    String language = Documents.documentLanguage(code);
    if (language != null) {
      jar.put("language", language);
    }
    Map<String, String> files = new LinkedHashMap<>();
    files.put(
        STORED,
        ConfigurationLoader.toYaml(
            List.of(global, Documents.toSpace(space), Documents.toAirlock(airlock))));
    files.put(FILE, toYaml(document));
    try {
      return ConfigurationLoader.parse(files).airlockVersions().get(project.name());
    } catch (ConfigurationException invalid) {
      throw new InvalidFormException(
          invalid.problems().stream()
              .map(problem ->
                  problem
                      .replaceFirst(
                          ": llm (\\S+) is no model of it, .*",
                          ": llm: no model named $1 in this airlock's space or globally")
                      .replaceFirst(
                          ": spec\\.llm is required$",
                          ": llm: name the model that answers, for example granite"))
              .toList());
    }
  }

  /** The version as the form saves it: its models keyless, as a project never holds a key. */
  public static VersionFormDTO toForm(AirlockVersionDocument version) {
    AirlockVersionDocument.Spec spec = version.spec();
    return new VersionFormDTO(
        spec.version(),
        spec.description(),
        version.contextPreparatorHandlers(),
        version.memoryHandlers(),
        spec.llm(),
        spec.prompt(),
        spec.tools(),
        version.verifierHandlers(),
        rows(spec.llms()));
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
        .map(dataSource -> {
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
