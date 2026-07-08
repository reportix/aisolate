package com.reportix.aisolate.backend.domain.deploy;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.reportix.aisolate.backend.domain.airlock.InvalidFormException;
import com.reportix.aisolate.config.AirlockDocument;
import com.reportix.aisolate.config.rules.RuleSet;
import com.reportix.aisolate.plugins.CatalogCheck;
import com.reportix.aisolate.sdk.Column;
import com.reportix.aisolate.sdk.Entity;
import com.reportix.aisolate.sdk.Tool;
import com.reportix.aisolate.sdk.ToolPlugin;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a Python airlock's artifact carries, read from its {@code aisolate-manifest.json} and {@code
 * rules/*.json} alone: the zip is never extracted, nothing in it is imported or run. The manifest
 * is checked against docs/python-artifact.schema.json, then as a jar's scan is ({@link
 * CatalogCheck}), so a deploy never accepts code the pods refuse.
 */
public final class ManifestReader {

  static final String MANIFEST = "aisolate-manifest.json";

  private static final int MAX_FILE_BYTES = 1_048_576;
  private static final Pattern RULE_FILE = Pattern.compile("rules/[^/]+\\.json");
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Schema SCHEMA = loadSchema();

  /** The manifest's stages as {@link JarInspector.Handler#stage} stores them, in stage order. */
  private static final Map<String, String> STAGES =
      Map.of(
          "contextPreparator", "context_prep",
          "conversationMemory", "memory",
          "answerVerifier", "verifier");

  private static final List<String> STAGE_ORDER = List.of("context_prep", "memory", "verifier");

  /**
   * @throws InvalidFormException one {@code artifact:} error: not a zip, an unsafe entry, no or an
   *     invalid manifest, a rule file it does not list, code the tool server would refuse
   */
  public static JarInspector.Contents read(Path zip) {
    Map<String, byte[]> files;
    String digest;
    long size;
    try {
      files = readFiles(zip);
      digest = JarInspector.digest(zip);
      size = Files.size(zip);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    byte[] manifestBytes = files.get(MANIFEST);
    if (manifestBytes == null) {
      throw error("no " + MANIFEST + ": build the project with `python -m aisolate build`");
    }
    JsonNode manifest = parseManifest(manifestBytes);
    List<RuleSet> rules = readRules(manifest, files);
    List<JarInspector.Tool> tools = new ArrayList<>();
    try {
      CatalogCheck.check(plugins(manifest, tools), rules);
    } catch (IllegalStateException | IllegalArgumentException notOneCatalog) {
      throw error(notOneCatalog.getMessage());
    }
    List<JarInspector.Handler> handlers = handlers(manifest);
    if (tools.isEmpty() && handlers.isEmpty() && rules.isEmpty()) {
      throw error("nothing to load: no tool, no handler, no rules file");
    }
    return new JarInspector.Contents(
        digest,
        size,
        tools,
        handlers,
        rules.stream()
            .map(set -> new JarInspector.RuleSet(set.entity(), set.rules().size()))
            .toList(),
        AirlockDocument.Jar.PYTHON);
  }

  /**
   * The manifest and the rule files by name. Every entry name is checked as the pods extract it;
   * the names come from the central directory, which Python's {@code zipfile} reads, and must be
   * the entries a stream sees, so the backend and the pods read one zip.
   */
  private static Map<String, byte[]> readFiles(Path zip) throws IOException {
    Set<String> central;
    // Mapped, not read: the directory may sit behind gigabytes of vendored packages.
    try (FileChannel channel = FileChannel.open(zip)) {
      if (channel.size() > Integer.MAX_VALUE) {
        // A zip's 32-bit directory offsets end at 4 GiB anyway; Zip64 would lift both.
        throw error("larger than 2 GiB, which a deploy does not read yet");
      }
      central = centralDirectory(channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()));
    }
    Map<String, byte[]> files = new LinkedHashMap<>();
    Set<String> streamed = new HashSet<>();
    try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
      for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
        String name = entry.getName();
        if (!streamed.add(name)) {
          throw error("entry " + name + " is in the zip twice");
        }
        if (name.equals(MANIFEST) || name.startsWith("rules/")) {
          files.put(name, readCapped(in, name));
        }
      }
    } catch (ZipException notAZip) {
      throw error("not a zip file");
    }
    if (!streamed.equals(central)) {
      throw error("not a well-formed zip: its entries and its directory differ");
    }
    return files;
  }

  private static byte[] readCapped(InputStream in, String name) throws IOException {
    byte[] content = in.readNBytes(MAX_FILE_BYTES + 1);
    if (content.length > MAX_FILE_BYTES) {
      throw error(name + ": longer than 1 MiB");
    }
    return content;
  }

  /**
   * The entry names of the zip's central directory, each refused when it is a symlink, absolute, or
   * climbs out with {@code ..}: the pods would write it outside their code directory.
   */
  private static Set<String> centralDirectory(ByteBuffer zip) {
    ByteBuffer buffer = zip.order(ByteOrder.LITTLE_ENDIAN);
    int length = buffer.limit();
    int end = -1;
    for (int at = length - 22; at >= Math.max(0, length - 22 - 65_535); at--) {
      if (buffer.getInt(at) == 0x06054b50) {
        end = at;
        break;
      }
    }
    if (end < 0) {
      throw error("not a zip file");
    }
    Set<String> names = new TreeSet<>();
    int at = buffer.getInt(end + 16);
    for (int i = 0, count = Short.toUnsignedInt(buffer.getShort(end + 10)); i < count; i++) {
      if (at < 0 || at + 46 > length || buffer.getInt(at) != 0x02014b50) {
        throw error("not a well-formed zip: its directory is cut short");
      }
      int nameLength = Short.toUnsignedInt(buffer.getShort(at + 28));
      int madeBy = Short.toUnsignedInt(buffer.getShort(at + 4)) >> 8;
      int mode = buffer.getInt(at + 38) >>> 16;
      if (at + 46 + nameLength > length) {
        throw error("not a well-formed zip: its directory is cut short");
      }
      byte[] nameBytes = new byte[nameLength];
      buffer.get(at + 46, nameBytes);
      String name = new String(nameBytes, StandardCharsets.UTF_8);
      // Host 3 is Unix: the high half of the external attributes is the file's mode.
      if (madeBy == 3 && (mode & 0xF000) == 0xA000) {
        throw error("entry " + name + " is a symlink");
      }
      if (name.startsWith("/")
          || name.contains("\\")
          || name.matches("^[A-Za-z]:.*")
          || List.of(name.split("/")).contains("..")) {
        throw error("entry " + name + " is not a plain path inside the zip");
      }
      names.add(name);
      at +=
          46
              + nameLength
              + Short.toUnsignedInt(buffer.getShort(at + 30))
              + Short.toUnsignedInt(buffer.getShort(at + 32));
    }
    return names;
  }

  private static JsonNode parseManifest(byte[] bytes) {
    JsonNode manifest;
    try {
      manifest = JSON.readTree(bytes);
    } catch (JacksonException notJson) {
      throw error(MANIFEST + " is not JSON");
    }
    List<Error> problems = SCHEMA.validate(manifest);
    if (!problems.isEmpty()) {
      throw new InvalidFormException(
          problems.stream().map(problem -> "artifact: " + MANIFEST + " " + problem).toList());
    }
    return manifest;
  }

  /** Every {@code rules/*.json} of the zip, which must be the files the manifest lists. */
  private static List<RuleSet> readRules(JsonNode manifest, Map<String, byte[]> files) {
    Set<String> inZip = new TreeSet<>();
    for (String name : files.keySet()) {
      if (!name.startsWith("rules/") || name.endsWith("/")) {
        continue;
      }
      if (!RULE_FILE.matcher(name).matches()) {
        throw error(name + ": a rule set is rules/<entity>.json");
      }
      inZip.add(name);
    }
    Set<String> listed = new TreeSet<>(strings(manifest.path("rules")));
    if (!listed.equals(inZip)) {
      throw error(MANIFEST + " lists the rule files " + listed + ", the zip holds " + inZip);
    }
    List<RuleSet> rules = new ArrayList<>();
    for (String name : inZip) {
      try {
        rules.add(RuleSet.parse(new String(files.get(name), StandardCharsets.UTF_8)));
      } catch (RuntimeException invalid) {
        throw error(
            name
                + ": "
                + Objects.requireNonNullElse(invalid.getMessage(), invalid.getClass().getName()));
      }
    }
    return rules;
  }

  /** The manifest's plugin, as the SDK's interface: its declarations, no code. */
  private record Declared(String name, List<Entity> entities, String dataSource, List<Tool> tools)
      implements ToolPlugin {}

  /**
   * Each plugin with the tools and entities that name it, collecting the tools in manifest order
   * into {@code tools}. A tool or entity naming no plugin, or a plugin's lists that differ from
   * them, is a broken manifest.
   */
  private static List<ToolPlugin> plugins(JsonNode manifest, List<JarInspector.Tool> tools) {
    Set<String> known =
        manifest
            .path("plugins")
            .valueStream()
            .map(plugin -> plugin.path("name").asString())
            .collect(Collectors.toSet());
    manifest.path("tools").forEach(tool -> requireKnown(known, "tool", tool));
    manifest.path("entities").forEach(entity -> requireKnown(known, "entity", entity));
    List<ToolPlugin> plugins = new ArrayList<>();
    for (JsonNode plugin : manifest.path("plugins")) {
      String name = plugin.path("name").asString();
      String dataSource = text(plugin.path("data_source"));
      List<Tool> own = new ArrayList<>();
      for (JsonNode tool : manifest.path("tools")) {
        if (tool.path("plugin").asString().equals(name)) {
          own.add(
              new Tool(
                  tool.path("name").asString(),
                  tool.path("description").asString(),
                  JSON.convertValue(tool.path("input_schema"), Map.class),
                  null));
          tools.add(
              new JarInspector.Tool(
                  tool.path("name").asString(),
                  tool.path("description").asString(),
                  name,
                  dataSource == null || dataSource.isBlank() ? null : dataSource));
        }
      }
      List<Entity> entities = new ArrayList<>();
      for (JsonNode entity : manifest.path("entities")) {
        if (entity.path("plugin").asString().equals(name)) {
          entities.add(entity(entity));
        }
      }
      requireListed(name, "tools", strings(plugin.path("tools")), own.stream().map(Tool::name));
      requireListed(
          name, "entities", strings(plugin.path("entities")), entities.stream().map(Entity::name));
      plugins.add(new Declared(name, entities, dataSource, own));
    }
    return plugins;
  }

  private static void requireKnown(Set<String> plugins, String kind, JsonNode declared) {
    if (!plugins.contains(declared.path("plugin").asString())) {
      throw error(
          kind
              + " "
              + declared.path("name").asString()
              + " names plugin "
              + declared.path("plugin").asString()
              + ", which the manifest does not list");
    }
  }

  private static void requireListed(
      String plugin, String kind, List<String> listed, Stream<String> named) {
    if (!new TreeSet<>(listed).equals(new TreeSet<>(named.toList()))) {
      throw error("plugin " + plugin + " lists " + kind + " " + listed + " that name it otherwise");
    }
  }

  /**
   * As the SDK's {@link Column} and {@link Entity} are built: their constructors refuse what a Java
   * plugin's would.
   */
  private static Entity entity(JsonNode entity) {
    String name = entity.path("name").asString();
    Map<String, Column> columns = new LinkedHashMap<>();
    try {
      for (JsonNode column : entity.path("columns")) {
        String columnName = column.path("name").asString();
        Integer decimals =
            column.path("decimals").isNull() ? null : column.path("decimals").asInt();
        Column previous =
            columns.put(
                columnName,
                new Column(
                    columnName,
                    column.path("expression").asString(),
                    Column.Type.valueOf(column.path("type").asString().toUpperCase(Locale.ROOT)),
                    text(column.path("unit")),
                    text(column.path("unit_column")),
                    decimals,
                    column.path("fact").asBoolean(false)));
        if (previous != null) {
          throw error("entity " + name + ": column " + columnName + " is declared twice");
        }
      }
      return new Entity(
          name,
          entity.path("from").asString(),
          entity.path("row_key").asString(),
          columns,
          entity.path("subject").asString(),
          text(entity.path("subject_name")),
          text(entity.path("period")));
    } catch (IllegalArgumentException refused) {
      throw error(refused.getMessage());
    }
  }

  /** By stage, then order, then name, as a jar's; an airlock lists a handler by stage and name. */
  private static List<JarInspector.Handler> handlers(JsonNode manifest) {
    Map<String, JarInspector.Handler> byStageAndName = new TreeMap<>();
    for (JsonNode handler : manifest.path("handlers")) {
      String name = handler.path("name").asString();
      String stage = STAGES.get(handler.path("stage").asString());
      JarInspector.Handler read =
          new JarInspector.Handler(
              name,
              stage,
              handler.path("order").asInt(),
              text(handler.path("description")),
              strings(handler.path("data_sources")));
      if (byStageAndName.put(stage + "/" + name, read) != null) {
        throw error("handler " + name + " is declared twice");
      }
    }
    return byStageAndName.values().stream()
        .sorted(
            Comparator.comparingInt((JarInspector.Handler h) -> STAGE_ORDER.indexOf(h.stage()))
                .thenComparingInt(JarInspector.Handler::order)
                .thenComparing(JarInspector.Handler::name))
        .toList();
  }

  private static List<String> strings(JsonNode array) {
    return array.valueStream().map(JsonNode::asString).toList();
  }

  private static String text(JsonNode node) {
    return node.isNull() || node.isMissingNode() ? null : node.asString();
  }

  private static Schema loadSchema() {
    try (InputStream in =
        ManifestReader.class.getResourceAsStream("/python-artifact.schema.json")) {
      return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
          .getSchema(
              Objects.requireNonNull(in, "python-artifact.schema.json is not on the classpath"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static InvalidFormException error(String message) {
    return new InvalidFormException(List.of("artifact: " + message));
  }

  private ManifestReader() {}
}
