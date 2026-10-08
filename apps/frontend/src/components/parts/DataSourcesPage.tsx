"use client";

import { Section, buttonClass } from "@aisolate/ui";
import Link from "next/link";
import { DataSourceForm } from "@/components/DataSourceForm";
import { DataSourceTable } from "@/components/DataSourceTable";
import { Source } from "@/components/InheritedFrom";
import { PageHeader } from "@/components/PageHeader";
import { SaveBanner } from "@/components/SaveBanner";
import { api } from "@/lib/api";
import { useCatalog } from "@/lib/scope";
import { EditPanel } from "./EditPanel";

/** The databases the code of a space's airlocks or of an airlock reads, by id: its own, then those it inherits. */
export default function DataSourcesPage() {
  const c = useCatalog("data_sources", api.dataSources.list);

  return (
    <div className="grid gap-6">
      <PageHeader
        title="Data sources"
        description={
          c.airlockId
            ? `The databases the code of ${c.name} reads, by id: its own, the space's and the platform's. Its own replaces one with the same id.`
            : "The databases its airlocks' code reads, by id: its own and the platform's; its own replaces the platform's with the same id. Passwords are kept as Secrets and never shown."
        }
      />
      {c.problem && <SaveBanner message={c.problem} />}
      {c.form && <EditPanel noun="data source" state={c.form} Form={DataSourceForm} />}
      {(c.rows || !c.problem) && (
        <Section
          action={
            c.mayChange &&
            c.owner && (
              <Link href={`${c.base}/data-sources/new`} className={buttonClass()}>
                Add data source
              </Link>
            )
          }
          loading={!c.rows && { rows: 3, cols: 4 }}
        >
          {c.rows && (
            <DataSourceTable
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
