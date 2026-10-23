"use client";

import { Section, buttonClass } from "@aisolate/ui";
import Link from "next/link";
import { useState } from "react";
import { Source } from "@/components/InheritedFrom";
import { LlmForm } from "@/components/LlmForm";
import { LlmTable, llmView } from "@/components/LlmTable";
import { PageHeader } from "@/components/PageHeader";
import { SaveBanner } from "@/components/SaveBanner";
import { SecretView } from "@/components/SecretView";
import { api } from "@/lib/api";
import { useCatalog } from "@/lib/scope";
import { EditPanel } from "./EditPanel";

/** The LLMs a space's airlocks or an airlock names by id: its own, then those it inherits. */
export default function LlmsPage() {
  const c = useCatalog("llms", api.llms.list);
  // The row whose View is open above the list; Edit opens there too, one of the two at a time.
  const [viewing, setViewing] = useState<string | null>(null);
  const viewed = !c.form && c.rows?.find((r) => r.id === viewing);

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
      {viewed && (
        <SecretView key={viewed.id} {...llmView(viewed)} onClose={() => setViewing(null)} />
      )}
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
              actions={{
                onView: (id) => {
                  c.edit(null);
                  setViewing(id);
                },
                edit: c.mayChange
                  ? {
                      onEdit: (id) => {
                        setViewing(null);
                        c.edit(id);
                      },
                      busy: c.busy,
                      onRemove: c.remove,
                    }
                  : undefined,
              }}
            />
          )}
        </Section>
      )}
    </div>
  );
}
