package com.reportix.aisolate.controller.release;

import com.reportix.aisolate.config.AirlockDocument;
import com.reportix.aisolate.config.AirlockVersionDocument;
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
import com.reportix.aisolate.controller.api.v1.AirlockVersion;
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
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * One space as a release: the Global, its Space, the Airlocks naming it and the AirlockVersions
 * naming those, rebuilt into documents and loaded through the same loader as {@code aisolate
 * validate} and the pods, then checked for what the cluster needs. Each problem is attributed to
 * the resource it names; a resource whose Airlock or Space does not exist is in no release, so
 * never a problem.
 *
 * <p>A problem blocks its own scope only: an invalid AirlockVersion is left out and its siblings
 * still serve, an invalid Airlock is left out with its versions, and only a problem of the Space or
 * of the install blocks the whole space. So a role removed from a list stops admitting its holders
 * while someone else's version is broken.
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
      List<AirlockVersion> versions,
      ControllerProperties.Egress egress,
      String otlpEndpoint) {
    Optional<Space> self = spaces.stream().filter(s -> name(s).equals(space)).findFirst();
    if (self.isEmpty()) {
      return new Release(null, Map.of());
    }
    List<Airlock> mine =
        airlocks.stream()
            .filter(a -> a.getSpec() != null && space.equals(a.getSpec().space()))
            .toList();
    Set<String> mineByName = mine.stream().map(Release::name).collect(Collectors.toSet());
    ServedGlobal served = ServedGlobal.of(global, egress);
    List<Object> documents = new ArrayList<>();
    documents.add(served.document());
    documents.add(new SpaceDocument(new Metadata(space), self.get().getSpec()));
    for (Airlock a : mine) {
      documents.add(new AirlockDocument(new Metadata(name(a)), a.getSpec()));
    }
    Map<String, List<String>> problems = new LinkedHashMap<>();
    for (AirlockVersion v : versions) {
      if (v.getSpec() != null && mineByName.contains(v.getSpec().airlock())) {
        AirlockVersionDocument version =
            new AirlockVersionDocument(new Metadata(name(v)), v.getSpec());
        String lost = lostMainLlm(version, documents, served);
        if (lost == null) {
          documents.add(version);
        } else {
          add(problems, "AirlockVersion/" + version.name(), lost);
        }
      }
    }
    // Each pass leaves out what the last one found invalid; nothing refers to a version, and an
    // airlock leaves with its versions, so what remains loads or names a new culprit.
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
        String name = key.substring(key.indexOf('/') + 1);
        for (Object document : List.copyOf(documents)) {
          if (key.startsWith("Airlock/")
              && document instanceof AirlockVersionDocument v
              && v.spec() != null
              && name.equals(v.spec().airlock())) {
            add(problems, "AirlockVersion/" + v.name(), "its Airlock " + name + " is invalid");
            documents.remove(document);
          }
          if (ConfigurationLoader.fileName(document).equals(key.replace('/', '-') + ".yaml")) {
            documents.remove(document);
          }
        }
      }
      if (documents.size() == before) {
        return new Release(null, problems);
      }
    }
  }

  /**
   * Why the version's main LLM is gone, or null: the global entry it resolves to was left out and
   * no nearer level defines that id. The loader would only say it resolves to nothing.
   */
  private static String lostMainLlm(
      AirlockVersionDocument version, List<Object> documents, ServedGlobal served) {
    String id = version.spec().llm();
    Predicate<List<Llm>> defines =
        llms -> llms.stream().anyMatch(llm -> Objects.equals(llm.id(), id));
    Optional<Llm> leftOut =
        served.leftOut().stream().filter(llm -> Objects.equals(llm.id(), id)).findFirst();
    if (leftOut.isEmpty() || defines.test(version.spec().llms())) {
      return null;
    }
    for (Object document : documents) {
      boolean nearer =
          switch (document) {
            case SpaceDocument s -> s.spec() != null && defines.test(s.spec().llms());
            case AirlockDocument a ->
                a.name().equals(version.spec().airlock()) && defines.test(a.spec().llms());
            default -> false;
          };
      if (nearer) {
        return null;
      }
    }
    return "AirlockVersion "
        + version.name()
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

  /** Whether the Space loads, whatever its airlocks and versions do. */
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
      case "AirlockVersion" -> configuration.airlockVersions().containsKey(name);
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
    for (AirlockVersionDocument version : release.airlockVersions().values()) {
      checkVersion(problems, release, version, egress);
    }
    // Every version's pods would send there: the admin's setting, so no single resource's problem.
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
   * The data sources and the model as resolved: wherever one is defined, this version's pods are
   * the ones that connect, so the version is told.
   */
  private static void checkVersion(
      Map<String, List<String>> problems,
      Configuration release,
      AirlockVersionDocument version,
      ControllerProperties.Egress egress) {
    String key = "AirlockVersion/" + version.name();
    String at = "AirlockVersion " + version.name() + ": ";
    for (DataSource dataSource : release.dataSourcesOf(version.name())) {
      String field = at + "dataSources[" + dataSource.id() + "]";
      require(problems, key, field + ".url", dataSource.url());
      require(problems, key, field + ".secretRef", dataSource.secretRef());
      checkHost(problems, key, field + ".url", dataSource.url(), egress);
    }
    for (Llm llm : release.llmsOf(version.name())) {
      checkHost(problems, key, at + "llms[" + llm.id() + "].url", llm.url(), egress);
    }
    // Every pod that mounts the jar checks it against this digest; the loader refused a blank one.
    AirlockVersionDocument.Jar jar = version.spec().jar();
    if (jar != null && !JAR_DIGEST.matcher(jar.digest()).matches()) {
      add(problems, key, at + "spec.jar.digest must be the jar's sha256:<hex>");
    }
    // Both pods of a version are Services named after it; the backend refuses the same names.
    AirlockDocument airlock = release.airlockOf(version.name());
    String tooLong =
        Names.clusterNameProblem(
            release.spaceOf(airlock.name()).name(), airlock.name(), version.spec().version());
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
