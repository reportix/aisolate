import {
  AlertIcon,
  CheckIcon,
  ChevronRightIcon,
  EmptyRow,
  GroundedAnswer,
  Section,
  Table,
  Td,
  Th,
  Tr,
  callLabel,
  cx,
} from "@aisolate/ui";
import type { ReactNode } from "react";
import {
  type AuditDetail,
  type ChainStatus,
  chainLine,
  outcomeLabel,
  tokens,
  utc,
} from "@/lib/audit";
import { DefinitionRow, Mono, MonoChips } from "./Definition";

const seconds = new Intl.NumberFormat("en", { maximumFractionDigits: 1 });

/**
 * One audit event as the airlock recorded it: the question, the answer with its figures'
 * provenance, the tools it called, what it cost, and whether it is unchanged. The ids and hashes
 * that prove it sit in a closed Technical details panel: an auditor opens it, a reader needs none.
 */
export function AuditEventView({
  detail: { id, received_at, prev_hash, hash, event: e },
  chain,
}: {
  detail: AuditDetail;
  /** null while verify runs or when it failed: the chain section says so. */
  chain: ChainStatus | null;
}) {
  return (
    <>
      <Section title="Question">
        <p className="px-4 py-3 text-sm leading-7 whitespace-pre-line">
          {e.question || <Quiet>No question was recorded.</Quiet>}
        </p>
      </Section>
      <Section title="Answer">
        <div className="px-4 py-3">
          {e.grounding ? (
            <GroundedAnswer grounding={e.grounding} />
          ) : e.answer ? (
            <p className="text-sm leading-7 whitespace-pre-line">{e.answer}</p>
          ) : (
            <Quiet>{`Nothing was answered: ${outcomeLabel(e).toLowerCase()}.`}</Quiet>
          )}
        </div>
      </Section>
      <Section
        title="Tools"
        description="Each call the model made, with the arguments it ran with."
      >
        <Table>
          <thead>
            <tr>
              <Th>Tool</Th>
              <Th>Data source</Th>
              <Th>Rows</Th>
            </tr>
          </thead>
          <tbody>
            {e.tools.length === 0 && <EmptyRow cols={3}>No tool was called.</EmptyRow>}
            {e.tools.map((t, i) => (
              <Tr key={i}>
                <Td className="whitespace-normal">
                  {t.args ? callLabel({ tool: t.name, args: t.args }) : `${t.name} (refused)`}
                </Td>
                <Td>{t.data_source ?? "—"}</Td>
                <Td>{t.rows ?? "—"}</Td>
              </Tr>
            ))}
          </tbody>
        </Table>
      </Section>
      <Section title="Details">
        <dl className="divide-y divide-hairline">
          <DefinitionRow label="Outcome">{outcomeLabel(e)}</DefinitionRow>
          <DefinitionRow label="Asked">{`${utc(e.ts)} UTC`}</DefinitionRow>
          <DefinitionRow label="Airlock">
            <Mono value={`${e.space}/${e.airlock}`} />
          </DefinitionRow>
          <DefinitionRow label="User">
            <Mono value={e.user ?? ""} />
          </DefinitionRow>
          <DefinitionRow label="Model">
            {e.model ? (
              <Mono
                value={`${e.model.provider}/${e.model.name}`}
                from={`${e.model.calls} ${e.model.calls === 1 ? "call" : "calls"}`}
              />
            ) : (
              <Quiet>none reached</Quiet>
            )}
          </DefinitionRow>
          <DefinitionRow label="Tokens in / out">
            {tokens(e.model?.input_tokens ?? null, e.model?.output_tokens ?? null)}
          </DefinitionRow>
          <DefinitionRow label="Duration">{`${seconds.format(e.duration_ms / 1000)} s`}</DefinitionRow>
          <DefinitionRow label="Verdicts">
            <MonoChips names={e.verifiers.map((v) => `${v.name}: ${v.verdict}`)} />
          </DefinitionRow>
          <DefinitionRow label="Tamper check">
            <ChainState chain={chain} plain />
          </DefinitionRow>
        </dl>
      </Section>
      <details className="group">
        <summary className="flex min-h-8 w-fit cursor-pointer list-none items-center gap-1.5 text-base font-semibold tracking-snug [&::-webkit-details-marker]:hidden">
          Technical details
          <ChevronRightIcon
            size={16}
            className="shrink-0 text-ink-tertiary transition-transform duration-150 group-open:rotate-90"
          />
        </summary>
        <p className="mt-1 max-w-2xl text-sm text-ink-secondary">
          {`What an auditor checks this record by. Each record of ${e.airlock} carries the hash of the one before it, so a change or a gap shows.`}
        </p>
        <dl className="mt-3 divide-y divide-hairline rounded-lg border border-hairline bg-paper">
          <DefinitionRow label="Chain">
            <ChainState chain={chain} />
          </DefinitionRow>
          <DefinitionRow label="Record">{`${id}, received ${utc(received_at)} UTC`}</DefinitionRow>
          <DefinitionRow label="Hash">
            <Mono value={hash} />
          </DefinitionRow>
          <DefinitionRow label="Previous hash">
            {/^0+$/.test(prev_hash) ? (
              <Quiet>{`none: the first record of ${e.airlock}`}</Quiet>
            ) : (
              <Mono value={prev_hash} />
            )}
          </DefinitionRow>
          <DefinitionRow label="Revision">
            <Mono value={e.revision ?? ""} />
          </DefinitionRow>
          <DefinitionRow label="Client">
            <Mono value={e.client ?? ""} />
          </DefinitionRow>
          <DefinitionRow label="Trace">
            <Mono value={e.trace_id ?? ""} />
          </DefinitionRow>
          <DefinitionRow label="Evidence">
            <MonoChips names={e.evidence} />
          </DefinitionRow>
          <DefinitionRow label="Rows hashes">
            {e.tools.some((t) => t.rows_hash) ? (
              <span className="grid gap-1 break-all">
                {e.tools.map(
                  (t, i) => t.rows_hash && <span key={i}>{`${t.name}: ${t.rows_hash}`}</span>,
                )}
              </span>
            ) : (
              <Quiet>none</Quiet>
            )}
          </DefinitionRow>
        </dl>
      </details>
    </>
  );
}

/** Plain, for the Details row: whether the record is unchanged; else the chain's own line. */
function ChainState({ chain, plain }: { chain: ChainStatus | null; plain?: boolean }) {
  if (!chain) return <Quiet>Not checked: the chain could not be read.</Quiet>;
  const broken = chain.status === "broken";
  const Icon = broken ? AlertIcon : CheckIcon;
  return (
    <span className={cx("flex items-center gap-1.5", broken ? "text-error" : "text-ok")}>
      <Icon size={16} className="shrink-0" />
      {plain && !broken ? "Unchanged since it was recorded" : chainLine(chain)}
    </span>
  );
}

const Quiet = ({ children }: { children: ReactNode }) => (
  <span className="text-sm text-ink-tertiary">{children}</span>
);
