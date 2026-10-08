"use client";

import { useEffect, useState } from "react";
import { api } from "./api";
import { getAccessToken } from "./auth";

/** Which of Monitoring's views the install has: a trace store, a metrics store. */
export type MonitoringViews = { tracing: boolean; metrics: boolean };

const NONE: MonitoringViews = { tracing: false, metrics: false };

// Asked once per sign-in: the install's views change only with a redeploy.
let asked: Promise<MonitoringViews> | null = null;

/** The views, for the admin and developers; none for anyone else, and none until known. */
export function useMonitoring(mayMonitor: boolean): MonitoringViews {
  const [views, setViews] = useState<MonitoringViews>(NONE);
  useEffect(() => {
    if (!mayMonitor) return;
    asked ??= getAccessToken()
      .then((t) => api.monitoring(t))
      .catch(() => NONE);
    let cancelled = false;
    void asked.then((v) => !cancelled && setViews(v));
    return () => {
      cancelled = true;
    };
  }, [mayMonitor]);
  return mayMonitor ? views : NONE;
}
