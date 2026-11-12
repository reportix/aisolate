"use client";

import { Section, buttonClass } from "@aisolate/ui";
import Link from "next/link";
import { VersionTable } from "@/components/VersionTable";
import { mayChange } from "@/lib/roles";
import { useAirlock } from "@/lib/useAirlock";

/** The Overview tab: the airlock's versions, each on its own endpoint. */
export default function AirlockOverviewPage() {
  const { space, airlock, versions } = useAirlock();
  return (
    <Section
      title="Versions"
      description="Each on its own endpoint: aisolate deploy brings one, New version starts one by name."
      action={
        mayChange(space, true) && (
          <Link
            href={`/spaces/${space.id}/airlocks/${airlock.id}/versions/new`}
            className={buttonClass()}
          >
            New version
          </Link>
        )
      }
    >
      <VersionTable space={space} airlock={airlock} versions={versions} />
    </Section>
  );
}
