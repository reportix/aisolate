package com.reportix.aisolate.sdk;

import java.util.List;
import java.util.Optional;

/**
 * What a customer's tool plugin gives AIsolate's tool server: the entities its tools may query, the
 * data source they live in, the join from the user's identity to the customer's business key, and
 * the tools themselves. A plugin holds no rules and no credentials: the airlock's code ships the
 * rules, the tool server holds the read-only connection, and every tool reaches the data through
 * the {@link GovernedQuery} it is handed. Plugins are found by scanning the jar's classes: every
 * concrete class implementing this interface is one, package-private or not, in any package. A
 * {@code META-INF/services} entry is not needed and is ignored.
 */
public interface ToolPlugin {

  /** The plugin's name, as the AIsolate UI and the deploy's answer list it beside its tools. */
  String name();

  /** The allow-list of tables and columns the tools may reach through this plugin. */
  List<Entity> entities();

  /**
   * The id of the data source the entities live in, one the airlock defines (or its space does);
   * null only when {@link #entities()} is empty.
   */
  String dataSource();

  /** The tools this plugin publishes; the airlock decides which of them a caller may use. */
  List<Tool> tools();

  /**
   * The identity join: a SELECT with one {@code ?}, bound to the token's {@code email}, that
   * returns the value row filters compare with as {@code principal.<identityAttribute>}. Null when
   * the plugin has none; every row filter on that attribute then denies. Compare the e-mail
   * ignoring case ({@code lower(email) = lower(?)}): identity providers keep the case a user typed.
   */
  default String identityJoin() {
    return null;
  }

  /**
   * The name row filters use for the identity join's value; null without a join. It also says which
   * entities the join is about: those whose {@link Entity#subject} column has this name, and only
   * their rows can be marked {@code self}, the asking user's own.
   */
  default String identityAttribute() {
    return null;
  }

  default Optional<Entity> entity(String name) {
    return entities().stream().filter(entity -> entity.name().equals(name)).findFirst();
  }
}
