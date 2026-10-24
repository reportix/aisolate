package com.reportix.aisolate.airlock.domain.chat;

import com.reportix.aisolate.airlock.domain.airlock.AirlockDefinition;
import com.reportix.aisolate.airlock.domain.chat.audit.AuditEvent;
import com.reportix.aisolate.plugins.HandlerCandidate;
import com.reportix.aisolate.sdk.Description;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.ObservationView;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.handler.TracingObservationHandler;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.ai.model.observation.ModelObservationContext;

/**
 * The spans of one exchange, in plain words, so a trace reads the way the exchange ran: {@code
 * exchange <space>/<airlock>}, then {@code admission}, one span per handler, Spring AI's model and
 * tool-call spans, {@code tool <name>} and one span per verifier. Each carries the airlock as
 * {@code aisolate.space}, {@code aisolate.airlock} and {@code aisolate.revision}. No span holds a
 * prompt, an answer, a row or the user: the audit line, which names the trace, says who asked.
 *
 * <p>Observations, not a tracer: Spring AI's spans nest under the current observation, so ours have
 * to be observations for its model calls to land inside the exchange. Every attribute is high
 * cardinality, a span tag only; the observations are named {@code aisolate.trace.*}, and the pod
 * turns their meters off: the metrics are the audit trail's ({@code AuditLog}).
 */
public final class ExchangeTracing {

  static final String EXCHANGE = "aisolate.trace.exchange";
  static final String HANDLER_SPAN = "aisolate.trace.handler";

  public static final String SPACE = "aisolate.space";
  public static final String AIRLOCK = "aisolate.airlock";
  public static final String REVISION = "aisolate.revision";
  static final String OUTCOME = "aisolate.outcome";
  static final String CLIENT = "aisolate.client";
  static final String ADMITTED = "aisolate.admitted";
  static final String REASON = "aisolate.reason";
  static final String HANDLER = "aisolate.handler";
  static final String STAGE = "aisolate.stage";
  static final String DATA_SOURCE = "aisolate.data_source";
  static final String PASSAGES = "aisolate.passages";
  static final String VERDICT = "aisolate.verdict";
  static final String TOOL = "aisolate.tool";
  static final String ROWS = "aisolate.rows";
  static final String FIGURES = "aisolate.figures.";

  static final String PRE_LLM = "pre_llm";
  static final String POST_LLM = "post_llm";

  /**
   * Spring AI's model-call meters, its token counter {@code gen_ai.client.token.usage} among them,
   * by {@code airlock}: a low-cardinality tag read from the exchange a call runs in, {@code none}
   * outside one, since every series of a meter needs the same tags. Applied before the meters are
   * taken, when the call's observation stops.
   */
  public static final ObservationFilter MODEL_CALLS_BY_AIRLOCK =
      context -> {
        if (context instanceof ModelObservationContext<?, ?>) {
          Observation.ContextView exchange = findExchange(context);
          context.addLowCardinalityKeyValue(KeyValue.of("airlock", valueOf(exchange, AIRLOCK)));
        }
        return context;
      };

  private final ObservationRegistry registry;

  public ExchangeTracing(ObservationRegistry registry) {
    this.registry = registry;
  }

  /** What Spring AI's chat client and tool calls observe through, so they nest in ours. */
  ObservationRegistry registry() {
    return registry;
  }

  /**
   * The root span of one request to an airlock, current while {@code body} runs; admission and the
   * exchange open their spans under it.
   */
  public <T, E extends Throwable> T exchange(
      String spaceId, String airlockId, Observation.CheckedCallable<T, E> body) throws E {
    return Observation.createNotStarted(EXCHANGE, registry)
        .contextualName("exchange " + spaceId + "/" + airlockId)
        .highCardinalityKeyValue(SPACE, spaceId)
        .highCardinalityKeyValue(AIRLOCK, airlockId)
        .observeChecked(body);
  }

  /** The {@code admission} span around {@code body}, which reports to it what it decided. */
  public <T> T admission(Function<Admission, T> body) {
    Observation exchange = currentExchange();
    Observation admission =
        Observation.createNotStarted("aisolate.trace.admission", registry)
            .contextualName("admission");
    return admission.observe(() -> body.apply(new Admission(exchange, admission)));
  }

  /** What admission tells its span and the exchange's root. */
  public static final class Admission {

    private final Observation exchange;
    private final Observation admission;

    private Admission(Observation exchange, Observation admission) {
      this.exchange = exchange;
      this.admission = admission;
    }

    /** The airlock asked and the calling client; never the user. */
    public void asked(AirlockDefinition airlock, String client) {
      describe(exchange, airlock);
      describe(admission, airlock);
      tag(exchange, CLIENT, client);
      tag(admission, ADMITTED, "true");
    }

    /** {@code reason}: {@code role} or {@code client}, as on the audit line. */
    public void refused(String reason) {
      tag(admission, ADMITTED, "false");
      tag(admission, REASON, reason);
      tag(exchange, OUTCOME, "refused");
    }
  }

  /**
   * The exchange's outcome on its root, as the audit line names it, and how many of its figures are
   * sourced, computed, unsourced and mismatched: counts, never a value.
   */
  void finished(String outcome, AuditEvent.Figures figures) {
    Observation root = currentExchange();
    tag(root, OUTCOME, outcome);
    if (figures != null) {
      tag(root, FIGURES + "sourced", figures.sourced());
      tag(root, FIGURES + "computed", figures.computed());
      tag(root, FIGURES + "unsourced", figures.unsourced());
      tag(root, FIGURES + "mismatch", figures.mismatch());
    }
  }

  /**
   * One handler's span, named by its {@link Description} or else its class; {@code body} may add
   * what it did to the span.
   */
  void handler(
      AirlockDefinition airlock,
      HandlerCandidate candidate,
      String stage,
      Consumer<Observation> body) {
    Observation span = start(HANDLER_SPAN, nameOf(candidate), airlock);
    tag(span, HANDLER, candidate.name());
    tag(span, STAGE, stage);
    span.observe(() -> body.accept(span));
  }

  /** The {@code tool <name>} span around one tool call; the MCP call inside it carries it on. */
  <T> T tool(AirlockDefinition airlock, String toolName, Function<Observation, T> body) {
    Observation span = start("aisolate.trace.tool", "tool " + toolName, airlock);
    tag(span, TOOL, toolName);
    return span.observe(() -> body.apply(span));
  }

  /**
   * A handler opened one of the airlock's data sources: its span says which. Called from outside
   * the chat package, where the data sources are handed out.
   */
  public void readsDataSource(String id) {
    Observation current = registry.getCurrentObservation();
    if (current != null && HANDLER_SPAN.equals(current.getContext().getName())) {
      tag(current, DATA_SOURCE, id);
    }
  }

  /** The trace the current exchange belongs to; null when nothing traces it. */
  public String traceId() {
    Observation current = registry.getCurrentObservation();
    TracingObservationHandler.TracingContext tracing =
        current == null
            ? null
            : current.getContextView().get(TracingObservationHandler.TracingContext.class);
    Span span = tracing == null ? null : tracing.getSpan();
    String id = span == null ? null : span.context().traceId();
    return id == null || id.isBlank() ? null : id;
  }

  static void tag(Observation span, String key, Object value) {
    if (value != null) {
      span.highCardinalityKeyValue(key, String.valueOf(value));
    }
  }

  private Observation start(String name, String spanName, AirlockDefinition airlock) {
    Observation span = Observation.createNotStarted(name, registry).contextualName(spanName);
    describe(span, airlock);
    return span;
  }

  /** The root while it is current; nothing (a no-op) outside an exchange, as in unit tests. */
  private Observation currentExchange() {
    Observation current = registry.getCurrentObservation();
    return current != null && EXCHANGE.equals(current.getContext().getName())
        ? current
        : Observation.NOOP;
  }

  private static Observation.ContextView findExchange(Observation.ContextView context) {
    for (ObservationView parent = context.getParentObservation();
        parent != null;
        parent = parent.getContextView().getParentObservation()) {
      if (EXCHANGE.equals(parent.getContextView().getName())) {
        return parent.getContextView();
      }
    }
    return null;
  }

  private static String valueOf(Observation.ContextView exchange, String key) {
    KeyValue value = exchange == null ? null : exchange.getHighCardinalityKeyValue(key);
    return value == null ? "none" : value.getValue();
  }

  private static void describe(Observation span, AirlockDefinition airlock) {
    tag(span, SPACE, airlock.spaceId());
    tag(span, AIRLOCK, airlock.id());
    tag(span, REVISION, airlock.revision());
  }

  private static String nameOf(HandlerCandidate candidate) {
    String description = candidate.description();
    return description != null && !description.isBlank() ? description : candidate.name();
  }
}
