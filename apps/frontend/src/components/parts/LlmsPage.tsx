"use client";

import { Section, buttonClass } from "@aisolate/ui";
import Link from "next/link";
import { Source } from "@/components/InheritedFrom";
import { LlmForm } from "@/components/LlmForm";
import { LlmTable } from "@/components/LlmTable";
import { PageHeader } from "@/components/PageHeader";
import { SaveBanner } from "@/components/SaveBanner";
import { api } from "@/lib/api";
import { useCatalog } from "@/lib/scope";
import { EditPanel } from "./EditPanel";

/** The LLMs a space's airlocks or an airlock names by id: its own, then those it inherits. */
export default function LlmsPage() {
  const c = useCatalog("llms", api.llms.list);

  return (
    <div className="grid gap-6">
      <PageHeader
        title="Models"
        description={
          c.airlockId
            ? `The models ${c.name} names by id: its own, the space's and the platform's. Its own replaces one with the same id.`
            : "The models this space's airlocks name by id: its own and the platform's. Its own replaces the platform's with the same id."
        }
      />
      {c.problem && <SaveBanner message={c.problem} />}
      {c.form && <EditPanel noun="model" state={c.form} Form={LlmForm} />}
      {(c.rows || !c.problem) && (
        <Section
          action={
            c.mayChange &&
            c.owner && (
              <Link href={`${c.base}/llms/new`} className={buttonClass()}>
                Add model
              </Link>
            )
          }
          loading={!c.rows && { rows: 3, cols: 5 }}
        >
          {c.rows && (
            <LlmTable
              rows={c.rows}
              source={(r) => <Source row={r} own={c.own} owners={c.owners} />}
              editable={(r) => r.from === c.own}
              actions={
                c.mayChange ? { onEdit: c.edit, busy: c.busy, onRemove: c.remove } : undefined
              }
            />
          )}
        </Section>
      )}
    </div>
  );
}
