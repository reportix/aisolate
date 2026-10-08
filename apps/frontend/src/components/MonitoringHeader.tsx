"use client";

import { Tabs } from "@aisolate/ui";
import Link from "next/link";
import { useMonitoring } from "@/lib/monitoring";
import { PageHeader } from "./PageHeader";

/** Monitoring's title and its tabs, one per view the install has: Tracing, Metrics. */
export function MonitoringHeader({ current }: { current: "/monitoring" | "/monitoring/metrics" }) {
  const views = useMonitoring(true);
  const items = [
    ...(views.tracing ? [{ href: "/monitoring", label: "Tracing" }] : []),
    ...(views.metrics ? [{ href: "/monitoring/metrics", label: "Metrics" }] : []),
  ];
  return (
    <>
      <PageHeader
        title="Monitoring"
        description="What happened inside each question: every step, how long it took and what it used."
      />
      {items.length > 0 && (
        <Tabs items={items} current={current} linkAs={Link} label="Monitoring" />
      )}
    </>
  );
}
