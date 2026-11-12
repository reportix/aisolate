"use client";

import { Banner, TableSkeleton } from "@aisolate/ui";
import { useParams, useRouter } from "next/navigation";
import { useState } from "react";
import { IdentityForm } from "@/components/IdentityForm";
import { PageHeader } from "@/components/PageHeader";
import { api } from "@/lib/api";
import { catalogOf } from "@/lib/resolve";
import { useSave } from "@/lib/scope";
import { useLoad } from "@/lib/useLoad";
import { emptyVersionForm } from "@/lib/version";

// The backend names the field "version"; the form asks for it as the name.
const toName = (e: string) => e.replace(/^version:/, "name:");

/** A new version: a name, the model that answers and a description; its deploy fills the rest. */
export default function NewVersionPage() {
  const router = useRouter();
  const { space: spaceId, id } = useParams<{ space: string; id: string }>();
  const versionsHref = `/spaces/${spaceId}/airlocks/${id}/versions`;
  const [banner, setBanner] = useState<string | null>(null);
  // The form reads its initial values once, so it waits for every model the airlock sees.
  const { data, banner: loadBanner } = useLoad(
    `${spaceId}/${id}/llms`,
    async (t) => {
      const [space, airlock, globals] = await Promise.all([
        api.spaces.get(t, spaceId),
        api.airlocks.get(t, spaceId, id),
        api.llms.list(t),
      ]);
      return catalogOf([
        ["airlock", airlock.llms],
        ["space", space.llms],
        ["global", globals],
      ]);
    },
    "Could not load the models",
  );
  const { errors, busy, save } = useSave(setBanner, toName);
  const problem = banner ?? loadBanner;

  return (
    <div className="grid gap-6">
      <PageHeader
        title="New version"
        description="A name, the model that answers and a description; aisolate deploy brings its code and stages from the project."
      />
      {problem && <Banner message={problem} />}
      {data ? (
        <IdentityForm
          initial={{ name: "", description: "" }}
          versionName
          llms={data}
          label="Save version"
          errors={errors}
          busy={busy}
          onSubmit={({ name, description, llm }) =>
            void save(
              (t) =>
                api.versions.create(t, spaceId, id, {
                  ...emptyVersionForm(),
                  version: name,
                  description,
                  llm: llm ?? "",
                }),
              (created) => `${versionsHref}/${created.version}`,
            )
          }
          onCancel={() => router.push(versionsHref)}
        />
      ) : (
        !problem && <TableSkeleton rows={3} cols={1} />
      )}
    </div>
  );
}
