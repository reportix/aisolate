import type { ReactNode } from "react";
import { count, heading, type SourceRow } from "@/lib/provenance";
import type { Evidence, Passage } from "@/lib/types";
import { ChevronIcon } from "@/ui";
import { PendingDot } from "./PendingDot";

/**
 * Under the answer, the tools it used, folded under their count: one row per source in plain words,
 * numbered like the markers in the text, then the calls still running or that failed. A row opens
 * on the request the model sent and what came back, as the audit trail has them.
 */
export function Sources({ rows, trail }: { rows: SourceRow[]; trail?: ReactNode }) {
  if (rows.length === 0) return null;
  return (
    <details aria-label="Tools used" className="group/sources mt-5">
      <summary className="inline-flex cursor-pointer list-none items-center gap-1.5 rounded-md py-0.5 text-xs font-medium text-ink-tertiary hover:text-ink-secondary [&::-webkit-details-marker]:hidden">
        <h2>
          Used {rows.length} {rows.length === 1 ? "tool" : "tools"}
        </h2>
        <span className="transition-transform group-open/sources:rotate-90">
          <ChevronIcon open={false} size={12} />
        </span>
      </summary>
      <ol className="mt-2 divide-y divide-hairline overflow-hidden rounded-xl border border-hairline">
        {rows.map((r, i) => {
          // Only a source the text can cite gets a number; one that read nothing, is still running or failed shows a dash.
          const numbered = !r.none && r.rows !== undefined;
          const header = (
            <>
              <span
                className={`inline-flex h-[1.125rem] min-w-[1.125rem] shrink-0 -translate-y-px items-center justify-center self-center rounded-full px-1.5 text-[0.6875rem] leading-none font-semibold tabular-nums ${
                  numbered
                    ? "bg-mark-wash text-mark"
                    : "border border-hairline-strong text-ink-tertiary"
                }`}
              >
                {numbered ? i + 1 : "–"}
              </span>
              <span className="min-w-0 flex-1">
                <span className="text-ink">{r.label}</span>
                {r.detail && <span className="text-ink-tertiary"> · {r.detail}</span>}
              </span>
              {r.running && (
                <span className="flex self-center">
                  <PendingDot />
                </span>
              )}
              {r.error && (
                <span className="shrink-0 self-center text-xs font-medium text-error">Failed</span>
              )}
            </>
          );
          const look = "flex items-baseline gap-2 px-3 py-2 text-sm text-ink-secondary";
          if (!r.call && !r.document)
            return (
              <li key={r.key} className={look}>
                {header}
              </li>
            );
          return (
            <li key={r.key}>
              <details className="group/row">
                <summary
                  className={`${look} cursor-pointer list-none hover:bg-panel hover:text-ink [&::-webkit-details-marker]:hidden`}
                >
                  {header}
                  <span className="self-center text-ink-tertiary transition-transform group-open/row:rotate-90">
                    <ChevronIcon open={false} size={12} />
                  </span>
                </summary>
                <div className="grid gap-3 px-3 pt-1 pb-3 pl-[2.375rem] text-xs">
                  <Block title="Request">
                    <span className="text-ink">{r.call?.tool ?? r.document}</span>
                    {r.call && Object.keys(r.call.args ?? {}).length > 0 && (
                      <>
                        {"\n"}
                        <Json value={r.call.args} />
                      </>
                    )}
                  </Block>
                  {r.error ? (
                    <Block title="Response" failed>
                      Failed: {r.error}
                    </Block>
                  ) : (
                    r.evidence && (
                      // What came back, as the airlock sent it: its passages, its first rows, or (an
                      // older airlock) its entity, rules and query hash alone.
                      <Block title="Response" pre={!r.evidence.passages && !r.evidence.rows}>
                        {r.evidence.passages ? (
                          <Passages passages={r.evidence.passages} cited={r.citedPassages} />
                        ) : r.evidence.rows ? (
                          <Rows evidence={r.evidence} cited={r.citedCells} />
                        ) : (
                          <Json value={response(r.evidence)} />
                        )}
                      </Block>
                    )
                  )}
                </div>
              </details>
            </li>
          );
        })}
      </ol>
      {trail}
    </details>
  );
}

/** A cell as the reader sees it: numbers grouped, nothing for a missing value. */
const cell = (v: unknown): string =>
  typeof v === "number"
    ? v.toLocaleString("en-US")
    : v == null
      ? ""
      : typeof v === "object"
        ? JSON.stringify(v)
        : String(v);

/**
 * The first rows a call returned, the cells the answer took its figures from in the marker colour,
 * then the rules and query hash as the audit trail has them. `self` is the tool's own-row flag, not data.
 */
function Rows({ evidence: ev, cited }: { evidence: Evidence; cited?: Set<string> }) {
  const columns = ev.columns.filter((c) => c !== "self");
  const rows = ev.rows ?? [];
  const key = ev.labels?.row_key;
  const more = ev.row_count - rows.length;
  const meta = [
    count(ev.row_count, "row"),
    ev.rules.length > 0 && `rules ${ev.rules.join(", ")}`,
    ev.rule_version && `rule version ${ev.rule_version}`,
    ev.sql_hash && `sql ${ev.sql_hash}`,
  ].filter(Boolean);
  return (
    <>
      <table className="w-full border-collapse text-left">
        <thead>
          <tr>
            {columns.map((c) => (
              <th key={c} className="pr-4 pb-1 font-medium whitespace-nowrap text-ink-tertiary">
                {heading(c)}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, i) => (
            <tr key={i} className="border-t border-hairline">
              {columns.map((c) => (
                <td
                  key={c}
                  className={`py-1 pr-4 align-top tabular-nums ${
                    key && cited?.has(`${cell(row[key])}\u0000${c}`)
                      ? "font-medium text-mark"
                      : "text-ink"
                  }`}
                >
                  {cell(row[c])}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
      {more > 0 && (
        <p className="mt-1 text-ink-tertiary">
          and {more} more {more === 1 ? "row" : "rows"}
        </p>
      )}
      <p className="mt-2 text-ink-tertiary">{meta.join(" · ")}</p>
    </>
  );
}

/** A document search's passages as the model read them, the quoted one's title in the marker colour. */
function Passages({ passages, cited }: { passages: Passage[]; cited?: Set<number> }) {
  return (
    <ol className="grid gap-2">
      {passages.map((p, i) => (
        <li key={i}>
          <p className={`${cited?.has(i + 1) ? "text-mark" : "text-ink"} font-medium`}>
            {[p.title, p.section].filter(Boolean).join(" · ")}
          </p>
          {/* Extracted text keeps a PDF's line breaks: let the words flow instead. */}
          <p className="text-ink-secondary">{p.body}</p>
        </li>
      ))}
    </ol>
  );
}

/** What a call returned, less what the airlock left empty (a document search has no rule and no query). */
function response({ entity, row_count, columns, rule_version, rules, sql_hash }: Evidence) {
  return Object.fromEntries(
    Object.entries({ entity, row_count, columns, rule_version, rules, sql_hash }).filter(
      ([, v]) => v !== "" && !(Array.isArray(v) && v.length === 0),
    ),
  );
}

/** A titled block: the request, or the response; monospace for JSON and text, red for a failed call. */
function Block({
  title,
  failed = false,
  pre = true,
  children,
}: {
  title: string;
  failed?: boolean;
  pre?: boolean;
  children: ReactNode;
}) {
  const look = `rounded-lg px-3 py-2 text-xs leading-5 ${
    failed ? "border border-error-border bg-error-wash text-error" : "bg-desk text-ink-secondary"
  }`;
  return (
    <div>
      <p className="mb-1 text-ink-tertiary">{title}</p>
      {pre ? (
        <pre className={`${look} font-mono whitespace-pre-wrap [overflow-wrap:anywhere]`}>
          {children}
        </pre>
      ) : (
        <div className={`${look} overflow-x-auto`}>{children}</div>
      )}
    </div>
  );
}

const STRING = /"(?:\\.|[^"\\])*"(\s*:)?/g;

/** Pretty JSON, keys in ink and string values in the marker colour; everything else as it is. */
function Json({ value }: { value: unknown }) {
  const text = JSON.stringify(value, null, 2);
  const parts: ReactNode[] = [];
  let last = 0;
  for (const m of text.matchAll(STRING)) {
    const at = m.index ?? 0;
    if (at > last) parts.push(text.slice(last, at));
    const [whole, colon] = m;
    const str = colon ? whole.slice(0, -colon.length) : whole;
    parts.push(
      <span key={at} className={colon ? "text-ink" : "text-mark"}>
        {str}
      </span>,
    );
    if (colon) parts.push(colon);
    last = at + whole.length;
  }
  if (last < text.length) parts.push(text.slice(last));
  return <>{parts}</>;
}
