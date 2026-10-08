"use client";

import { Banner, Section, buttonClass } from "@aisolate/ui";
import Link from "next/link";
import { useParams } from "next/navigation";
import { AirlockTable } from "@/components/AirlockTable";
import { PageHeader } from "@/components/PageHeader";
import { api } from "@/lib/api";
import { mayChange } from "@/lib/roles";
import { useLoad } from "@/lib/useLoad";

/** The airlocks of a space. */
export default function SpaceAirlocksPage() {
  const { space: spaceId } = useParams<{ space: string }>();
  const { data, banner } = useLoad(
    `${spaceId}/airlocks`,
    async (t) => {
      const [space, airlocks] = await Promise.all([
        api.spaces.get(t, spaceId),
        api.airlocks.list(t, spaceId),
      ]);
      return { space, airlocks };
    },
    "Could not load the airlocks",
  );

  return (
    <div className="grid gap-6">
      <PageHeader
        title="Airlocks"
        description="Each answers on its own endpoint: New airlock starts one by name, aisolate deploy brings its code."
      />
      {banner && <Banner message={banner} />}
      {!banner && (
        <Section
          action={
            data &&
            mayChange(data.space, true) && (
              <Link href={`/spaces/${spaceId}/airlocks/new`} className={buttonClass()}>
                New airlock
              </Link>
            )
          }
          loading={!data && { rows: 3, cols: 3 }}
        >
          {data && <AirlockTable airlocks={data.airlocks} />}
        </Section>
      )}
    </div>
  );
}
