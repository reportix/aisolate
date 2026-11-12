"use client";

import { VersionGraph } from "@/components/VersionGraph";
import { deployHint, nodesOf } from "@/lib/graph";
import { useVersion } from "@/lib/useVersion";

/** The Graph tab: what the version's code runs, in order, or how to deploy it. */
export default function VersionGraphPage() {
  const { airlock, version } = useVersion();
  return <VersionGraph nodes={nodesOf(version)} hint={deployHint(airlock.id, version.version)} />;
}
