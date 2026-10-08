import { EmptyRow, Table, Td, Th, Tr, cx, rowLinkClass } from "@aisolate/ui";
import Link from "next/link";
import { type Outcome, outcomeOf } from "@/lib/audit";
import { number } from "@/lib/text";
import { type TraceSummary, duration, rowTime, stepsLabel, totalTokens } from "@/lib/trace";

const HEADS = ["Time", "Question", "Airlock", "Outcome", "Duration", "Tokens", "Steps"];
const RIGHT = new Set(["Duration", "Tokens"]);

/** An outcome as a chip: its state's ink on a wash of it, the word always beside the hue. */
const TONE: Record<Outcome, string> = {
  answered: "bg-ok/10 text-ok",
  dont_know: "bg-warn/10 text-warn",
  refused: "bg-error-wash text-error",
  error: "bg-error-wash text-error",
};

function OutcomeChip({ outcome }: { outcome: Outcome }) {
  return (
    <span
      className={cx("inline-flex rounded-full px-2.5 py-0.5 text-xs font-medium", TONE[outcome])}
    >
      {outcomeOf(outcome)}
    </span>
  );
}

/** Monitoring's exchanges, newest first; the question is the link to its trace. */
export function TraceTable({ traces, now }: { traces: TraceSummary[]; now: Date }) {
  return (
    <Table>
      <thead>
        <tr>
          {HEADS.map((h) => (
            <Th key={h} className={RIGHT.has(h) ? "text-right" : undefined}>
              {h}
            </Th>
          ))}
        </tr>
      </thead>
      <tbody>
        {traces.length === 0 && (
          <EmptyRow cols={HEADS.length}>There are no traces for these filters.</EmptyRow>
        )}
        {traces.map((t) => (
          <Tr key={t.id} link={!!t.trace_id}>
            <Td className="font-normal">
              <time dateTime={t.ts} title={new Date(t.ts).toString()}>
                {rowTime(t.ts, now)}
              </time>
            </Td>
            <Td className="max-w-md whitespace-normal">
              {t.trace_id ? (
                <Link
                  href={`/monitoring/traces/${t.trace_id}`}
                  className={cx("line-clamp-2", rowLinkClass)}
                >
                  {t.question || "No question was recorded"}
                </Link>
              ) : (
                <span className="line-clamp-2">{t.question}</span>
              )}
              {t.user && <div className="text-xs font-normal text-ink-tertiary">{t.user}</div>}
            </Td>
            <Td className="font-normal">{t.airlock}</Td>
            <Td>
              <OutcomeChip outcome={t.outcome} />
            </Td>
            <Td className="text-right font-normal">
              {t.duration_ms === null ? "—" : duration(t.duration_ms * 1000)}
            </Td>
            <Td className="text-right font-normal">{number(totalTokens(t))}</Td>
            <Td className="text-[13px] font-normal text-ink-secondary">{stepsLabel(t)}</Td>
          </Tr>
        ))}
      </tbody>
    </Table>
  );
}
