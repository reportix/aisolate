package com.reportix.aisolate.config;

import java.util.List;

/**
 * A space as the AIsolate UI defines it: its name, its description, the roles every one of its
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
   * The roles every airlock of the space admits, matched exactly against the token's roles claim;
   * an airlock may add its own. The identity provider decides who holds a role, so AIsolate keeps
   * no list of people.
   */
  public record Access(List<String> roles) {
    public Access {
      roles = roles == null ? List.of() : List.copyOf(roles);
    }
  }

  public String name() {
    return metadata.name();
  }

  public String displayName() {
    return spec.displayName() == null || spec.displayName().isBlank() ? name() : spec.displayName();
  }
}
