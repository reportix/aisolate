package com.reportix.aisolate.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The naming rules the loader, the backend's forms and its deploy share, each with the one sentence
 * that refuses it. A caller puts where the problem is in front: the loader its document and field,
 * a form its field name.
 */
public final class Names {

  /**
   * The id rule of airlocks and spaces, the same as the URL slug and the CHECK constraints. It
   * names Kubernetes objects, so it does not end in a hyphen.
   */
  public static final Pattern SLUG = Pattern.compile("^[a-z]([a-z0-9-]{0,39}[a-z0-9])$");

  public static final String SLUG_RULE =
      "use 2 to 41 lowercase letters, digits or hyphens, starting with a letter and not ending"
          + " with a hyphen";

  /** A data source's id; it becomes part of its Secret's name, {@code _} as {@code -}. */
  public static final Pattern DATA_SOURCE_ID = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,40}$");

  public static final String DATA_SOURCE_ID_RULE =
      "1 to 41 lowercase letters, digits, _ or -, starting with a letter or digit";

  /** An LLM's id: the data source rule, in a namespace of its own. */
  public static final Pattern LLM_ID = DATA_SOURCE_ID;

  public static final String LLM_ID_RULE = DATA_SOURCE_ID_RULE;

  /** An airlock admits callers it names, never none. */
  public static final String NO_CALLER = "must name at least one calling client";

  // The cluster's names the backend writes and the controller reads, spelled once for both.

  /** On every object the controller renders. */
  public static final String SPACE_LABEL = "aisolate.dev/space";

  /** On the pods the controller renders: {@link #AIRLOCK_ROLE} or {@link #TOOL_SERVER_ROLE}. */
  public static final String ROLE_LABEL = "aisolate.dev/role";

  public static final String AIRLOCK_ROLE = "airlock";
  public static final String TOOL_SERVER_ROLE = "tool-server";

  /** At most 63 characters: a Kubernetes Service name is one DNS label. */
  public static final int MAX_NAME = 63;

  /**
   * The MCP server (tool server) of one airlock, reached by that airlock's pod alone: the longest
   * name the controller gives an airlock's objects.
   */
  public static String mcpServerName(String space, String airlock) {
    return space + "-" + airlock + "-mcp-server";
  }

  /**
   * The sentence refusing an airlock whose objects would be named past {@link #MAX_NAME}; null when
   * they fit. The backend refuses it at create, the controller at render.
   */
  public static String clusterNameProblem(String space, String airlock) {
    String service = mcpServerName(space, airlock);
    return service.length() <= MAX_NAME
        ? null
        : "it would run as "
            + service
            + ", "
            + service.length()
            + " characters; Kubernetes allows "
            + MAX_NAME
            + " (shorten the space or the airlock name)";
  }

  /** The longest airlock id whose objects still fit beside {@code space}. */
  public static int maxAirlockId(String space) {
    return MAX_NAME - mcpServerName(space, "").length();
  }

  private static final Pattern WHITESPACE = Pattern.compile("\\s");

  private Names() {}

  /**
   * A list of users, callers, tools or handlers: no blank entry, none with a space in it (it would
   * never match), and none twice (a handler would run twice); each repeated name once, in list
   * order.
   *
   * @param noun what each entry is, for example {@code e-mail}
   */
  public static List<String> problems(String noun, List<String> names) {
    List<String> problems = new ArrayList<>();
    if (names.stream().anyMatch(name -> name == null || name.isBlank())) {
      problems.add("must not contain a blank " + noun);
    }
    names.stream()
        .filter(name -> name != null && !name.isBlank() && WHITESPACE.matcher(name).find())
        .distinct()
        .forEach(name -> problems.add("\"" + name + "\" has a space in it"));
    names.stream()
        .filter(name -> name != null && !name.isBlank())
        .collect(
            Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting()))
        .entrySet()
        .stream()
        .filter(count -> count.getValue() > 1)
        .map(Map.Entry::getKey)
        .forEach(name -> problems.add("lists " + name + " twice"));
    return problems;
  }

  /**
   * A space's or an airlock's users: e-mails, none blank and none twice. A {@code *} admits no one
   * here, unlike among the callers, so it is refused rather than read as everyone.
   */
  public static List<String> userProblems(List<String> users) {
    List<String> problems = new ArrayList<>(problems("e-mail", emailKeys(users)));
    users.stream()
        .filter(user -> user != null && !user.isBlank() && !user.contains("@"))
        .distinct()
        .forEach(
            user ->
                problems.add(
                    user.equals("*")
                        ? "* is no e-mail: list each user; only callers take * for any"
                        : user + " is no e-mail"));
    return problems;
  }

  /**
   * An airlock's machine clients: OAuth client ids, none blank, with a space or twice. Matched
   * exactly, so a {@code *} is refused rather than read as any client.
   */
  public static List<String> clientProblems(List<String> clients) {
    List<String> problems = new ArrayList<>(problems("client id", clients));
    if (clients.contains("*")) {
      problems.add("* is no wildcard: list each client id");
    }
    return problems;
  }

  /** Users as admission compares their e-mails: {@code Eric@} and {@code eric@} are one user. */
  private static List<String> emailKeys(List<String> users) {
    return users.stream().map(user -> user == null ? null : user.toLowerCase(Locale.ROOT)).toList();
  }

  /**
   * The listed names an airlock's code does not carry, each in one sentence.
   *
   * @param what what each name should be, for example {@code a tool}
   */
  public static List<String> notInCode(String what, List<String> listed, Collection<String> code) {
    return listed.stream()
        .filter(name -> name != null && !name.isBlank() && !code.contains(name))
        .map(name -> name + " is not " + what + " of this airlock's code")
        .toList();
  }
}
