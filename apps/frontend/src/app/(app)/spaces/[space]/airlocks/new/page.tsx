"use client";

import { Banner } from "@aisolate/ui";
import { useParams, useRouter } from "next/navigation";
import { useState } from "react";
import { IdentityForm } from "@/components/IdentityForm";
import { PageHeader } from "@/components/PageHeader";
import { api } from "@/lib/api";
import { emptyAirlockForm, useSave } from "@/lib/scope";

/** A new airlock: its id, label and description; its LLM and data sources have pages of their own. */
export default function NewAirlockPage() {
  const router = useRouter();
  // The id alone: the create answers 404 for a space that is gone, so nothing is loaded first.
  const { space: spaceId } = useParams<{ space: string }>();
  const base = `/spaces/${spaceId}`;
  const [banner, setBanner] = useState<string | null>(null);
  const { errors, busy, save } = useSave(setBanner);

  return (
    <div className="grid gap-6">
      <PageHeader
        title="New airlock"
        description="Its models and data sources have pages of their own; aisolate deploy brings its code."
      />
      {banner && <Banner message={banner} />}
      <IdentityForm
        initial={{ id: "", name: "", description: "" }}
        lists={["roles"]}
        id="new"
        url={{ label: "Airlock URL", path: `/spaces/${spaceId}/airlocks/` }}
        label="Save airlock"
        errors={errors}
        busy={busy}
        onSubmit={(identity) =>
          void save(
            (t) => api.airlocks.create(t, spaceId, { ...emptyAirlockForm(), ...identity }),
            (created) => `${base}/airlocks/${created.id}`,
          )
        }
        onCancel={() => router.push(base)}
      />
    </div>
  );
}
