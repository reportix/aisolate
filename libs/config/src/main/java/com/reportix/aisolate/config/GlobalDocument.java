package com.reportix.aisolate.config;

import java.util.List;

/**
 * What an admin defines for the whole install: the LLMs and data sources every space inherits. At
 * most one, named {@link #NAME}; a set of documents without it reads as an empty one.
 */
public record GlobalDocument(Metadata metadata, Spec spec) {

  public static final String NAME = "aisolate";

  public record Spec(List<Llm> llms, List<DataSource> dataSources) {
    public Spec {
      llms = llms == null ? List.of() : List.copyOf(llms);
      dataSources = dataSources == null ? List.of() : List.copyOf(dataSources);
    }
  }

  public GlobalDocument {
    spec = spec == null ? new Spec(null, null) : spec;
  }

  public static GlobalDocument empty() {
    return new GlobalDocument(new Metadata(NAME), null);
  }

  public String name() {
    return metadata.name();
  }
}
