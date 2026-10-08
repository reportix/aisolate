"use client";

import { ChevronRightIcon, Muted, Section, Table, Td, Th, Tr } from "@aisolate/ui";
import { NO_CODE } from "@/lib/airlock";
import { leadOf } from "@/lib/text";
import { useAirlock } from "@/lib/useAirlock";

/** A tool's one-line lead; the full text the model reads opens under it. */
function Description({ text }: { text: string }) {
  const { lead, more } = leadOf(text);
  if (!more) return lead;
  return (
    <details className="group">
      <summary className="flex w-fit cursor-pointer list-none items-center gap-1.5 [&::-webkit-details-marker]:hidden">
        {lead}
        <ChevronRightIcon
          size={14}
          className="shrink-0 text-ink-tertiary transition-transform duration-150 group-open:rotate-90"
        />
      </summary>
      <p className="mt-1 font-normal text-ink-secondary">{text}</p>
    </details>
  );
}

/** The Tools tab: the tools the airlock offers the model. */
export default function AirlockToolsPage() {
  const { jar: code, form } = useAirlock().airlock;
  // Only what the airlock lists is callable; the code's other tools stay unmentioned.
  const offered = (code?.tools.filter((t) => form.tools.includes(t.name)) ?? []).sort((a, b) =>
    a.name.localeCompare(b.name),
  );
  const empty = <Muted className="px-4 py-3">{code ? "none" : NO_CODE}</Muted>;

  return (
    <div className="grid gap-6">
      <Section
        title="Tools"
        description="What the model may call: the tools this airlock offers, each over its plugin's data source."
      >
        {offered.length > 0 ? (
          <Table>
            <thead>
              <tr>
                <Th className="w-60">Tool</Th>
                <Th className="w-50">Data source</Th>
                <Th>Description</Th>
              </tr>
            </thead>
            <tbody>
              {offered.map((t) => (
                <Tr key={t.name}>
                  <Td>{t.name}</Td>
                  <Td>{t.data_source}</Td>
                  <Td className="whitespace-normal!">
                    <Description text={t.description} />
                  </Td>
                </Tr>
              ))}
            </tbody>
          </Table>
        ) : (
          empty
        )}
      </Section>
    </div>
  );
}
