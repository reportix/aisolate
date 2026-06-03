package com.reportix.aisolate.config;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Every document of one release, by kind and name in file order, plus the revision that identifies
 * the set: the chart's checksum when it passed one, else a hash of the files as read. The loader
 * guarantees the references: each Airlock names its Space and, when it names an LLM, one it sees.
 * {@code global} is never null: a set without a Global holds an empty one.
 */
public record Configuration(
    String revision,
    GlobalDocument global,
    Map<String, SpaceDocument> spaces,
    Map<String, AirlockDocument> airlocks) {

  public Configuration {
    global = global == null ? GlobalDocument.empty() : global;
  }

  /** The Space {@code airlock} belongs to. */
  public SpaceDocument spaceOf(String airlock) {
    return require(spaces, "Space", require(airlocks, "Airlock", airlock).spec().space());
  }

  /**
   * Who may ask {@code airlock}, as the airlock pod, the tool server and discovery all read it: its
   * space's users then its own, its callers and its machine clients. A duplicate user is kept once;
   * admission ignores the case anyway.
   */
  public AirlockDocument.Access accessOf(String airlock) {
    SpaceDocument.Access space = spaceOf(airlock).spec().access();
    AirlockDocument.Access own = require(airlocks, "Airlock", airlock).spec().access();
    return new AirlockDocument.Access(
        Stream.concat(
                space == null ? Stream.empty() : space.users().stream(),
                own == null ? Stream.empty() : own.users().stream())
            .distinct()
            .toList(),
        own == null ? List.of() : own.callers(),
        own == null ? List.of() : own.clients());
  }

  /**
   * Every LLM {@code airlock} sees, sorted by id: the global ones, its space's, then its own, a
   * nearer definition of an id replacing the farther one whole.
   */
  public List<Llm> llmsOf(String airlock) {
    return byId(
        Llm::id,
        global.spec().llms(),
        spaceOf(airlock).spec().llms(),
        require(airlocks, "Airlock", airlock).spec().llms());
  }

  /**
   * The LLM that answers for {@code airlock}: the one of its catalog named by its llm; null before
   * the first deploy names one.
   */
  public Llm mainLlmOf(String airlock) {
    String id = require(airlocks, "Airlock", airlock).spec().llm();
    return llmsOf(airlock).stream().filter(llm -> llm.id().equals(id)).findFirst().orElse(null);
  }

  /** The data sources {@code airlock} reads, sorted by id, merged as {@link #llmsOf}. */
  public List<DataSource> dataSourcesOf(String airlock) {
    return byId(
        DataSource::id,
        global.spec().dataSources(),
        spaceOf(airlock).spec().dataSources(),
        require(airlocks, "Airlock", airlock).spec().dataSources());
  }

  /**
   * The revision the environment declares ({@code AISOLATE_CONFIG_REVISION}), checked against the
   * one the documents hash to: they are the same by construction, so a difference means the mounted
   * files and the release disagree, and nothing is served from them.
   */
  public Configuration declaredAs(String declared) {
    if (declared == null || declared.isBlank() || declared.equals(revision)) {
      return this;
    }
    throw new ConfigurationException(
        List.of(
            "revision mismatch: AISOLATE_CONFIG_REVISION says "
                + declared
                + ", the documents hash to "
                + revision));
  }

  private static <T> T require(Map<String, T> documents, String kind, String name) {
    T document = documents.get(name);
    if (document == null) {
      throw new IllegalArgumentException("no " + kind + " " + name);
    }
    return document;
  }

  @SafeVarargs
  private static <T> List<T> byId(Function<T, String> id, List<T>... farthestFirst) {
    Map<String, T> merged = new TreeMap<>();
    // An entry without an id is the loader's problem to report, not a key here.
    Stream.of(farthestFirst)
        .flatMap(List::stream)
        .filter(entry -> id.apply(entry) != null)
        .forEach(entry -> merged.put(id.apply(entry), entry));
    return List.copyOf(merged.values());
  }
}
