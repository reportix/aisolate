package com.reportix.aisolate.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * An airlock as the AIsolate UI creates it and {@code aisolate deploy} completes it: the space it
 * belongs to, its name, its description, who may ask it, and, in the order an exchange runs, what
 * runs before the model, the LLM that answers with its prompt and tools, what runs after, and the
 * LLMs and data sources its code reads beside the space's and the global ones. Handlers and tools
 * are listed by class simple name and tool name, and are its code's; a data source's credentials
 * never sit in a document. Without an {@code llm} or a {@code prompt} it is valid and runs nothing:
 * the first deploy brings them.
 */
public record AirlockDocument(Metadata metadata, Spec spec) {

  public static final String OLLAMA = "ollama";

  /** A server that speaks OpenAI's chat completions and embeddings, with an optional API key. */
  public static final String OPENAI = "openai";

  /** The model providers the airlock pod has a client for, in the order messages list them. */
  public static final List<String> PROVIDERS = List.of(OLLAMA, OPENAI);

  /** Why a provider is refused, or null; a blank one is the caller's to refuse as required. */
  public static String providerProblem(String provider) {
    return provider == null || provider.isBlank() || PROVIDERS.contains(provider)
        ? null
        : provider + " is not one of " + PROVIDERS;
  }

  /**
   * @param space the Space it belongs to
   * @param llm the id of the LLM that answers, one of {@link Configuration#llmsOf}; blank before
   *     the first deploy
   * @param tools the tools the model may call
   * @param llms its own LLMs, beside its space's and the global ones
   * @param jar the code deployed to it; null before the first deploy
   */
  public record Spec(
      String space,
      String displayName,
      String description,
      Access access,
      PreLlm preLLM,
      String llm,
      String prompt,
      List<String> tools,
      PostLlm postLLM,
      List<Llm> llms,
      List<DataSource> dataSources,
      Jar jar) {
    public Spec {
      tools = tools == null ? List.of() : List.copyOf(tools);
      llms = llms == null ? List.of() : List.copyOf(llms);
      dataSources = dataSources == null ? List.of() : List.copyOf(dataSources);
    }
  }

  /**
   * Who may ask the airlock besides its space's users: its own users by the token's {@code email}
   * claim, case ignored, none at all allowed, the OAuth clients that may call by {@code azp},
   * {@code *} for any, and the machine clients admitted by {@code azp} alone on a token without an
   * e-mail, exact and none by default.
   */
  public record Access(
      List<String> users,
      List<String> callers,
      @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> clients) {
    public Access {
      users = users == null ? List.of() : List.copyOf(users);
      callers = callers == null ? List.of() : List.copyOf(callers);
      clients = clients == null ? List.of() : List.copyOf(clients);
    }

    public Access(List<String> users, List<String> callers) {
      this(users, callers, List.of());
    }
  }

  /** Before the model sees the question: what shapes it, and what it reads. */
  public record PreLlm(
      ContextPreparator contextPreparator, ConversationMemory conversationMemory) {}

  public record ContextPreparator(List<String> handlers) {
    public ContextPreparator {
      handlers = handlers == null ? List.of() : List.copyOf(handlers);
    }
  }

  public record ConversationMemory(List<String> handlers) {
    public ConversationMemory {
      handlers = handlers == null ? List.of() : List.copyOf(handlers);
    }
  }

  /** After the model answered: what judges the answer. */
  public record PostLlm(AnswerVerifier answerVerifier) {}

  public record AnswerVerifier(List<String> handlers) {
    public AnswerVerifier {
      handlers = handlers == null ? List.of() : List.copyOf(handlers);
    }
  }

  /**
   * The code deployed to an airlock, as the cluster's documents carry it: the artifact's {@code
   * sha256:<hex>}, each loading pod's check that it runs what was deployed, the tools it publishes,
   * and its language, which picks the pods' images.
   *
   * @param language {@link #PYTHON} for a Python artifact; null or {@link #JAVA} for a jar. Left
   *     null for a jar, so a Java airlock's documents, and their revision, stay as they were.
   */
  public record Jar(String digest, List<String> tools, String language) {

    public static final String JAVA = "java";
    public static final String PYTHON = "python";

    public Jar {
      tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public boolean python() {
      return PYTHON.equals(language);
    }
  }

  public String name() {
    return metadata.name();
  }

  public String displayName() {
    return spec.displayName() == null || spec.displayName().isBlank() ? name() : spec.displayName();
  }

  /** The tools the model may call. */
  public List<String> tools() {
    return spec.tools();
  }

  public boolean hasCode() {
    return spec.jar() != null;
  }

  public List<String> contextPreparatorHandlers() {
    return spec.preLLM() == null || spec.preLLM().contextPreparator() == null
        ? List.of()
        : spec.preLLM().contextPreparator().handlers();
  }

  public List<String> memoryHandlers() {
    return spec.preLLM() == null || spec.preLLM().conversationMemory() == null
        ? List.of()
        : spec.preLLM().conversationMemory().handlers();
  }

  public List<String> verifierHandlers() {
    return spec.postLLM() == null || spec.postLLM().answerVerifier() == null
        ? List.of()
        : spec.postLLM().answerVerifier().handlers();
  }
}
