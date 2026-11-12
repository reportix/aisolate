package com.reportix.aisolate.config;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.List;

/**
 * One version of an airlock, what runs, in the order an exchange runs: what runs before the model,
 * the LLM that answers with its prompt and tools, what runs after, and the LLMs its code reads
 * beside its airlock's, its space's and the global ones. Who may ask, and the data sources, are its
 * airlock's: a data source needs a login, and a version holds no credential. Its name is {@code
 * <airlock>-<version>}, unique across the install: the backend refuses a version whose name another
 * airlock's version already carries ({@code hr-assistant} with {@code eu-v1} against {@code
 * hr-assistant-eu} with {@code v1}). Handlers and tools are listed by class simple name and tool
 * name, and are its code's.
 */
public record AirlockVersionDocument(Metadata metadata, Spec spec) {

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
   * Version names in the AIsolate UI's order: a run of digits compares as a number, so {@code v2}
   * comes before {@code v10}, and the rest as text. Names equal as numbers, {@code v01} and {@code
   * v1}, fall back to text, so no two names tie.
   */
  public static final Comparator<String> VERSION_ORDER = AirlockVersionDocument::compareVersions;

  /**
   * @param airlock the Airlock it belongs to
   * @param version its label there, for example {@code v1}
   * @param llm the id of the LLM that answers, one of {@link Configuration#llmsOf}
   * @param tools the tools the model may call
   * @param llms its own LLMs, beside its airlock's, its space's and the global ones; none with a
   *     key, a version holds no credential
   * @param jar the code deployed to it; null for a version without code
   */
  public record Spec(
      String airlock,
      String version,
      String description,
      PreLlm preLLM,
      String llm,
      String prompt,
      List<String> tools,
      PostLlm postLLM,
      List<Llm> llms,
      Jar jar) {
    public Spec {
      tools = tools == null ? List.of() : List.copyOf(tools);
      llms = llms == null ? List.of() : List.copyOf(llms);
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
   * The code deployed to a version, as the cluster's documents carry it: the artifact's {@code
   * sha256:<hex>}, each loading pod's check that it runs what was deployed, the tools it publishes,
   * and its language, which picks the pods' images.
   *
   * @param language {@link #PYTHON} for a Python artifact; null or {@link #JAVA} for a jar. Left
   *     null for a jar, so a Java version's documents, and their revision, stay as they were.
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

  private static int compareVersions(String a, String b) {
    int i = 0;
    int j = 0;
    while (i < a.length() && j < b.length()) {
      if (Character.isDigit(a.charAt(i)) && Character.isDigit(b.charAt(j))) {
        int endA = endOfDigits(a, i);
        int endB = endOfDigits(b, j);
        int byValue =
            new BigInteger(a.substring(i, endA)).compareTo(new BigInteger(b.substring(j, endB)));
        if (byValue != 0) {
          return byValue;
        }
        i = endA;
        j = endB;
      } else if (a.charAt(i) != b.charAt(j)) {
        return Character.compare(a.charAt(i), b.charAt(j));
      } else {
        i++;
        j++;
      }
    }
    int byLength = Integer.compare(a.length() - i, b.length() - j);
    return byLength != 0 ? byLength : a.compareTo(b);
  }

  private static int endOfDigits(String name, int from) {
    int end = from;
    while (end < name.length() && Character.isDigit(name.charAt(end))) {
      end++;
    }
    return end;
  }
}
