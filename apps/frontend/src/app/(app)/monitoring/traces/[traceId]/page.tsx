"use client";

import { Banner, CopyIcon, Section, Skeleton, TableSkeleton } from "@aisolate/ui";
import { useParams } from "next/navigation";
import { type ReactNode, useState } from "react";
import { ExchangeIo, TraceSteps } from "@/components/TraceSteps";
import { api } from "@/lib/api";
import { outcomeLabel, outcomeOf } from "@/lib/audit";
import { number, shortId } from "@/lib/text";
import { useTabTitle } from "@/lib/title";
import { type TraceDetail, duration, ioOf, rowsOf, spansMessage, when } from "@/lib/trace";
import { useLoad } from "@/lib/useLoad";
import { useOverview } from "@/lib/useOverview";

/** One exchange by its trace id: what it cost over the steps its trace holds; its question and answer sit on the exchange step. */
export default function TracePage() {
  const { traceId } = useParams<{ traceId: string }>();
  // No PageHeader here: the page names its tab itself, as its heading does.
  useTabTitle(`Trace ${shortId(traceId)}`);
  const { data, banner } = useLoad(
    `trace/${traceId}`,
    (t) => api.traces.get(t, traceId),
    "Could not load this trace",
  );
  if (!data) {
    return (
      <div className="grid gap-6">
        <Skeleton className="h-7 w-80" />
        {banner ? <Banner message={banner} /> : <TableSkeleton rows={6} cols={3} />}
      </div>
    );
  }
  return <Trace detail={data} />;
}

function Trace({ detail }: { detail: TraceDetail }) {
  const e = detail.event;
  const { data: overview } = useOverview();
  const space = overview?.spaces.find((s) => s.id === e.space)?.name ?? e.space;
  const rows = detail.steps ? rowsOf(detail.steps) : [];
  // The LLM's id is the first model call's; the audit names the model it ran.
  const llmId = rows.find((r) => r.step.kind === "model")?.step.attributes["aisolate.llm"];
  const model = e.model ? (llmId ? `${String(llmId)} · ${e.model.name}` : e.model.name) : "—";
  const message = spansMessage(detail.spans_status);

  return (
    <div className="grid gap-6">
      <header className="grid gap-2.5">
        <h1 className="flex items-center gap-2 text-xl font-semibold tracking-tight">
          Trace <TraceId id={detail.trace_id} />
        </h1>
        <p className="flex flex-wrap gap-2 text-sm text-ink-tertiary">
          {e.user && <span>{e.user}</span>}
          {e.user && <span aria-hidden>·</span>}
          <span>{`${space} / ${e.airlock}`}</span>
          <span aria-hidden>·</span>
          <time dateTime={e.ts}>{when(e.ts)}</time>
        </p>
        <dl className="flex flex-wrap gap-2">
          <Fact label="Outcome">
            <span className={OUTCOME_INK[e.outcome]} title={outcomeLabel(e)}>
              {outcomeOf(e.outcome)}
            </span>
          </Fact>
          <Fact label="Duration">{duration(e.duration_ms * 1000)}</Fact>
          <Fact label="Tokens in · out">
            {e.model?.input_tokens == null && e.model?.output_tokens == null
              ? "—"
              : `${number(e.model?.input_tokens ?? 0)} · ${number(e.model?.output_tokens ?? 0)}`}
          </Fact>
          <Fact label="Model">{model}</Fact>
        </dl>
      </header>
      {message ? (
        <Section title="Steps">
          <div className="grid gap-4 px-4 py-3">
            <p className="text-sm text-ink-tertiary">{message}</p>
            <ExchangeIo io={ioOf(e)} />
          </div>
        </Section>
      ) : (
        <TraceSteps rows={rows} event={e} />
      )}
    </div>
  );
}

const OUTCOME_INK = {
  answered: "text-ok",
  dont_know: "text-warn",
  refused: "text-error",
  error: "text-error",
};

function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="grid min-w-30 gap-0.5 rounded-lg border border-hairline px-3 py-2">
      <dt className="text-xs text-ink-tertiary">{label}</dt>
      <dd className="text-sm font-semibold">{children}</dd>
    </div>
  );
}

/** The trace id cut to its ends, whole on hover; the button copies all of it. */
function TraceId({ id }: { id: string }) {
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(id);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      // Nothing to copy into: the id stays on screen, whole in its title.
    }
  };
  return (
    <span className="inline-flex items-center gap-1.5">
      <span title={id}>{shortId(id)}</span>
      <button
        type="button"
        onClick={() => void copy()}
        aria-label={copied ? "Copied" : "Copy the trace id"}
        title={copied ? "Copied" : "Copy"}
        className="rounded p-0.5 text-ink-secondary hover:text-ink"
      >
        <CopyIcon size={16} />
      </button>
    </span>
  );
}
