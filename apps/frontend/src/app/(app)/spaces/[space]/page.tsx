"use client";

import { Button, Section, buttonClass } from "@aisolate/ui";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useState } from "react";
import { AirlockTable } from "@/components/AirlockTable";
import { IdentityForm } from "@/components/IdentityForm";
import { EntityLoading, PageHeader } from "@/components/PageHeader";
import { SaveBanner } from "@/components/SaveBanner";
import { api } from "@/lib/api";
import { mayChange } from "@/lib/roles";
import { spaceFormOf, useSave } from "@/lib/scope";
import { useLoad } from "@/lib/useLoad";

export default function SpacePage() {
  const { space: spaceId } = useParams<{ space: string }>();
  const [editing, setEditing] = useState(false);
  const { data, setData, banner, setBanner } = useLoad(
    spaceId,
    async (t) => {
      const [space, airlocks] = await Promise.all([
        api.spaces.get(t, spaceId),
        api.airlocks.list(t, spaceId),
      ]);
      return { space, airlocks };
    },
    "Could not load the space",
  );
  const { errors, setErrors, busy, save } = useSave(setBanner);

  if (!data) {
    return <EntityLoading banner={banner} width="w-40" rows={3} cols={3} />;
  }

  const { space, airlocks } = data;

  return (
    <div className="grid gap-6">
      <PageHeader
        tile={{ id: space.id, name: space.name }}
        title={space.name}
        description={space.description || undefined}
        dates={space}
        actions={
          mayChange(space, false) &&
          !editing && (
            <Button variant="secondary" onClick={() => setEditing(true)}>
              Edit
            </Button>
          )
        }
      />
      {banner && <SaveBanner message={banner} />}

      {/* Editing hides the airlocks, so the form's save stays the page's one primary action. */}
      {editing ? (
        <IdentityForm
          initial={{
            id: space.id,
            name: space.name,
            description: space.description,
            developers: space.developers,
            roles: space.roles,
          }}
          lists={["developers", "roles"]}
          id="fixed"
          url={{ label: "Space URL", path: "/spaces/" }}
          label="Save"
          errors={errors}
          busy={busy}
          onSubmit={(identity) =>
            void save(
              (t) => api.spaces.update(t, { ...spaceFormOf(space), ...identity }, space.updated_at),
              (saved) => {
                setData({ space: saved, airlocks });
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
        <Section
          title="Airlocks"
          action={
            mayChange(space, true) && (
              <Link href={`/spaces/${space.id}/airlocks/new`} className={buttonClass()}>
                New airlock
              </Link>
            )
          }
        >
          <AirlockTable airlocks={airlocks} />
        </Section>
      )}
    </div>
  );
}
