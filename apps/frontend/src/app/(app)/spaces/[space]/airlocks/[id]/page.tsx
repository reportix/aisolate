"use client";

import { AirlockGraph } from "@/components/AirlockGraph";
import { deployHint, nodesOf } from "@/lib/graph";
import { useAirlock } from "@/lib/useAirlock";

/** The Overview tab: what the airlock's code runs, in order, or how to deploy it. */
export default function AirlockOverviewPage() {
  const { airlock } = useAirlock();
  return <AirlockGraph nodes={nodesOf(airlock)} hint={deployHint(airlock.id)} />;
}
