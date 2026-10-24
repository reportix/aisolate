"use client";

import { Banner } from "@aisolate/ui";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { IdentityForm } from "@/components/IdentityForm";
import { PageHeader } from "@/components/PageHeader";
import { api } from "@/lib/api";
import { emptySpaceForm, useSave } from "@/lib/scope";

export default function NewSpacePage() {
  const router = useRouter();
  const [banner, setBanner] = useState<string | null>(null);
  const { errors, busy, save } = useSave(setBanner);

  return (
    <div className="grid gap-6">
      <PageHeader
        title="New space"
        description="A space holds the airlocks of one team or product; roles, a model and data sources come after."
      />
      {banner && <Banner message={banner} />}
      <IdentityForm
        initial={{ id: "", name: "", description: "" }}
        lists={["developers", "roles"]}
        id="new"
        url={{ label: "Space URL", path: "/spaces/" }}
        label="Save space"
        errors={errors}
        busy={busy}
        onSubmit={(identity) =>
          void save(
            (t) => api.spaces.create(t, { ...emptySpaceForm(), ...identity }),
            (created) => `/spaces/${created.id}`,
          )
        }
        onCancel={() => router.push("/spaces")}
      />
    </div>
  );
}
