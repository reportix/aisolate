package com.reportix.aisolate.controller.release;

import com.reportix.aisolate.config.AirlockDocument;
import com.reportix.aisolate.config.Configuration;
import com.reportix.aisolate.config.ConfigurationException;
import com.reportix.aisolate.config.ConfigurationLoader;
import com.reportix.aisolate.config.DataSource;
import com.reportix.aisolate.config.GlobalDocument;
import com.reportix.aisolate.config.Llm;
import com.reportix.aisolate.config.Metadata;
import com.reportix.aisolate.config.Names;
import com.reportix.aisolate.config.SpaceDocument;
import com.reportix.aisolate.config.Urls;
import com.reportix.aisolate.controller.api.v1.Airlock;
import com.reportix.aisolate.controller.api.v1.Space;
import com.reportix.aisolate.controller.render.ControllerProperties;
import com.reportix.aisolate.controller.render.Renderer;
import io.fabric8.kubernetes.api.model.HasMetadata;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * One space as a release: the Global, its Space and the Airlocks naming it, rebuilt into documents
 * and loaded through the same loader as {@code aisolate validate} and the pods, then checked for
 * what the cluster needs. Each problem is attributed to the resource it names; an Airlock whose
 * Space does not exist is in no release, so never a problem.
 *
 * <p>A problem blocks its own scope only: an invalid Airlock is left out and its siblings still
 * serve, and only a problem of the Space or of the install blocks the whole space. So a user
 * removed from a list stops being admitted while someone else's airlock is broken.
 *
 * @param configuration what serves: the release without the resources left out; null when the Space
 *     itself or the install's settings are invalid
 * @param problems the problems per {@code Kind/name}; the key {@code *} holds the ones that name no
 *     single resource
 */
public record Release(Configuration configuration, Map<String, List<String>> problems) {

  private static final Pattern JAR_DIGEST = Pattern.compile("sha256:[0-9a-f]+");

  /**
   * The Global as every release serves it, and why entries were left out of it: a global LLM or
   * data source whose host the admin did not map, or a data source without a Secret, is the
   * Global's problem and leaves every catalog, so one typo there never stops every space.
   */
  public record ServedGlobal(GlobalDocument document, List<String> problems, List<Llm> leftOut) {

    public static ServedGlobal of(GlobalDocument global, ControllerProperties.Egress egress) {
      List<String> problems = new ArrayList<>();
      List<Llm> leftOut = new ArrayList<>();
      List<Llm> llms = new ArrayList<>();
      for (Llm llm : global.spec().llms()) {
        String unmapped = unmappedHost(llm.url(), egress);
        if (unmapped == null) {
          llms.add(llm);
        } else {
          leftOut.add(llm);
          problems.add("llms[" + llm.id() + "].url: " + notAllowed(unmapped));
        }
      }
      List<DataSource> dataSources = new ArrayList<>();
      for (DataSource dataSource : global.spec().dataSources()) {
        String unmapped = unmappedHost(dataSource.url(), egress);
        String field = "dataSources[" + dataSource.id() + "]";
        if (blank(dataSource.secretRef())) {
          problems.add(field + ".secretRef is required");
        } else if (unmapped != null) {
          problems.add(field + ".url: " + notAllowed(unmapped));
        } else {
          dataSources.add(dataSource);
        }
      }
      return new ServedGlobal(
          new GlobalDocument(global.metadata(), new GlobalDocument.Spec(llms, dataSources)),
          problems,
          leftOut);
    }
  }

  /**
   * The release of {@code space} out of every resource of the namespace.
   *
   * @param global the install's Global, as {@link ServedGlobal} serves it
   * @param otlpEndpoint where the pods send traces, blank for nowhere; its host must be reachable
   */
  public static Release of(
      String space,
      GlobalDocument global,
      List<Space> spaces,
      List<Airlock> airlocks,
      ControllerProperties.Egress egress,
      String otlpEndpoint) {
    Optional<Space> self = spaces.stream().filter(s -> name(s).equals(space)).findFirst();
    if (self.isEmpty()) {
      return new Release(null, Map.of());
    }
    ServedGlobal served = ServedGlobal.of(global, egress);
    SpaceDocument spaceDocument = new SpaceDocument(new Metadata(space), self.get().getSpec());
    List<Object> documents = new ArrayList<>();
    documents.add(served.document());
    documents.add(spaceDocument);
    Map<String, List<String>> problems = new LinkedHashMap<>();
    for (Airlock a : airlocks) {
      if (a.getSpec() != null && space.equals(a.getSpec().space())) {
        AirlockDocument airlock = new AirlockDocument(new Metadata(name(a)), a.getSpec());
        String lost = lostMainLlm(airlock, spaceDocument, served);
        if (lost == null) {
          documents.add(airlock);
        } else {
          add(problems, "Airlock/" + airlock.name(), lost);
        }
      }
    }
    // Each pass leaves out what the last one found invalid; nothing refers to an airlock, so what
    // remains loads or names a new culprit.
    while (true) {
      Map<String, String> files = Renderer.buildFiles(documents.stream());
      Map<String, List<String>> found;
      Configuration configuration = null;
      try {
        configuration = ConfigurationLoader.parse(files);
        found = check(configuration, egress, otlpEndpoint);
      } catch (ConfigurationException invalid) {
        found = attribute(invalid, files);
      }
      if (found.isEmpty()) {
        return new Release(configuration, problems);
      }
      found.forEach((key, lines) -> lines.forEach(line -> add(problems, key, line)));
      if (found.containsKey("*") || found.containsKey("Space/" + space)) {
        return new Release(null, problems);
      }
      int before = documents.size();
      for (String key : found.keySet()) {
        documents.removeIf(
            document ->
                ConfigurationLoader.fileName(document).equals(key.replace('/', '-') + ".yaml"));
      }
      if (documents.size() == before) {
        return new Release(null, problems);
      }
    }
  }

  /**
   * Why the airlock's main LLM is gone, or null: the global entry it resolves to was left out and
   * no nearer level defines that id. The loader would only say it resolves to nothing. An airlock
   * without a model yet has none to lose.
   */
  private static String lostMainLlm(
      AirlockDocument airlock, SpaceDocument space, ServedGlobal served) {
    String id = airlock.spec().llm();
    Predicate<List<Llm>> defines =
        llms -> llms.stream().anyMatch(llm -> Objects.equals(llm.id(), id));
    Optional<Llm> leftOut =
        served.leftOut().stream().filter(llm -> Objects.equals(llm.id(), id)).findFirst();
    if (blank(id)
        || leftOut.isEmpty()
        || defines.test(airlock.spec().llms())
        || (space.spec() != null && defines.test(space.spec().llms()))) {
      return null;
    }
    return "Airlock "
        + airlock.name()
        + ": llm "
        + id
        + ": host "
        + Urls.host(leftOut.get().url())
        + " is not in platform.egress.hosts";
  }

  /** The loader names the file first: "Airlock-hr-assistant.yaml: Airlock hr-assistant: ...". */
  private static Map<String, List<String>> attribute(
      ConfigurationException invalid, Map<String, String> files) {
    Map<String, List<String>> problems = new LinkedHashMap<>();
    for (String line : invalid.problems()) {
      String key = "*";
      int colon = line.indexOf(':');
      if (colon > 0 && files.containsKey(line.substring(0, colon))) {
        key = line.substring(0, colon).replace(".yaml", "").replaceFirst("-", "/");
      }
      add(problems, key, line);
    }
    return problems;
  }

  /** Whether the Space loads, whatever its airlocks do. */
  public boolean valid() {
    return configuration != null;
  }

  /** Whether {@code Kind/name} is in what serves. */
  public boolean serves(String kind, String name) {
    if (configuration == null) {
      return false;
    }
    return switch (kind) {
      case "Space" -> configuration.spaces().containsKey(name);
      case "Airlock" -> configuration.airlocks().containsKey(name);
      default -> false;
    };
  }

  /**
   * The problems of one resource, plus the ones that name no single resource; a resource left out
   * only because its Space is invalid is told so.
   */
  public List<String> problemsOf(String kind, String name) {
    List<String> all = new ArrayList<>(problems.getOrDefault(kind + "/" + name, List.of()));
    all.addAll(problems.getOrDefault("*", List.of()));
    if (all.isEmpty() && !serves(kind, name)) {
      all.add(
          "release invalid: "
              + problems.entrySet().stream()
                  .filter(e -> e.getKey().startsWith("Space/"))
                  .map(e -> e.getKey() + " (" + e.getValue().getFirst() + ")")
                  .collect(Collectors.joining("; ")));
    }
    return all;
  }

  /** What the loader accepts but the cluster cannot run. */
  private static Map<String, List<String>> check(
      Configuration release, ControllerProperties.Egress egress, String otlpEndpoint) {
    Map<String, List<String>> problems = new LinkedHashMap<>();
    for (AirlockDocument airlock : release.airlocks().values()) {
      checkAirlock(problems, release, airlock, egress);
    }
    // Every airlock's pods would send there: the admin's setting, so no single resource's problem.
    String otlpHost = blank(otlpEndpoint) ? null : Urls.host(otlpEndpoint);
    if (!blank(otlpEndpoint) && (otlpHost == null || !reachable(otlpHost, egress))) {
      add(
          problems,
          "*",
          "platform.observability.otlpEndpoint host "
              + otlpHost
              + " is not in platform.egress.hosts");
    }
    return problems;
  }

  /**
   * The data sources and the model as resolved: wherever one is defined, this airlock's pods are
   * the ones that connect, so the airlock is told.
   */
  private static void checkAirlock(
      Map<String, List<String>> problems,
      Configuration release,
      AirlockDocument airlock,
      ControllerProperties.Egress egress) {
    String key = "Airlock/" + airlock.name();
    String at = "Airlock " + airlock.name() + ": ";
    for (DataSource dataSource : release.dataSourcesOf(airlock.name())) {
      String field = at + "dataSources[" + dataSource.id() + "]";
      require(problems, key, field + ".url", dataSource.url());
      require(problems, key, field + ".secretRef", dataSource.secretRef());
      checkHost(problems, key, field + ".url", dataSource.url(), egress);
    }
    for (Llm llm : release.llmsOf(airlock.name())) {
      checkHost(problems, key, at + "llms[" + llm.id() + "].url", llm.url(), egress);
    }
    // Every pod that mounts the jar checks it against this digest; the loader refused a blank one.
    AirlockDocument.Jar jar = airlock.spec().jar();
    if (jar != null && !JAR_DIGEST.matcher(jar.digest()).matches()) {
      add(problems, key, at + "spec.jar.digest must be the jar's sha256:<hex>");
    }
    // Both pods of an airlock are Services named after it; the backend refuses the same names.
    String tooLong =
        Names.clusterNameProblem(release.spaceOf(airlock.name()).name(), airlock.name());
    if (tooLong != null) {
      add(problems, key, at + tooLong);
    }
  }

  /** The URL itself is never echoed, only its host: the admin's map is by host. */
  private static void checkHost(
      Map<String, List<String>> problems,
      String key,
      String field,
      String url,
      ControllerProperties.Egress egress) {
    String host = unmappedHost(url, egress);
    if (host != null) {
      add(problems, key, field + ": " + notAllowed(host));
    }
  }

  /** The URL's host when the pods could not reach it, else null. */
  private static String unmappedHost(String url, ControllerProperties.Egress egress) {
    String host = blank(url) ? null : Urls.host(url);
    return host != null && !reachable(host, egress) ? host : null;
  }

  private static String notAllowed(String host) {
    return "the host "
        + host
        + " is not allowed: the admin maps it in platform.egress.hosts or proxies it in"
        + " platform.egress.proxy.domains";
  }

  private static boolean reachable(String host, ControllerProperties.Egress egress) {
    return egress.hosts().containsKey(host) || egress.proxies(host);
  }

  private static void require(
      Map<String, List<String>> problems, String key, String field, String value) {
    if (blank(value)) {
      add(problems, key, field + " is required");
    }
  }

  private static void add(Map<String, List<String>> problems, String key, String line) {
    problems.computeIfAbsent(key, k -> new ArrayList<>()).add(line);
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  private static String name(HasMetadata resource) {
    return resource.getMetadata().getName();
  }
}
