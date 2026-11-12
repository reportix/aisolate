"use client";

import { Banner, Button, Tabs } from "@aisolate/ui";
import Link from "next/link";
import { useParams, usePathname } from "next/navigation";
import { useState, type ReactNode } from "react";
import { CopyButton } from "@/components/CopyButton";
import { IdentityForm } from "@/components/IdentityForm";
import { EntityLoading, PageHeader } from "@/components/PageHeader";
import { SaveBanner } from "@/components/SaveBanner";
import { api } from "@/lib/api";
import { dataErrorMessage } from "@/lib/audit";
import { env } from "@/lib/env";
import { mayChange } from "@/lib/roles";
import { useSave } from "@/lib/scope";
import { clock } from "@/lib/time";
import { useLoad } from "@/lib/useLoad";
import { VersionContext } from "@/lib/useVersion";
import { endpointUrl } from "@/lib/version";

/** One version: its header, then Graph | Details | Tools over one load of it. */
export default function VersionLayout({ children }: { children: ReactNode }) {
  const {
    space: spaceId,
    id,
    version: name,
  } = useParams<{ space: string; id: string; version: string }>();
  // The layout stays across the tabs, so the open tab is read from the path on every render.
  const pathname = usePathname();
  const [editing, setEditing] = useState(false);
  const { data, setData, banner, setBanner } = useLoad(
    `${spaceId}/${id}/${name}`,
    async (t) => {
      const [space, airlock, version, dataSources, dataError] = await Promise.all([
        api.spaces.get(t, spaceId),
        api.airlocks.get(t, spaceId, id),
        api.versions.get(t, spaceId, id, name),
        api.dataSources.list(t),
        // The version shows without it rather than not at all.
        api.audit.dataError(t, spaceId, id, name).catch(() => null),
      ]);
      return { space, airlock, version, dataSources, dataError };
    },
    "Could not load the version",
  );
  const { errors, setErrors, busy, save } = useSave(setBanner);

  if (!data) {
    return <EntityLoading banner={banner} width="w-32" rows={4} cols={2} />;
  }

  const { space, airlock, version, dataError } = data;
  const base = `/spaces/${spaceId}/airlocks/${id}/versions/${name}`;

  return (
    <div className="grid gap-6">
      <PageHeader
        tile={{ id: airlock.id, name: airlock.name }}
        title={`Version ${version.version}`}
        description={version.description || undefined}
        dates={version}
        parents={[
          { kind: "Space", name: space.name, href: `/spaces/${space.id}` },
          {
            kind: "Airlock",
            name: airlock.name,
            href: `/spaces/${space.id}/airlocks/${airlock.id}`,
          },
        ]}
        actions={
          !editing && (
            <>
              <CopyButton
                text={endpointUrl(version.endpoint, env.aisolateUrl)}
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
          initial={{ name: version.version, description: version.description }}
          nameReadOnly
          versionName
          label="Save"
          errors={errors}
          busy={busy}
          // Only the description changes; the rest of the form goes back as the deploy left it.
          onSubmit={({ description }) =>
            void save(
              (t) =>
                api.versions.update(
                  t,
                  spaceId,
                  id,
                  { ...version.form, description },
                  version.updated_at,
                ),
              (saved) => {
                setData({ ...data, version: saved });
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
          <Tabs
            linkAs={Link}
            label="Version"
            current={pathname}
            items={[
              { href: base, label: "Graph" },
              { href: `${base}/details`, label: "Details" },
              { href: `${base}/tools`, label: "Tools" },
            ]}
          />
          <VersionContext value={data}>{children}</VersionContext>
        </>
      )}
    </div>
  );
}
