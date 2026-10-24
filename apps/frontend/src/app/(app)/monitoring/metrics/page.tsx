"use client";

import { Banner, Section } from "@aisolate/ui";
import { useState } from "react";
import { BarChart } from "@/components/BarChart";
import { LineChart } from "@/components/LineChart";
import { MonitoringFilters } from "@/components/MonitoringFilters";
import { MonitoringHeader } from "@/components/MonitoringHeader";
import { api } from "@/lib/api";
import { REASONS } from "@/lib/audit";
import { type MetricsFilters, type MetricsPeriod, metricsQuery, short } from "@/lib/metrics";
import { filtersSearch, traceFiltersOf } from "@/lib/trace";
import { useLoad } from "@/lib/useLoad";
import { useOverview } from "@/lib/useOverview";

/** The address's filters; all time is Tracing's alone, so it reads as the last 24 hours here. */
function filtersOf(search: string): MetricsFilters {
  const { space, airlock, period } = traceFiltersOf(search);
  return { space, airlock, period: period === "all" ? "24h" : (period as MetricsPeriod) };
}

const seconds = (v: number) => `${v.toFixed(2)} s`;

/** Monitoring's Metrics tab: the example dashboard's five panels over the spaces one may see. */
export default function MetricsPage() {
  const [filters, setFilters] = useState<MetricsFilters>(() =>
    typeof window === "undefined" ? { period: "24h" } : filtersOf(location.search),
  );
  const { data, banner } = useLoad(
    `metrics${metricsQuery(filters)}`,
    (t) => api.metrics(t, filters),
    "Could not load the metrics",
  );
  const { data: overview } = useOverview();
  // A line's hue follows its airlock among every airlock the reader sees, whatever the filters.
  const keys = (overview?.airlocks ?? []).map((a) => `${a.space_id}/${a.id}`);

  const apply = (next: MetricsFilters) => {
    history.replaceState(null, "", `/monitoring/metrics${filtersSearch(next)}`);
    setFilters(next);
  };

  const from = data ? Date.parse(data.from) / 1000 : 0;
  const to = data ? Date.parse(data.to) / 1000 : 1;
  const loading = !data && { rows: 4, cols: 3 };
  return (
    <div className="grid gap-6">
      <MonitoringHeader current="/monitoring/metrics" />
      <MonitoringFilters filters={filters} onApply={apply} />
      {banner && <Banner message={banner} />}
      {!banner && (
        <div className="grid items-start gap-6 lg:grid-cols-2">
          <Section title="Exchanges per minute by airlock" loading={loading}>
            {data && (
              <LineChart
                lines={data.exchanges_per_minute}
                keys={keys}
                from={from}
                to={to}
                unit="Exchanges per minute"
                format={(v) => `${short(v)} per minute`}
              />
            )}
          </Section>
          <Section title="Tokens per minute by airlock" loading={loading}>
            {data && (
              <LineChart
                lines={data.tokens_per_minute}
                keys={keys}
                from={from}
                to={to}
                unit="Tokens per minute, in and out"
                format={(v) => `${short(v)} per minute`}
              />
            )}
          </Section>
          <Section title="p95 exchange duration" loading={loading}>
            {data && (
              <LineChart
                lines={data.p95_duration_seconds}
                keys={keys}
                from={from}
                to={to}
                unit="Seconds within which 95% of the exchanges ended"
                format={seconds}
              />
            )}
          </Section>
          <Section title="Refusals by reason" loading={loading}>
            {data && (
              <BarChart
                bars={data.refusals}
                empty="No refusals in this period."
                label={(r) => REASONS[r] ?? r}
              />
            )}
          </Section>
          <Section title="Tool calls by tool" loading={loading}>
            {data && <BarChart bars={data.tool_calls} empty="No tool calls in this period." />}
          </Section>
        </div>
      )}
    </div>
  );
}
