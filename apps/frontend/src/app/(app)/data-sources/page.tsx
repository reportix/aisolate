"use client";

import { Section, buttonClass } from "@aisolate/ui";
import Link from "next/link";
import { useState } from "react";
import { DataSourceForm } from "@/components/DataSourceForm";
import { DataSourceTable, dataSourceView } from "@/components/DataSourceTable";
import { PageHeader } from "@/components/PageHeader";
import { SaveBanner } from "@/components/SaveBanner";
import { SecretView } from "@/components/SecretView";
import { useSession } from "@/components/Session";
import { EditPanel } from "@/components/parts/EditPanel";
import { api } from "@/lib/api";
import { useGlobalList } from "@/lib/globals";

/** AIsolate's data sources, which every space's code may read: the admin changes them, a developer reads them. */
export default function GlobalDataSourcesPage() {
  const { admin } = useSession();
  const { rows, busy, problem, remove, edit, form } = useGlobalList(
    api.dataSources,
    "data-sources",
    "data_sources",
  );
  // The row whose View is open above the list; Edit opens there too, one of the two at a time.
  const [viewing, setViewing] = useState<string | null>(null);
  const viewed = !form && rows?.find((r) => r.id === viewing);

  return (
    <div className="grid gap-6">
      <PageHeader
        title="Data sources"
        description="The databases the code of every space's airlocks may read, by id. A space or an airlock may define its own with the same id in their place. Passwords are kept encrypted; an admin sees them from View."
      />
      {problem && <SaveBanner message={problem} />}
      {form && <EditPanel noun="data source" state={form} Form={DataSourceForm} />}
      {viewed && (
        <SecretView key={viewed.id} {...dataSourceView(viewed)} onClose={() => setViewing(null)} />
      )}
      {(rows || !problem) && (
        <Section
          action={
            admin && (
              <Link href="/data-sources/new" className={buttonClass()}>
                Add data source
              </Link>
            )
          }
          loading={!rows && { rows: 2, cols: 3 }}
        >
          {rows && (
            <DataSourceTable
              rows={rows}
              actions={{
                onView: (id) => {
                  edit(null);
                  setViewing(id);
                },
                edit: admin
                  ? {
                      onEdit: (id) => {
                        setViewing(null);
                        edit(id);
                      },
                      busy,
                      onRemove: remove,
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
