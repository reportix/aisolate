package com.reportix.aisolate.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MappingIterator;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * A project's documents as a deploy sends them: the cluster's documents, except that a data source
 * holds a {@code username} and {@code password} instead of a {@code secretRef}. Any string value
 * may read a variable, {@code ${NAME}} or {@code ${NAME:-fallback}} (the project's {@code .env},
 * the shell winning), the same rules as {@code aisolate.yaml}. Splitting yields the documents the
 * cluster stores and the credentials the backend turns into Secrets.
 */
public final class ProjectDocuments {

  /** One data source's credentials, keyed by {@link #dataSourceKey}. */
  public record Credentials(String username, String password) {
    /** Neither value reaches a log line through this record or the ones that hold it. */
    @Override
    public String toString() {
      return "Credentials[****]";
    }
  }

  public record Split(String documents, Map<String, Credentials> credentials) {}

  private static final Pattern VARIABLE =
      Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?}");

  private static final YAMLMapper YAML =
      YAMLMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

  private ProjectDocuments() {}

  /** {@code <document>/dataSources/<id>}: the document's name and the data source's id. */
  public static String dataSourceKey(String document, String id) {
    return document + "/dataSources/" + id;
  }

  /**
   * The documents with every variable read and the credentials taken out. A variable that is unset
   * or empty and has no fallback is refused by name; no value is ever echoed.
   */
  public static Split split(String yaml, Map<String, String> variables) {
    Set<String> unset = new TreeSet<>();
    List<JsonNode> documents = new ArrayList<>();
    try (MappingIterator<JsonNode> stream = YAML.readerFor(JsonNode.class).readValues(yaml)) {
      while (stream.hasNext()) {
        JsonNode node = stream.next();
        if (node != null && node.isObject()) {
          documents.add(substitute(node, variables, unset));
        }
      }
    }
    if (!unset.isEmpty()) {
      throw new ConfigurationException(
          unset.stream().map(name -> "variable " + name + " is not set").toList());
    }
    Map<String, Credentials> credentials = new LinkedHashMap<>();
    StringBuilder out = new StringBuilder();
    for (JsonNode document : documents) {
      // Every kind: a credential left in any document would reach the cluster's copy of it.
      String name = document.path("metadata").path("name").asString("");
      for (JsonNode dataSource : document.at("/spec/dataSources")) {
        take(dataSource, dataSourceKey(name, dataSource.path("id").asString("")), credentials);
      }
      String text = YAML.writeValueAsString(document);
      out.append(text.startsWith("---") ? "" : "---\n").append(text);
    }
    return new Split(out.toString(), credentials);
  }

  private static void take(JsonNode dataSource, String key, Map<String, Credentials> into) {
    if (dataSource instanceof ObjectNode fields
        && (fields.has("username") || fields.has("password"))) {
      JsonNode username = fields.remove("username");
      JsonNode password = fields.remove("password");
      into.put(
          key,
          new Credentials(
              username == null ? null : username.asString(null),
              password == null ? null : password.asString(null)));
    }
  }

  private static JsonNode substitute(
      JsonNode node, Map<String, String> variables, Set<String> unset) {
    if (node instanceof ObjectNode object) {
      object.properties().forEach(e -> e.setValue(substitute(e.getValue(), variables, unset)));
      return object;
    }
    if (node instanceof ArrayNode array) {
      for (int i = 0; i < array.size(); i++) {
        array.set(i, substitute(array.get(i), variables, unset));
      }
      return array;
    }
    if (node.isString() && node.asString().contains("${")) {
      Matcher m = VARIABLE.matcher(node.asString());
      StringBuilder value = new StringBuilder();
      while (m.find()) {
        String set = variables.getOrDefault(m.group(1), "");
        if (set.isEmpty() && m.group(2) == null) {
          unset.add(m.group(1));
        }
        m.appendReplacement(
            value,
            Matcher.quoteReplacement(
                set.isEmpty() ? Objects.requireNonNullElse(m.group(2), "") : set));
      }
      m.appendTail(value);
      return StringNode.valueOf(value.toString());
    }
    return node;
  }
}
