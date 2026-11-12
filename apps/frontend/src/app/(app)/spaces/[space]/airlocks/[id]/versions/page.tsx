"use client";

import { Banner, Section, buttonClass } from "@aisolate/ui";
import Link from "next/link";
import { useParams } from "next/navigation";
import { PageHeader } from "@/components/PageHeader";
import { VersionTable } from "@/components/VersionTable";
import { api } from "@/lib/api";
import { mayChange } from "@/lib/roles";
import { useLoad } from "@/lib/useLoad";

/** The versions of an airlock, each on its own endpoint. */
export default function AirlockVersionsPage() {
  const { space: spaceId, id } = useParams<{ space: string; id: string }>();
  const { data, banner } = useLoad(
    `${spaceId}/${id}/versions`,
    async (t) => {
      const [space, airlock, versions] = await Promise.all([
        api.spaces.get(t, spaceId),
        api.airlocks.get(t, spaceId, id),
        api.versions.list(t, spaceId, id),
      ]);
      return { space, airlock, versions };
    },
    "Could not load the versions",
  );

  return (
    <div className="grid gap-6">
      <PageHeader
        title="Versions"
        description="Each on its own endpoint: aisolate deploy brings one, New version starts one by name."
      />
      {banner && <Banner message={banner} />}
      {!banner && (
        <Section
          action={
            data &&
            mayChange(data.space, true) && (
              <Link
                href={`/spaces/${spaceId}/airlocks/${id}/versions/new`}
                className={buttonClass()}
              >
                New version
              </Link>
            )
          }
          loading={!data && { rows: 3, cols: 5 }}
        >
          {data && (
            <VersionTable space={data.space} airlock={data.airlock} versions={data.versions} />
          )}
        </Section>
      )}
    </div>
  );
}
