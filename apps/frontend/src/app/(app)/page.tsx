"use client";

import {
  AirlockIcon,
  Banner,
  CpuIcon,
  DatabaseIcon,
  Ledger,
  Section,
  Skeleton,
  SpacesIcon,
  Stat,
} from "@aisolate/ui";
import { AirlockTable } from "@/components/AirlockTable";
import { api } from "@/lib/api";
import { useTabTitle } from "@/lib/title";
import { useLoad } from "@/lib/useLoad";
import { useOverview } from "@/lib/useOverview";

/** One fact per section, in the sidebar's order; one barely tinted gray each, never a state. */
const STATS = [
  { key: "spaces", label: "Spaces", tone: "lavender", Icon: SpacesIcon },
  { key: "airlocks", label: "Airlocks", tone: "sand", Icon: AirlockIcon },
  { key: "llms", label: "Models", tone: "mint", Icon: CpuIcon },
  { key: "dataSources", label: "Data sources", tone: "rose", Icon: DatabaseIcon },
] as const;

export default function DashboardPage() {
  // The one page without a PageHeader names its tab itself.
  useTabTitle("Dashboard");
  const { data, error } = useOverview();
  const llms = useLoad("llms", api.llms.list, "Could not load the models");
  const sources = useLoad("data-sources", api.dataSources.list, "Could not load the data sources");
  // A global list that failed to load shows a dash under the banner, not a skeleton forever.
  const counts = {
    spaces: data?.spaces.length,
    airlocks: data?.airlocks.length,
    llms: llms.banner ? "–" : llms.data?.length,
    dataSources: sources.banner ? "–" : sources.data?.length,
  };
  // Without the overview the stats are not shown, so its error stands alone; both lists can fail at once, and the
  // same text shows once (it is the banner's key).
  const problems = error
    ? [error]
    : [...new Set([llms.banner, sources.banner].filter((p) => p !== null))];

  return (
    <div className="grid gap-6">
      {problems.map((p) => (
        <Banner key={p} message={p} />
      ))}
      {!error && (
        <>
          <Ledger>
            {STATS.map(({ key, label, tone, Icon }) => (
              <Stat
                key={key}
                tone={tone}
                icon={<Icon size={16} />}
                label={label}
                value={counts[key] ?? <Skeleton className="h-7 w-10" />}
              />
            ))}
          </Ledger>
          <Section title="Airlocks" loading={!data && { rows: 3, cols: 3 }}>
            {data && <AirlockTable airlocks={data.airlocks} spaces={data.spaces} />}
          </Section>
        </>
      )}
    </div>
  );
}
