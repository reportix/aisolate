"use client";

import { Section, buttonClass } from "@aisolate/ui";
import Link from "next/link";
import { LlmForm } from "@/components/LlmForm";
import { LlmTable } from "@/components/LlmTable";
import { PageHeader } from "@/components/PageHeader";
import { SaveBanner } from "@/components/SaveBanner";
import { useSession } from "@/components/Session";
import { EditPanel } from "@/components/parts/EditPanel";
import { api } from "@/lib/api";
import { useGlobalList } from "@/lib/globals";

/** AIsolate's LLMs, which every space sees: the admin changes them, a developer reads them. */
export default function GlobalLlmsPage() {
  const { admin } = useSession();
  const { rows, busy, problem, remove, edit, form } = useGlobalList(api.llms, "llms", "llms");

  return (
    <div className="grid gap-6">
      <PageHeader
        title="Models"
        description="The models every space's airlocks name by id. A space or an airlock may define its own with the same id in their place."
      />
      {problem && <SaveBanner message={problem} />}
      {form && <EditPanel noun="model" state={form} Form={LlmForm} />}
      {(rows || !problem) && (
        <Section
          action={
            admin && (
              <Link href="/llms/new" className={buttonClass()}>
                Add model
              </Link>
            )
          }
          loading={!rows && { rows: 2, cols: 4 }}
        >
          {rows && (
            <LlmTable
              rows={rows}
              actions={admin ? { onEdit: edit, busy, onRemove: remove } : undefined}
            />
          )}
        </Section>
      )}
    </div>
  );
}
