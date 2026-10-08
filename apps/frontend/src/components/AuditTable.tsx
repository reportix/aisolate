import { EmptyRow, Table, Td, Th, Tr, chipClass, cx, rowLinkClass } from "@aisolate/ui";
import Link from "next/link";
import { type AuditSummary, type Figures, outcomeLabel, outcomeOf, tokens, utc } from "@/lib/audit";
import { shortId } from "@/lib/text";

// Nine columns in the 72rem canvas: 12px between cells, the frame's 16px at its sides.
// Important: the kit's cells set px-4, and cx joins classes without merging them.
const pad = "px-3! first:pl-4! last:pr-4!";

const HEADS = ["Time (UTC)", "Space", "Airlock", "User", "Client", "Outcome", "Figures", "Tokens"];

/**
 * The audit lines, newest first; the time is the link to the event. Space and airlock by id, as the
 * filters take them and as an auditor, who reads no space, sees them too.
 */
export function AuditTable({ events }: { events: AuditSummary[] }) {
  return (
    <Table>
      <thead>
        <tr>
          {HEADS.map((h) => (
            <Th key={h} className={cx(pad, "whitespace-nowrap")}>
              {h}
            </Th>
          ))}
        </tr>
      </thead>
      <tbody>
        {events.length === 0 && (
          <EmptyRow cols={HEADS.length}>There are no audit events for these filters.</EmptyRow>
        )}
        {events.map((e) => (
          <Tr key={e.id} link>
            <Td className={pad}>
              <Link
                href={`/audit/${e.id}`}
                className={rowLinkClass}
                title={e.question ?? undefined}
              >
                <time dateTime={e.ts} title={`${utc(e.ts)} UTC`}>
                  {utc(e.ts).slice(0, 16)}
                </time>
              </Link>
            </Td>
            <Td className={pad}>{e.space}</Td>
            <Td className={pad}>{e.airlock}</Td>
            <Td className={pad}>{e.user ?? "—"}</Td>
            <Td className={pad} title={e.client ?? undefined}>
              {e.client ? shortId(e.client) : "—"}
            </Td>
            {/* The reason on hover: the event's page says it in full. */}
            <Td className={cx(pad, "font-normal")} title={e.reason ? outcomeLabel(e) : undefined}>
              {outcomeOf(e.outcome)}
            </Td>
            <Td className={pad}>
              <FigureBadges figures={e.figures} />
            </Td>
            <Td className={cx(pad, "font-normal")}>{tokens(e.input_tokens, e.output_tokens)}</Td>
          </Tr>
        ))}
      </tbody>
    </Table>
  );
}

const TONE: Record<keyof Figures, string | undefined> = {
  sourced: undefined,
  computed: undefined,
  unsourced: "border-warn text-warn",
  mismatch: "border-error text-error",
};

/** The answer's figures by provenance, one small badge per count that is not zero. */
function FigureBadges({ figures }: { figures: Figures | null }) {
  const shown = figures
    ? (Object.keys(TONE) as (keyof Figures)[]).filter((k) => figures[k] > 0)
    : [];
  if (!figures || shown.length === 0)
    return <span className="font-normal text-ink-tertiary">—</span>;
  return (
    <span className="flex gap-1">
      {shown.map((k) => (
        <span key={k} className={chipClass(cx("whitespace-nowrap", TONE[k]))}>
          {figures[k]} {k}
        </span>
      ))}
    </span>
  );
}
