"use client";

import { Banner, Button, Tabs } from "@aisolate/ui";
import Link from "next/link";
import { useParams, usePathname } from "next/navigation";
import { useEffect, useState, type ReactNode } from "react";
import { CopyButton } from "@/components/CopyButton";
import { IdentityForm } from "@/components/IdentityForm";
import { EntityLoading, PageHeader } from "@/components/PageHeader";
import { SaveBanner } from "@/components/SaveBanner";
import { endpointUrl } from "@/lib/airlock";
import { api } from "@/lib/api";
import { dataErrorMessage } from "@/lib/audit";
import { env } from "@/lib/env";
import { mayChange } from "@/lib/roles";
import { airlockFormOf, useSave } from "@/lib/scope";
import { clock } from "@/lib/time";
import { AirlockContext } from "@/lib/useAirlock";
import { useLoad } from "@/lib/useLoad";
import { subscribeOverview } from "@/lib/useOverview";

/** One airlock: its header, then Overview | Details | Tools | Models | Data sources over one load of it. */
export default function AirlockLayout({ children }: { children: ReactNode }) {
  const { space: spaceId, id } = useParams<{ space: string; id: string }>();
  // The layout stays across the tabs, so the open tab is read from the path on every render.
  const pathname = usePathname();
  const [editing, setEditing] = useState(false);
  // A tab's save (models, data sources) changes the airlock under this header: every save
  // calls reloadOverview(), so the airlock is read again after each.
  const [saves, setSaves] = useState(0);
  useEffect(() => subscribeOverview(() => setSaves((n) => n + 1)), []);
  const { data, setData, banner, setBanner } = useLoad(
    `${spaceId}/${id}/${saves}`,
    async (t) => {
      const [space, airlock, dataSources, dataError] = await Promise.all([
        api.spaces.get(t, spaceId),
        api.airlocks.get(t, spaceId, id),
        api.dataSources.list(t),
        // The airlock shows without it rather than not at all.
        api.audit.dataError(t, spaceId, id).catch(() => null),
      ]);
      return { space, airlock, dataSources, dataError };
    },
    "Could not load the airlock",
  );
  const { errors, setErrors, busy, save } = useSave(setBanner);

  if (!data) {
    return <EntityLoading banner={banner} width="w-48" rows={4} cols={2} />;
  }

  const { space, airlock, dataError } = data;
  const base = `/spaces/${spaceId}/airlocks/${id}`;
  const tabs = [
    { href: base, label: "Overview" },
    { href: `${base}/details`, label: "Details" },
    { href: `${base}/tools`, label: "Tools" },
    { href: `${base}/llms`, label: "Models" },
    { href: `${base}/data-sources`, label: "Data sources" },
  ];
  // A section's add or edit page keeps its tab open, as the rail keeps its row lit.
  const open = tabs.find((t) => t.href !== base && pathname.startsWith(`${t.href}/`))?.href;

  return (
    <div className="grid gap-6">
      <PageHeader
        tile={{ id: airlock.id, name: airlock.name }}
        title={airlock.name}
        description={airlock.description || undefined}
        dates={airlock}
        parents={[{ kind: "Space", name: space.name, href: `/spaces/${space.id}` }]}
        actions={
          !editing && (
            <>
              <CopyButton
                text={endpointUrl(airlock.endpoint, env.aisolateUrl)}
                label="Copy endpoint"
              />
              {mayChange(space, true) && (
                <Button variant="secondary" onClick={() => setEditing(true)}>
                  Edit
                </Button>
              )}
            </>
          )
        }
      />
      {banner && <SaveBanner message={banner} />}
      {dataError && <Banner message={dataErrorMessage(dataError, clock(dataError.ts))} />}

      {/* Editing hides the tabs, so the form's save stays the page's one primary action. */}
      {editing ? (
        <IdentityForm
          initial={{
            id: airlock.id,
            name: airlock.name,
            description: airlock.description,
            roles: airlock.roles,
          }}
          lists={["roles"]}
          id="fixed"
          url={{ label: "Airlock URL", path: `/spaces/${space.id}/airlocks/` }}
          label="Save"
          errors={errors}
          busy={busy}
          onSubmit={(identity) =>
            void save(
              (t) =>
                api.airlocks.update(
                  t,
                  spaceId,
                  { ...airlockFormOf(airlock), ...identity },
                  airlock.updated_at,
                ),
              (saved) => {
                setData({ ...data, airlock: saved });
                setEditing(false);
              },
            )
          }
          onCancel={() => {
            setEditing(false);
            setErrors([]);
          }}
        />
      ) : (
        <>
          <Tabs linkAs={Link} label="Airlock" current={open ?? pathname} items={tabs} />
          <AirlockContext value={data}>{children}</AirlockContext>
        </>
      )}
    </div>
  );
}
