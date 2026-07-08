package com.reportix.aisolate.backend.domain.deploy;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.reportix.aisolate.backend.domain.airlock.InvalidFormException;
import com.reportix.aisolate.config.AirlockDocument;
import com.reportix.aisolate.plugins.CatalogCheck;
import com.reportix.aisolate.plugins.HandlerCandidate;
import com.reportix.aisolate.plugins.JarScan;
import com.reportix.aisolate.sdk.Description;
import com.reportix.aisolate.sdk.ToolPlugin;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarFile;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * What an airlock's jar carries, read from the jar alone: the tools of every plugin class, each
 * with its plugin and the data source that plugin reads, the handler classes, and the rule sets at
 * {@code aisolate/rules/<entity>.json}, whatever the packages ({@link JarScan}). Loading a plugin
 * runs the jar's static initialisers and constructors inside the backend, with its privileges: a
 * residual risk, until a build-time manifest replaces the scan.
 */
public final class JarInspector {

  /**
   * The code of an airlock as the AIsolate UI shows it, read-only.
   *
   * @param digest {@code sha256:<64 hex>}, what every pod checks the jar against
   * @param language {@code java} for a jar, {@code python} for a Python artifact ({@link
   *     ManifestReader})
   */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Contents(
      String digest,
      long size,
      List<Tool> tools,
      List<Handler> handlers,
      @JsonProperty("rules") List<RuleSet> ruleSets,
      String language) {

    @JsonIgnore
    public List<String> toolNames() {
      return tools.stream().map(Tool::name).toList();
    }

    /** The handlers of one stage, {@link Handler#stage} as stored. */
    public List<String> handlerNames(String stage) {
      return handlers.stream().filter(h -> h.stage().equals(stage)).map(Handler::name).toList();
    }
  }

  /**
   * One tool a plugin class publishes.
   *
   * @param plugin the plugin's {@link ToolPlugin#name}
   * @param dataSource the id of the data source the plugin reads, null when it names none
   */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Tool(String name, String description, String plugin, String dataSource) {}

  /**
   * One handler class, with the stage its interface makes it and its order there.
   *
   * @param description the sentence of its {@link Description}, null when it has none
   * @param dataSources the ids of its {@code @DataSources}, empty when it names none
   */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Handler(
      String name, String stage, int order, String description, List<String> dataSources) {
    public Handler {
      // Stored before handlers named any: none.
      dataSources = dataSources == null ? List.of() : List.copyOf(dataSources);
    }
  }

  /** One rule set; the count says how many rules. */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record RuleSet(String entity, int rulesCount) {}

  /**
   * @throws InvalidFormException one {@code jar:} error: not a jar, nothing to load, a plugin,
   *     handler or rules file that cannot be loaded, code the tool server would refuse to start
   *     with ({@link CatalogCheck}), a handler twice
   */
  public static Contents read(Path file) {
    try {
      List<com.reportix.aisolate.config.rules.RuleSet> rules;
      try (JarFile opened = new JarFile(file.toFile())) {
        rules = readRules(opened);
      } catch (IOException notAJar) {
        throw createJarError("not a jar file");
      }
      JarScan scan = scan(file);
      try {
        CatalogCheck.check(scan.plugins(), rules);
      } catch (IllegalStateException notOneCatalog) {
        throw createJarError(notOneCatalog.getMessage());
      }
      List<Tool> tools = readTools(scan);
      List<Handler> handlers = readHandlers(scan);
      if (tools.isEmpty() && handlers.isEmpty() && rules.isEmpty()) {
        throw createJarError(
            "nothing to load: no ToolPlugin class, no handler class, no aisolate/rules file");
      }
      List<RuleSet> ruleSets =
          rules.stream().map(set -> new RuleSet(set.entity(), set.rules().size())).toList();
      return new Contents(
          digest(file), Files.size(file), tools, handlers, ruleSets, AirlockDocument.Jar.JAVA);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** {@code sha256:<hex>} of the file, read through once: an artifact may be gigabytes. */
  static String digest(Path file) throws IOException {
    MessageDigest sha256;
    try {
      sha256 = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    try (InputStream in = new DigestInputStream(Files.newInputStream(file), sha256)) {
      in.transferTo(OutputStream.nullOutputStream());
    }
    return "sha256:" + HexFormat.of().formatHex(sha256.digest());
  }

  private static JarScan scan(Path file) throws IOException {
    try {
      // JarScan never closes its URLClassLoader, so each deploy leaks a loader and an open jar
      // handle; fine for the backend, upgrade: JarScan hands back a closeable loader.
      return JarScan.of(List.of(file));
    } catch (IllegalStateException broken) {
      // The scan words a failed constructor "could not be created"; a deploy says loaded.
      throw createJarError(
          broken.getMessage().replace(" could not be created: ", " could not be loaded: "));
    }
  }

  /** Every tool of every plugin, in jar and plugin order. */
  private static List<Tool> readTools(JarScan scan) {
    List<Tool> tools = new ArrayList<>();
    for (ToolPlugin instance : scan.plugins()) {
      // Blank names none, so the airlock's data sources are not checked for it.
      String dataSource =
          Optional.ofNullable(instance.dataSource()).filter(id -> !id.isBlank()).orElse(null);
      for (com.reportix.aisolate.sdk.Tool tool : instance.tools()) {
        tools.add(new Tool(tool.name(), tool.description(), instance.name(), dataSource));
      }
    }
    return tools;
  }

  /** By stage, then order; an airlock lists a handler by its stage and simple name, so once. */
  private static List<Handler> readHandlers(JarScan scan) {
    record Declared(HandlerCandidate.Stage stage, String name) {}
    Set<Declared> declared = new HashSet<>();
    for (HandlerCandidate candidate : scan.handlers()) {
      if (!declared.add(new Declared(candidate.stage(), candidate.name()))) {
        throw createJarError("handler " + candidate.name() + " is declared twice");
      }
    }
    return scan.handlers().stream()
        .sorted(
            Comparator.comparing(HandlerCandidate::stage)
                .thenComparingInt(HandlerCandidate::order)
                .thenComparing(HandlerCandidate::name))
        .map(
            candidate ->
                new Handler(
                    candidate.name(),
                    candidate.stage().name().toLowerCase(Locale.ROOT),
                    candidate.order(),
                    candidate.description(),
                    candidate.dataSources()))
        .toList();
  }

  private static List<com.reportix.aisolate.config.rules.RuleSet> readRules(JarFile jar)
      throws IOException {
    Map<String, String> files;
    try {
      files = JarScan.ruleFiles(jar);
    } catch (IllegalStateException refused) {
      throw createJarError(refused.getMessage());
    }
    return files.entrySet().stream().map(f -> readRuleSet(f.getKey(), f.getValue())).toList();
  }

  private static com.reportix.aisolate.config.rules.RuleSet readRuleSet(
      String name, String content) {
    try {
      return com.reportix.aisolate.config.rules.RuleSet.parse(content);
    } catch (RuntimeException invalid) {
      throw createJarError(
          name
              + ": "
              + Objects.requireNonNullElse(invalid.getMessage(), invalid.getClass().getName()));
    }
  }

  private static InvalidFormException createJarError(String message) {
    return new InvalidFormException(List.of("jar: " + message));
  }

  private JarInspector() {}
}
