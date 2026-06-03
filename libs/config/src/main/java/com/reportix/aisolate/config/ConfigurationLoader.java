package com.reportix.aisolate.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MappingIterator;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLWriteFeature;

/**
 * Reads a directory of {@code aisolate.dev/v1} documents into one {@link Configuration}. Every
 * problem is collected and reported at once, each naming its file, document and field, so {@code
 * aisolate validate} shows the whole picture. Unknown fields are problems: a typo must fail here,
 * not silently do nothing later.
 */
public final class ConfigurationLoader {

  public static final String GROUP = "aisolate.dev";
  public static final String API_VERSION = GROUP + "/v1";

  // Map keys sorted on the way out: a resource read back from a Kubernetes API server carries its
  // keys sorted, and the canonical form (the revision) must not depend on who serialised it.
  private static final YAMLMapper YAML =
      YAMLMapper.builder()
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          // A key written twice would otherwise load its last value, silently.
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(YAMLWriteFeature.MINIMIZE_QUOTES)
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .changeDefaultPropertyInclusion(
              inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_NULL))
          .build();

  private ConfigurationLoader() {}

  /** Every {@code .yaml} or {@code .yml} file in the directory, in name order. */
  public static Configuration load(Path directory) {
    Map<String, String> files = new LinkedHashMap<>();
    try (Stream<Path> list = Files.list(directory)) {
      for (Path file : list.sorted().toList()) {
        String name = file.getFileName().toString();
        if (name.endsWith(".yaml") || name.endsWith(".yml")) {
          files.put(name, Files.readString(file, StandardCharsets.UTF_8));
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return parse(files);
  }

  /**
   * The documents a pod serves, as the controller renders its env: the mounted directory, and the
   * revision computed for it, which must be the one the files hash to (blank: theirs).
   *
   * @throws IllegalStateException no directory
   */
  public static Configuration load(String dir, String revision) {
    if (dir == null || dir.isBlank()) {
      throw new IllegalStateException(
          "AISOLATE_CONFIG_DIR is required: the directory of the release's documents");
    }
    return load(Path.of(dir)).declaredAs(revision);
  }

  /** Files by name; the revision is computed over them in this order. */
  public static Configuration parse(Map<String, String> filesByName) {
    Documents documents = new Documents();
    for (Map.Entry<String, String> file : filesByName.entrySet()) {
      try (MappingIterator<JsonNode> stream =
          YAML.readerFor(JsonNode.class).readValues(file.getValue())) {
        while (stream.hasNext()) {
          JsonNode node = stream.next();
          if (node != null && node.isObject()) {
            documents.read(file.getKey(), node);
          }
        }
      } catch (RuntimeException unreadable) {
        documents.problems.add(file.getKey() + ": " + firstLine(unreadable.getMessage()));
      }
    }
    if (documents.count == 0 && documents.problems.isEmpty()) {
      documents.problems.add("no documents: expected .yaml files with apiVersion " + API_VERSION);
    }
    documents.validateReferences();
    if (!documents.problems.isEmpty()) {
      throw new ConfigurationException(documents.problems);
    }
    GlobalDocument global = documents.global();
    return new Configuration(
        revision(global, documents.spaces, documents.airlocks),
        global,
        documents.spaces,
        documents.airlocks);
  }

  /** YAML, one document per object, {@code ---} between them. */
  public static String toYaml(List<?> documents) {
    StringBuilder out = new StringBuilder();
    for (Object document : documents) {
      out.append(canonical(document));
    }
    return out.toString();
  }

  /**
   * One document, {@code ---} first: the same bytes whoever serialised it (file, backend,
   * Kubernetes API server), so the revision is a name every side agrees on.
   */
  public static String canonical(Object document) {
    JsonNode node = YAML.valueToTree(document);
    // The records carry the name in metadata; apiVersion and kind are ours to add.
    ObjectNode framed = YAML.createObjectNode();
    framed.put("apiVersion", API_VERSION);
    framed.put("kind", kind(document));
    framed.set("metadata", node.get("metadata"));
    framed.set("spec", node.get("spec"));
    String yaml = YAML.writeValueAsString(framed);
    yaml = yaml.startsWith("---") ? yaml : "---\n" + yaml;
    return yaml.endsWith("\n") ? yaml : yaml + "\n";
  }

  /** The file a document travels in to a pod: {@code <Kind>-<name>.yaml}. */
  public static String fileName(Object document) {
    return kind(document) + "-" + name(document) + ".yaml";
  }

  /** The Kubernetes kind of a document record: {@code Global}, {@code Space}, {@code Airlock}. */
  public static String kind(Object document) {
    return document.getClass().getSimpleName().replace("Document", "");
  }

  /**
   * First twelve hex digits of SHA-256 over every document in canonical form, sorted by kind and
   * name: formatting, file names and key order do not matter, any value does. A missing Global
   * counts as the empty one it reads as.
   */
  static String revision(
      GlobalDocument global,
      Map<String, SpaceDocument> spaces,
      Map<String, AirlockDocument> airlocks) {
    try {
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      List<Object> all = new ArrayList<>();
      all.add(global);
      all.addAll(spaces.values());
      all.addAll(airlocks.values());
      all.sort(
          Comparator.comparing(ConfigurationLoader::kind).thenComparing(ConfigurationLoader::name));
      for (Object document : all) {
        sha.update((kind(document) + "/" + name(document)).getBytes(StandardCharsets.UTF_8));
        sha.update((byte) 0);
        sha.update(canonical(document).getBytes(StandardCharsets.UTF_8));
      }
      return HexFormat.of().formatHex(sha.digest()).substring(0, 12);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String name(Object document) {
    return switch (document) {
      case GlobalDocument g -> g.name();
      case SpaceDocument s -> s.name();
      case AirlockDocument a -> a.name();
      default -> throw new IllegalArgumentException("not a document: " + document.getClass());
    };
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  /** A parser's message without its trace of the input: its first line. */
  public static String firstLine(String message) {
    return message == null ? "invalid" : message.lines().findFirst().orElse("invalid");
  }

  /** The documents of one parse, with the file each came from and the problems found so far. */
  private static final class Documents {
    final Map<String, GlobalDocument> globals = new LinkedHashMap<>();
    final Map<String, SpaceDocument> spaces = new LinkedHashMap<>();
    final Map<String, AirlockDocument> airlocks = new LinkedHashMap<>();
    final Map<String, String> fileOf = new LinkedHashMap<>();
    final List<String> problems = new ArrayList<>();
    int count;

    GlobalDocument global() {
      return globals.getOrDefault(GlobalDocument.NAME, GlobalDocument.empty());
    }

    void read(String file, JsonNode node) {
      String apiVersion = node.path("apiVersion").asString(null);
      // A release may hold other objects (Secrets, say): another API group is not ours.
      if (!blank(apiVersion) && !apiVersion.startsWith(GROUP + "/")) {
        return;
      }
      count++;
      String kind = node.path("kind").asString("");
      // path(), not get(): a missing metadata reads as a blank name, a problem like any other.
      String name = node.path("metadata").path("name").asString("");
      String where = file + ": " + (kind + " " + name).strip();
      if (!API_VERSION.equals(apiVersion)) {
        problems.add(where + ": apiVersion " + apiVersion + " is not " + API_VERSION);
        return;
      }
      if (!Names.SLUG.matcher(name).matches()) {
        problems.add(where + ": metadata.name '" + name + "': " + Names.SLUG_RULE);
        return;
      }
      // The frame (apiVersion, kind) is ours; the records carry metadata and spec only.
      ObjectNode body = ((ObjectNode) node).deepCopy();
      body.remove("apiVersion");
      body.remove("kind");
      try {
        switch (kind) {
          case "Global" ->
              put(globals, YAML.treeToValue(body, GlobalDocument.class), name, file, where);
          case "Space" ->
              put(spaces, YAML.treeToValue(body, SpaceDocument.class), name, file, where);
          case "Airlock" ->
              put(airlocks, YAML.treeToValue(body, AirlockDocument.class), name, file, where);
          default -> problems.add(where + ": unknown kind " + kind);
        }
      } catch (RuntimeException invalid) {
        problems.add(where + ": " + firstLine(invalid.getMessage()));
      }
    }

    private <T> void put(Map<String, T> into, T document, String name, String file, String where) {
      String key = document.getClass().getSimpleName() + "/" + name;
      if (into.containsKey(name)) {
        problems.add(where + ": already defined in " + fileOf.get(key));
        return;
      }
      into.put(name, document);
      fileOf.put(key, file);
    }

    private String where(Object document, String kind, String name) {
      return fileOf.get(document.getClass().getSimpleName() + "/" + name)
          + ": "
          + kind
          + " "
          + name;
    }

    void validateReferences() {
      for (GlobalDocument global : globals.values()) {
        String where = where(global, "Global", global.name());
        // Any other name is a second Global: the install has one.
        if (!GlobalDocument.NAME.equals(global.name())) {
          problems.add(where + ": metadata.name must be " + GlobalDocument.NAME);
        }
        validateLlms(global.spec().llms(), where + ": spec.llms");
        validateDataSources(global.spec().dataSources(), where + ": spec.dataSources");
      }
      for (SpaceDocument space : spaces.values()) {
        String where = where(space, "Space", space.name());
        SpaceDocument.Spec spec = space.spec();
        if (spec == null) {
          problems.add(where + ": spec is required");
          continue;
        }
        validateUserNames(spec.access() == null ? List.of() : spec.access().users(), where);
        validateLlms(spec.llms(), where + ": spec.llms");
        validateDataSources(spec.dataSources(), where + ": spec.dataSources");
      }
      for (AirlockDocument airlock : airlocks.values()) {
        String where = where(airlock, "Airlock", airlock.name());
        AirlockDocument.Spec spec = airlock.spec();
        if (spec == null) {
          problems.add(where + ": spec is required");
          continue;
        }
        SpaceDocument space = blank(spec.space()) ? null : spaces.get(spec.space());
        if (blank(spec.space())) {
          problems.add(where + ": spec.space is required");
        } else if (space == null) {
          problems.add(where + ": spec.space " + spec.space() + " is no Space");
        }
        validateAirlockAccess(spec.access(), where);
        validateRun(airlock, where);
        validateCode(airlock, where);
        if (space != null && space.spec() != null && !blank(spec.llm())) {
          validateMainLlm(airlock, space, where);
        }
      }
    }

    private void validateUserNames(List<String> users, String where) {
      Names.userProblems(users)
          .forEach(problem -> problems.add(where + ": spec.access.users " + problem));
    }

    /**
     * Who may ask besides the space's users: any users of its own, at least one calling client, and
     * any machine clients.
     */
    private void validateAirlockAccess(AirlockDocument.Access access, String where) {
      List<String> callers = access == null ? List.of() : access.callers();
      validateUserNames(access == null ? List.of() : access.users(), where);
      if (callers.isEmpty()) {
        problems.add(where + ": spec.access.callers " + Names.NO_CALLER);
      }
      validateNames("client", callers, where + ": spec.access.callers");
      Names.clientProblems(access == null ? List.of() : access.clients())
          .forEach(problem -> problems.add(where + ": spec.access.clients " + problem));
    }

    /** What an exchange runs, in its order; the llm and the prompt may still be absent. */
    private void validateRun(AirlockDocument airlock, String where) {
      AirlockDocument.Spec spec = airlock.spec();
      validateNames(
          "name",
          airlock.contextPreparatorHandlers(),
          where + ": spec.preLLM.contextPreparator.handlers");
      validateNames(
          "name", airlock.memoryHandlers(), where + ": spec.preLLM.conversationMemory.handlers");
      validateNames("name", spec.tools(), where + ": spec.tools");
      validateNames(
          "name", airlock.verifierHandlers(), where + ": spec.postLLM.answerVerifier.handlers");
      validateLlms(spec.llms(), where + ": spec.llms");
      validateDataSources(spec.dataSources(), where + ": spec.dataSources");
    }

    /** Tools and handlers are the code's: none without a jar, only the jar's tools with one. */
    private void validateCode(AirlockDocument airlock, String where) {
      AirlockDocument.Jar jar = airlock.spec().jar();
      if (jar == null) {
        if (Stream.of(
                airlock.tools(),
                airlock.contextPreparatorHandlers(),
                airlock.memoryHandlers(),
                airlock.verifierHandlers())
            .anyMatch(names -> !names.isEmpty())) {
          problems.add(where + ": lists tools or handlers but has no jar");
        }
        return;
      }
      if (blank(jar.digest())) {
        problems.add(where + ": spec.jar.digest is required");
      }
      if (jar.language() != null
          && !jar.language().equals(AirlockDocument.Jar.JAVA)
          && !jar.python()) {
        problems.add(where + ": spec.jar.language " + jar.language() + " is not java or python");
      }
      Names.notInCode("a tool", airlock.tools(), jar.tools())
          .forEach(problem -> problems.add(where + ": spec.tools " + problem));
    }

    /** The LLM that answers is defined on the airlock, its space or globally. */
    private void validateMainLlm(AirlockDocument airlock, SpaceDocument space, String where) {
      Configuration resolved = new Configuration(null, global(), spaces, airlocks);
      if (resolved.mainLlmOf(airlock.name()) == null) {
        problems.add(
            where
                + ": llm "
                + airlock.spec().llm()
                + " is no model of it, of Space "
                + space.name()
                + " or of AIsolate");
      }
    }

    /** An LLM has every field; one list holds an id once, a nearer one replaces it by design. */
    private void validateLlms(List<Llm> llms, String at) {
      Set<String> ids = new HashSet<>();
      for (int i = 0; i < llms.size(); i++) {
        Llm llm = llms.get(i);
        String entry = at + "[" + i + "]";
        if (blank(llm.id())) {
          problems.add(entry + ".id is required");
        } else if (!Names.LLM_ID.matcher(llm.id()).matches()) {
          problems.add(entry + ".id " + llm.id() + ": " + Names.LLM_ID_RULE);
        } else if (!ids.add(llm.id())) {
          problems.add(entry + ".id " + llm.id() + " is listed twice");
        }
        String provider = AirlockDocument.providerProblem(llm.provider());
        if (blank(llm.provider())) {
          problems.add(entry + ".provider is required");
        } else if (provider != null) {
          problems.add(entry + ".provider " + provider);
        }
        if (blank(llm.url())) {
          problems.add(entry + ".url is required");
        }
        validateUrl(llm.url(), entry + ".url");
        if (blank(llm.model())) {
          problems.add(entry + ".model is required");
        }
      }
    }

    /** One list holds an id once; the same id on a deeper document replaces it, by design. */
    private void validateDataSources(List<DataSource> dataSources, String at) {
      Set<String> ids = new HashSet<>();
      for (int i = 0; i < dataSources.size(); i++) {
        DataSource dataSource = dataSources.get(i);
        String entry = at + "[" + i + "]";
        if (blank(dataSource.id())) {
          problems.add(entry + ".id is required");
        } else if (!Names.DATA_SOURCE_ID.matcher(dataSource.id()).matches()) {
          problems.add(entry + ".id " + dataSource.id() + ": " + Names.DATA_SOURCE_ID_RULE);
        } else if (!ids.add(dataSource.id())) {
          problems.add(entry + ".id " + dataSource.id() + " is listed twice");
        }
        validateUrl(dataSource.url(), entry + ".url");
      }
    }

    private void validateNames(String noun, List<String> names, String at) {
      Names.problems(noun, names).forEach(problem -> problems.add(at + " " + problem));
    }

    private void validateUrl(String url, String at) {
      String problem = blank(url) ? null : Urls.problem(url);
      if (problem != null) {
        problems.add(at + " " + problem);
      }
    }
  }
}
