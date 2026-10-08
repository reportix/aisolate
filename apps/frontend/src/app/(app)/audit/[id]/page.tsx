"use client";

import { Banner, Section, TableSkeleton, buttonClass } from "@aisolate/ui";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { AuditEventView } from "@/components/AuditEvent";
import { ExportMenu } from "@/components/ExportMenu";
import { PageHeader } from "@/components/PageHeader";
import { api, onApiError } from "@/lib/api";
import { type ChainStatus, type ExportFormat, outcomeLabel, traceLink, utc } from "@/lib/audit";
import { getAccessToken } from "@/lib/auth";
import { env } from "@/lib/env";
import { useMonitoring } from "@/lib/monitoring";
import { useLoad } from "@/lib/useLoad";

/** One audit event, and whether its airlock's chain holds. */
export default function AuditEventPage() {
  const { id } = useParams<{ id: string }>();
  const { data, banner, setBanner } = useLoad(
    `audit/${id}`,
    (t) => api.audit.event(t, id),
    "Could not load this audit event",
  );
  const [chain, setChain] = useState<ChainStatus | null>(null);
  const [exporting, setExporting] = useState(false);
  const space = data?.event.space;
  const airlock = data?.event.airlock;

  // The chain is the airlock's, not the event's: a failure leaves the event readable.
  useEffect(() => {
    if (!space || !airlock) return;
    let cancelled = false;
    void getAccessToken()
      .then((t) => api.audit.verify(t, space, airlock))
      .then(
        (c) => !cancelled && setChain(c),
        () => undefined,
      );
    return () => {
      cancelled = true;
    };
  }, [space, airlock]);

  // This event alone, as the file the backend names.
  const exportAs = async (format: ExportFormat) => {
    setExporting(true);
    try {
      const { blob, filename } = await api.audit.exportEvent(await getAccessToken(), id, format);
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = filename;
      link.click();
      // Revoked on the next task: Firefox and Safari can cancel a download whose URL goes at once.
      setTimeout(() => URL.revokeObjectURL(url), 0);
    } catch (e) {
      onApiError(e, "Could not export this audit event", { banner: setBanner });
    } finally {
      setExporting(false);
    }
  };

  const e = data?.event;
  // Monitoring's own trace page when the install has one and the reader may open it; else a viewer's.
  const views = useMonitoring(true);
  const trace = !e?.trace_id
    ? null
    : views.tracing
      ? `/monitoring/traces/${encodeURIComponent(e.trace_id)}`
      : traceLink(env.traceUrl, e.trace_id);
  return (
    <div className="grid gap-6">
      <PageHeader
        title={`Event ${id}`}
        description={e && `${e.space}/${e.airlock} · ${utc(e.ts)} UTC · ${outcomeLabel(e)}`}
        actions={
          data && (
            <div className="flex items-center gap-2">
              {trace &&
                (views.tracing ? (
                  <Link href={trace} className={buttonClass("secondary")}>
                    View trace
                  </Link>
                ) : (
                  <a
                    href={trace}
                    target="_blank"
                    rel="noopener noreferrer"
                    className={buttonClass("secondary")}
                  >
                    View trace
                  </a>
                ))}
              <ExportMenu onExport={(f) => void exportAs(f)} busy={exporting} />
            </div>
          )
        }
      />
      {banner && <Banner message={banner} />}
      {!banner && !data && (
        <Section>
          <TableSkeleton rows={4} cols={2} />
        </Section>
      )}
      {data && <AuditEventView detail={data} chain={chain} />}
    </div>
  );
}
