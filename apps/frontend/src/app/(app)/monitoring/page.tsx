"use client";

import { Banner, Button, Section } from "@aisolate/ui";
import { useState } from "react";
import { MonitoringFilters, type Scope } from "@/components/MonitoringFilters";
import { MonitoringHeader } from "@/components/MonitoringHeader";
import { TraceTable } from "@/components/TraceTable";
import { api, onApiError } from "@/lib/api";
import { getAccessToken } from "@/lib/auth";
import { type TraceFilters, filtersSearch, traceFiltersOf, tracesQuery } from "@/lib/trace";
import { useLoad } from "@/lib/useLoad";

const FAIL = "Could not load the traces";

/** Monitoring's Tracing tab: every exchange of the spaces one may see, newest first. */
export default function TracingPage() {
  // The shell renders a page only once signed in, in the browser, so the address is there to read.
  const [scope, setScope] = useState<Scope<TraceFilters>>(() => ({
    filters: typeof window === "undefined" ? { period: "24h" } : traceFiltersOf(location.search),
    now: Date.now(),
  }));
  const { filters, now } = scope;
  const {
    data: list,
    setData: setList,
    banner,
    setBanner,
  } = useLoad(tracesQuery(filters, now), (t) => api.traces.list(t, filters, now), FAIL);
  const [more, setMore] = useState(false);

  const apply = (next: TraceFilters) => {
    // The address keeps them, so the back button from a trace brings this list back.
    history.replaceState(null, "", `/monitoring${filtersSearch(next)}`);
    setScope({ filters: next, now: Date.now() });
  };

  const loadMore = async () => {
    if (!list?.next_cursor) return;
    setMore(true);
    try {
      const page = await api.traces.list(await getAccessToken(), filters, now, list.next_cursor);
      setList({ traces: [...list.traces, ...page.traces], next_cursor: page.next_cursor });
    } catch (e) {
      onApiError(e, FAIL, { banner: setBanner });
    } finally {
      setMore(false);
    }
  };

  return (
    <div className="grid gap-6">
      <MonitoringHeader current="/monitoring" />
      <MonitoringFilters filters={filters} onApply={apply} search />
      {banner && <Banner message={banner} />}
      {!banner && (
        <Section loading={!list && { rows: 5, cols: 7 }}>
          {list && <TraceTable traces={list.traces} now={new Date(now)} />}
        </Section>
      )}
      {list?.next_cursor && (
        <div>
          <Button variant="secondary" onClick={() => void loadMore()} disabled={more}>
            {more ? "Loading…" : "Load more"}
          </Button>
        </div>
      )}
    </div>
  );
}
