"use client";

import { createContext, useContext } from "react";
import type { Airlock, DataSource, Space } from "./types";

/** The airlock with its space, and AIsolate's data sources, which its code may read too. */
export type LoadedAirlock = { space: Space; airlock: Airlock; dataSources: DataSource[] };

/** Set by the airlock's layout, which loads it once for its tabs. */
export const AirlockContext = createContext<LoadedAirlock | null>(null);

/** The airlock a tab shows; the layout renders the tabs only once it has loaded. */
export function useAirlock(): LoadedAirlock {
  const loaded = useContext(AirlockContext);
  if (!loaded) throw new Error("useAirlock: outside the airlock layout");
  return loaded;
}
