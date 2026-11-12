"use client";

import { cx, InfoIcon } from "@aisolate/ui";
import { useId } from "react";
import { env } from "@/lib/env";

type Props = {
  /** Folded rail: the icon alone, the label its title. */
  collapsed?: boolean;
  /** Where the panel opens: above the rail's foot, or under the mobile header. */
  placement?: "foot" | "head";
};

/**
 * Help, at the rail's foot as Skye's: a native popover (light dismiss, Escape and the top layer
 * from the browser) with the links: the documentation on the AIsolate website, and the source of
 * this AIsolate, which a program used over a network offers its users (AGPL-3.0 section 13).
 */
function HelpLink({ href, children }: { href: string; children: string }) {
  return (
    <a
      href={href}
      target="_blank"
      rel="noopener noreferrer"
      className="block rounded px-4 py-2.5 text-sm text-ink-secondary transition-colors duration-150 hover:bg-desk hover:text-ink"
    >
      {children}
    </a>
  );
}

export function HelpMenu({ collapsed = false, placement = "foot" }: Props) {
  const panelId = useId();
  return (
    <>
      <button
        type="button"
        popoverTarget={panelId}
        title={collapsed ? "Help" : undefined}
        // The whole foot is the button, as Skye's: centred, the hover over all of it.
        className={cx(
          "flex w-full items-center justify-center text-sm transition-[gap,color,background-color] duration-200 ease-out hover:bg-panel hover:text-ink",
          placement === "foot" ? "border-t border-hairline py-3" : "rounded p-2",
          collapsed ? "gap-0" : "gap-2",
        )}
      >
        <InfoIcon size={18} className="shrink-0" />
        <span
          className={cx(
            "truncate transition-[max-width,opacity] duration-200 ease-out",
            collapsed ? "max-w-0 opacity-0" : "max-w-48 opacity-100",
          )}
        >
          Help
        </span>
      </button>
      <div
        id={panelId}
        popover="auto"
        // Skye's rows (40px); the panel stays inside the 256px rail, 8px in on each side.
        className={cx(
          "fixed inset-auto m-0 w-60 rounded-lg border border-hairline bg-paper p-1 text-ink shadow-overlay",
          placement === "foot" ? "bottom-16 left-2" : "top-13 right-14",
        )}
      >
        <HelpLink href={env.docsUrl}>Documentation</HelpLink>
        <HelpLink href={env.sourceUrl}>Source</HelpLink>
      </div>
    </>
  );
}
