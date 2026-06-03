package com.reportix.aisolate.config;

import java.util.List;

/**
 * A space as the AIsolate UI defines it: its name, its description, the users every one of its
 * airlocks admits, and the LLMs and data sources its airlocks share beside the global ones. Its
 * airlocks name it.
 */
public record SpaceDocument(Metadata metadata, Spec spec) {

  public record Spec(
      String displayName,
      String description,
      Access access,
      List<Llm> llms,
      List<DataSource> dataSources) {
    public Spec {
      llms = llms == null ? List.of() : List.copyOf(llms);
      dataSources = dataSources == null ? List.of() : List.copyOf(dataSources);
    }
  }

  /**
   * The users every airlock of the space admits, by the token's {@code email} claim, case ignored;
   * an airlock may add its own.
   */
  public record Access(List<String> users) {
    public Access {
      users = users == null ? List.of() : List.copyOf(users);
    }
  }

  public String name() {
    return metadata.name();
  }

  public String displayName() {
    return spec.displayName() == null || spec.displayName().isBlank() ? name() : spec.displayName();
  }
}
