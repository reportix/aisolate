"use client";

import { createContext, useContext } from "react";
import type { Airlock, AirlockVersion, DataSource, Space } from "./types";

/** The version with its airlock and space, and AIsolate's data sources, which its code may read too. */
export type LoadedVersion = {
  space: Space;
  airlock: Airlock;
  version: AirlockVersion;
  dataSources: DataSource[];
};

/** Set by the version's layout, which loads it once for its three tabs. */
export const VersionContext = createContext<LoadedVersion | null>(null);

/** The version a tab shows; the layout renders the tabs only once it has loaded. */
export function useVersion(): LoadedVersion {
  const loaded = useContext(VersionContext);
  if (!loaded) throw new Error("useVersion: outside the version layout");
  return loaded;
}
