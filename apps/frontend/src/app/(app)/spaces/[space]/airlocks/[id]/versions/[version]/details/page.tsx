"use client";

import { Section, Table, Td, Th, Tr } from "@aisolate/ui";
import { CopyButton } from "@/components/CopyButton";
import { DataSourceTable } from "@/components/DataSourceTable";
import { DefinitionRow, Mono, MonoChips } from "@/components/Definition";
import { Source } from "@/components/InheritedFrom";
import { env } from "@/lib/env";
import { catalogOf, originLabel } from "@/lib/resolve";
import type { Stage } from "@/lib/types";
import { useVersion } from "@/lib/useVersion";
import { NO_CODE, endpointUrl, handlersOf, noLlm } from "@/lib/version";

/** The Details tab: the version's endpoint, its stages and the LLM that answers, its code, and the data sources its code reads as they resolve. */
export default function VersionDetailsPage() {
  const { space, airlock, version, dataSources: globals } = useVersion();
  const { form, jar: code } = version;
  // A version defines no data source: its code reads the airlock's, the space's and the platform's.
  const dataSources = catalogOf([
    ["airlock", airlock.data_sources],
    ["space", space.data_sources],
    ["global", globals],
  ]);
  const names = { airlock: airlock.name, space: space.name };
  const dsBase = `/spaces/${space.id}`;
  const handlers = (stage: Stage, listed: string[]) => (
    <MonoChips
      names={code ? handlersOf(code, stage, listed).map((h) => h.name) : []}
      none={code ? "none" : NO_CODE}
    />
  );
  const endpoint = endpointUrl(version.endpoint, env.aisolateUrl);

  return (
    <div className="grid gap-6">
      <Section title="Endpoint" action={<CopyButton text={endpoint} />}>
        <p className="px-4 py-3">
          <span className="break-all" title="Called with the end user's token">
            {endpoint}
          </span>
        </p>
      </Section>

      <Section
        title="Pre-model"
        description="Prepares what the model receives: the project's code adds context to the question, such as passages found in its documents or the user's earlier questions."
      >
        <dl className="divide-y divide-hairline">
          <DefinitionRow label="Context preparator">
            {handlers("context_prep", form.context_preparators)}
          </DefinitionRow>
          <DefinitionRow label="Conversation memory">
            {handlers("memory", form.memory_handlers)}
          </DefinitionRow>
        </dl>
      </Section>

      <Section
        title="Model"
        description="The model that answers, as the project names it and as it resolves from the airlock, the space or the platform, and what it is told, from the project."
      >
        <dl className="divide-y divide-hairline">
          <DefinitionRow label="Name">
            <Mono
              value={version.main_llm?.model ?? ""}
              none={noLlm(form.llm)}
              from={version.main_llm ? originLabel(version.main_llm.from, names) : undefined}
            />
          </DefinitionRow>
          <DefinitionRow label="Prompt">
            {form.prompt ? (
              <span className="whitespace-pre-wrap">{form.prompt}</span>
            ) : (
              <span className="text-ink-tertiary">{code ? "none" : NO_CODE}</span>
            )}
          </DefinitionRow>
        </dl>
      </Section>

      <Section
        title="Post-model"
        description="Checks the model's answer: the project's verifiers judge it and add their verdict to it, never blocking it."
      >
        <Table>
          <thead>
            <tr>
              <Th>Verifier</Th>
              <Th>What it does</Th>
            </tr>
          </thead>
          <tbody>
            {(code ? handlersOf(code, "verifier", form.verifiers) : []).map((h) => (
              <Tr key={h.name}>
                <Td>{h.name}</Td>
                <Td className="font-normal">{h.description ?? ""}</Td>
              </Tr>
            ))}
          </tbody>
        </Table>
      </Section>

      <Section
        title="Code"
        description="What aisolate deploy brought: the project's handlers and tools, by the digest of their build."
      >
        <dl className="divide-y divide-hairline">
          <DefinitionRow label="Digest">
            <Mono value={code?.digest ?? ""} none={NO_CODE} />
          </DefinitionRow>
          <DefinitionRow label="Language">
            <Mono value={code?.language ?? ""} none={NO_CODE} />
          </DefinitionRow>
        </dl>
      </Section>

      <Section
        title="Data sources"
        description="Every id the code names, as it resolves: the airlock's own, the space's, then the platform's."
      >
        <DataSourceTable
          rows={dataSources}
          empty="This version reads no data sources."
          source={(d) => (
            <Source
              row={d}
              own="version"
              owners={{
                airlock: {
                  name: airlock.name,
                  href: `${dsBase}/airlocks/${airlock.id}/data-sources`,
                },
                space: { name: space.name, href: `${dsBase}/data-sources` },
              }}
            />
          )}
        />
      </Section>
    </div>
  );
}
