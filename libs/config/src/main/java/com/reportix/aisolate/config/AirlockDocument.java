package com.reportix.aisolate.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * An airlock as the AIsolate UI defines it: the space it belongs to, its name, its description, who
 * may ask it, and the LLMs and data sources its versions see beside the space's and the global
 * ones. What runs is its versions', which name it; before the first deploy it has none.
 */
public record AirlockDocument(Metadata metadata, Spec spec) {

  /**
   * @param space the Space it belongs to
   * @param llms its own LLMs, beside its space's and the global ones
   * @param dataSources its own data sources, beside its space's and the global ones
   */
  public record Spec(
      String space,
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
   * Who may ask every version of the airlock besides holders of its space's roles: holders of its
   * own roles (the token's roles claim, exact; {@code *} is no wildcard, none at all allowed),
   * through the OAuth clients that may call by {@code azp}, {@code *} for any, and the machine
   * clients admitted by {@code azp} alone on a token without an e-mail, exact and none by default.
   */
  public record Access(
      List<String> roles,
      List<String> callers,
      @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> clients) {
    public Access {
      roles = roles == null ? List.of() : List.copyOf(roles);
      callers = callers == null ? List.of() : List.copyOf(callers);
      clients = clients == null ? List.of() : List.copyOf(clients);
    }

    public Access(List<String> roles, List<String> callers) {
      this(roles, callers, List.of());
    }
  }

  public String name() {
    return metadata.name();
  }

  public String displayName() {
    return spec.displayName() == null || spec.displayName().isBlank() ? name() : spec.displayName();
  }
}
