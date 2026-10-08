"use client";

import { useEffect, useState } from "react";
import { api } from "./api";
import type { Airlock, Space } from "./types";
import { useLoad } from "./useLoad";

/** Every space and every airlock: the rail names each, the dashboard counts them. */
export type Overview = { spaces: Space[]; airlocks: Airlock[] };

// One cache in this module and one in-flight load, shared by the rail and the list pages; the load
// fans out per space: one aggregate endpoint when they multiply.
let cache: (Overview & { at: number }) | null = null;
let inflight: Promise<Overview & { at: number }> | null = null;
const TTL_MS = 30_000;
const listeners = new Set<() => void>();

async function fetchOverview(token: string): Promise<Overview & { at: number }> {
  const spaces = await api.spaces.list(token);
  const airlocks = (await Promise.all(spaces.map((s) => api.airlocks.list(token, s.id)))).flat();
  return { at: Date.now(), spaces, airlocks };
}

/** The cached overview while it is fresh, else one fetch shared by every concurrent caller. */
export function loadOverview(token: string): Promise<Overview & { at: number }> {
  if (cache && Date.now() - cache.at < TTL_MS) return Promise.resolve(cache);
  if (!inflight) {
    const load = fetchOverview(token);
    inflight = load;
    // A reload while it runs replaces `inflight`; a superseded load fills nothing.
    load
      .then(
        (overview) => {
          if (inflight === load) cache = overview;
        },
        () => undefined,
      )
      .finally(() => {
        if (inflight === load) inflight = null;
      });
  }
  return inflight;
}

/** Registers a listener for reloadOverview(); useOverview's effect (and tests) call this directly. */
export function subscribeOverview(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/** Clears the shared cache and tells every mounted useOverview to refetch, rail and lists together. */
export function reloadOverview(): void {
  cache = null;
  inflight = null;
  listeners.forEach((l) => l());
}

/**
 * Everything the AIsolate UI's rail and list pages show, composed from the control API. A reload
 * bumps the load's key and keeps the data on screen meanwhile.
 */
export function useOverview() {
  const [tick, setTick] = useState(0);
  useEffect(() => subscribeOverview(() => setTick((t) => t + 1)), []);
  const { data, banner } = useLoad(String(tick), loadOverview, "Could not load the overview");
  return { data, error: banner };
}
