"use client";

import { useEffect, useEffectEvent, useState } from "react";
import { onApiError } from "./api";
import { getAccessToken } from "./auth";

/** One fetch whenever `key` changes, keeping the last data meanwhile; a failure goes to the banner. */
export function useLoad<T>(key: string, load: (token: string) => Promise<T>, fail: string) {
  const [data, setData] = useState<T | null>(null);
  const [banner, setBanner] = useState<string | null>(null);
  // Read through an effect event: pages pass `load` inline, so `key` alone says when to fetch.
  const fetchData = useEffectEvent(async () => load(await getAccessToken()));
  useEffect(() => {
    let cancelled = false;
    fetchData().then(
      (loaded) => {
        if (cancelled) return;
        setData(loaded);
        setBanner(null);
      },
      (e) => {
        if (!cancelled) onApiError(e, fail, { banner: setBanner });
      },
    );
    return () => {
      cancelled = true;
    };
  }, [key, fail]);
  return { data, setData, banner, setBanner };
}
