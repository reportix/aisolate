"use client";

import { useStoredToggle } from "@aisolate/ui";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, type ReactNode } from "react";
import { AuthGate } from "@/components/AuthGate";
import { SessionProvider, useSession } from "@/components/Session";
import { PageFrame } from "@/components/PageFrame";
import { MobileHeader, Sidebar } from "@/components/Sidebar";
import { Unavailable } from "@/components/Unavailable";
import { useConnection } from "@/lib/connection";
import { useMonitoring } from "@/lib/monitoring";
import { auditsOnly, railOf, trailOf } from "@/lib/rail";
import { useOverview } from "@/lib/useOverview";

/** The AIsolate UI: the rail on the gray ground, the page (top bar included) on one white rounded panel beside it. */
export default function AppLayout({ children }: { children: ReactNode }) {
  return (
    <AuthGate>
      {(user) => (
        <SessionProvider user={user}>
          <Shell>{children}</Shell>
          <Unavailable />
        </SessionProvider>
      )}
    </AuthGate>
  );
}

/** Signed in only: the overview it names the rail and the trail from needs the token. */
function Shell({ children }: { children: ReactNode }) {
  const [collapsed, toggle] = useStoredToggle("aisolate:sidebar", false);
  const pathname = usePathname();
  const { data } = useOverview();
  const session = useSession();
  // A developer, who sees a space, reads its audit and AIsolate's LLMs and data sources too.
  const developer = Boolean(data?.spaces.length);
  const views = useMonitoring(session.admin || developer);
  const viewer = {
    admin: session.admin,
    auditor: session.auditor,
    developer,
    monitoring: views.tracing || views.metrics,
  };
  const rail = railOf(pathname, data, viewer);
  // An auditor alone has no dashboard to see; the overview first, so a developer stays on it.
  const toAudit = Boolean(data) && auditsOnly(viewer) && pathname === "/";
  const router = useRouter();
  useEffect(() => {
    if (toAudit) router.replace("/audit");
  }, [toAudit, router]);
  // Behind the Unavailable overlay nothing takes focus or clicks.
  const down = useConnection().state !== "up";
  return (
    <div className="flex min-h-screen" inert={down}>
      <Sidebar rail={rail} collapsed={collapsed} onToggle={toggle} />
      <div className="flex min-w-0 flex-1 flex-col md:py-2 md:pr-2">
        <MobileHeader rail={rail} />
        <div className="flex flex-1 flex-col bg-paper md:rounded-lg md:border md:border-hairline">
          <PageFrame trail={trailOf(pathname, data)}>{!toAudit && children}</PageFrame>
        </div>
      </div>
    </div>
  );
}
