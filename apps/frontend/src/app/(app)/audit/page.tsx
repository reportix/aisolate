"use client";

import { Banner, Button, Field, Section, fieldClass } from "@aisolate/ui";
import { type FormEvent, useState } from "react";
import { AuditTable } from "@/components/AuditTable";
import { PageHeader } from "@/components/PageHeader";
import { PeriodSelect } from "@/components/PeriodSelect";
import { api, onApiError } from "@/lib/api";
import { type AuditFilters, auditFiltersOf, auditSearch, eventsQuery } from "@/lib/audit";
import { getAccessToken } from "@/lib/auth";
import { useLoad } from "@/lib/useLoad";

const FAIL = "Could not load the audit trail";

/** Every audit event, newest first, found by one search over a period, a page at a time. */
export default function AuditPage() {
  // The shell renders a page only once signed in, in the browser, so the address is there to read.
  const [scope, setScope] = useState(() => ({
    filters: typeof window === "undefined" ? {} : auditFiltersOf(window.location.search),
    now: Date.now(),
  }));
  const { filters, now } = scope;
  // The first page per filter set; Load more appends to it.
  const {
    data: list,
    setData: setList,
    banner,
    setBanner,
  } = useLoad(eventsQuery(filters, now), (t) => api.audit.events(t, filters, now), FAIL);
  const [more, setMore] = useState(false);

  const apply = (next: AuditFilters) => {
    // The address keeps them, so the back button from an event brings this list back.
    history.replaceState(null, "", `/audit${auditSearch(next)}`);
    setScope({ filters: next, now: Date.now() });
  };

  const loadMore = async () => {
    if (!list?.next_cursor) return;
    setMore(true);
    try {
      const page = await api.audit.events(await getAccessToken(), filters, now, list.next_cursor);
      setList({ events: [...list.events, ...page.events], next_cursor: page.next_cursor });
    } catch (e) {
      onApiError(e, FAIL, { banner: setBanner });
    } finally {
      setMore(false);
    }
  };

  // The search applies on Enter, the period at once.
  const search = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    apply({ ...filters, q: String(new FormData(e.currentTarget).get("q") ?? "") });
  };

  return (
    <div className="grid gap-6">
      <PageHeader
        title="Audit"
        description="Every exchange and refused request of every airlock, newest first, as the airlock recorded it."
      />
      <form onSubmit={search} aria-label="Filters" className="flex flex-wrap items-end gap-3">
        <div className="w-full sm:w-96">
          <Field id="f-q" label="Search">
            <input
              id="f-q"
              name="q"
              type="search"
              defaultValue={filters.q}
              placeholder="User, trace, client, space, airlock or question"
              className={fieldClass}
            />
          </Field>
        </div>
        <PeriodSelect
          allTime
          value={filters.period ?? "all"}
          onChange={(period) => apply({ ...filters, period })}
        />
      </form>
      {banner && <Banner message={banner} />}
      {!banner && (
        <Section loading={!list && { rows: 4, cols: 9 }}>
          {list && <AuditTable events={list.events} />}
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
