"use client";

import {
  AIsolateLogo,
  ActivityIcon,
  AirlockIcon,
  AuditIcon,
  CpuIcon,
  DatabaseIcon,
  HomeIcon,
  PanelLeftIcon,
  SpacesIcon,
  Tile,
  UserMenu,
  cx,
} from "@aisolate/ui";
import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ComponentType } from "react";
import { logout } from "@/lib/auth";
import type { Rail, RailIcon, RailRow } from "@/lib/rail";
import { useSession } from "./Session";

const ICONS: Record<RailIcon, ComponentType<{ size?: number; className?: string }>> = {
  home: HomeIcon,
  spaces: SpacesIcon,
  audit: AuditIcon,
  airlocks: AirlockIcon,
  llm: CpuIcon,
  "data-sources": DatabaseIcon,
  monitoring: ActivityIcon,
};

type Props = { rail: Rail; collapsed: boolean; onToggle: () => void };

/** The rail on the gray ground: the wordmark, then the rail of the page's context (see railOf). */
export function Sidebar({ rail, collapsed, onToggle }: Props) {
  return (
    <aside
      className={cx(
        "hidden shrink-0 bg-desk text-ink-secondary transition-[width] duration-200 ease-out md:block",
        collapsed ? "w-14" : "w-64",
      )}
    >
      {/* The aside stretches to the page; only this column sticks, so the rail is never cut short. */}
      <div className="sticky top-0 flex h-screen flex-col">
        <div
          className={cx(
            "relative flex h-14 items-center",
            collapsed ? "justify-center" : "pr-2 pl-4",
          )}
        >
          {/* The mark and the name, as GitLab's tanuki and wordmark, with the "Homepage" tip; folded, the name slides away and the toggle takes the hover. */}
          <Link
            href="/"
            aria-label="Homepage"
            aria-hidden={collapsed}
            tabIndex={collapsed ? -1 : 0}
            className="group relative flex text-ink"
          >
            <AIsolateLogo folded={collapsed} />
            {!collapsed && (
              <span
                aria-hidden="true"
                className="pointer-events-none absolute top-1/2 left-[calc(100%+8px)] z-20 hidden -translate-y-1/2 rounded-md bg-ink px-2 py-1 text-xs font-medium whitespace-nowrap text-ink-inverse group-hover:block group-focus-visible:block"
              >
                Homepage
              </span>
            )}
          </Link>
          {/* Folded, the mark stands alone; the expand toggle lies over it and shows on hover or focus. */}
          <button
            type="button"
            onClick={onToggle}
            aria-label={collapsed ? "Expand sidebar" : "Collapse sidebar"}
            aria-expanded={!collapsed}
            className={cx(
              "rounded p-2 transition duration-150 hover:bg-panel hover:text-ink",
              collapsed
                ? "absolute bg-desk opacity-0 hover:opacity-100 focus-visible:opacity-100"
                : "ml-auto",
            )}
          >
            <PanelLeftIcon size={18} />
          </button>
        </div>
        <nav
          aria-label="AIsolate UI"
          className="flex min-h-0 flex-1 flex-col gap-0.5 overflow-x-hidden overflow-y-auto px-2 pt-2 pb-4"
        >
          {rail.label && (
            // Folded, the label fades but keeps its line, so the rows stay where they were.
            <p
              className={cx(
                // GitLab's "Project" title: ink, semibold, a step clear of the rows above.
                "truncate px-3 pt-2 pb-1 text-sm leading-5 font-semibold text-ink transition-opacity duration-200 ease-out",
                collapsed && "opacity-0",
              )}
            >
              {rail.label}
            </p>
          )}
          {rail.rows.map((row) => (
            <RailLink
              key={row.key}
              row={row}
              collapsed={collapsed}
              // The sections sit 16px in under the space's or airlock's own row.
              nested={rail.context !== "top" && !row.entity}
            />
          ))}
        </nav>
      </div>
    </aside>
  );
}

type LinkProps = { row: RailRow; collapsed: boolean; nested: boolean };

/** One row: an icon (the entity's tile) and its label; folded, the icon alone, the label its title. */
function RailLink({ row, collapsed, nested }: LinkProps) {
  const Icon = row.icon && ICONS[row.icon];
  const pathname = usePathname();
  return (
    <Link
      href={row.href}
      // An entity's name can be cut by the rail's width, so its title always holds it whole.
      title={collapsed || row.entity ? row.label : undefined}
      // A section seen from one of its sub-pages is lit, but not the page itself.
      aria-current={row.lit ? (row.href === pathname ? "page" : "true") : undefined}
      className={cx(
        "flex items-center rounded text-sm transition-[color,background-color,gap,padding,margin] duration-200 ease-out",
        // 6px on the entity row: its 24px tile keeps it at the 36px of the text rows.
        row.entity ? "py-1.5" : "py-2",
        collapsed
          ? "justify-center gap-0 px-0"
          : cx("px-3", row.entity ? "gap-2" : "gap-3", nested && "ml-4"),
        row.lit
          ? "bg-pen-wash font-medium text-pen"
          : cx("hover:bg-panel hover:text-ink", row.entity && "font-medium text-ink"),
      )}
    >
      {row.entity ? (
        <Tile id={row.entity.id} name={row.entity.name} size="sm" />
      ) : (
        Icon && <Icon size={18} className="shrink-0" />
      )}
      <span
        className={cx(
          "truncate transition-[max-width,opacity] duration-200 ease-out",
          collapsed ? "max-w-0 opacity-0" : "max-w-48 opacity-100",
        )}
      >
        {row.label}
      </span>
    </Link>
  );
}

/** Below md the rail becomes a bar: wordmark and the user, then the context's rows as a wrapping row of links. */
export function MobileHeader({ rail }: { rail: Rail }) {
  const session = useSession();
  const pathname = usePathname();
  return (
    <header className="border-b border-hairline bg-paper text-ink-secondary md:hidden">
      <div className="flex h-14 items-center justify-between px-4">
        <Link href="/" aria-label="Homepage" className="flex text-ink">
          <AIsolateLogo />
        </Link>
        <UserMenu name={session.name} email={session.email} onSignOut={() => void logout()} />
      </div>
      <nav aria-label="AIsolate UI" className="flex flex-wrap gap-1 px-3 pb-3">
        {rail.rows.map((row) => {
          const Icon = row.icon && ICONS[row.icon];
          return (
            <Link
              key={row.key}
              href={row.href}
              title={row.entity ? row.label : undefined}
              aria-current={row.lit ? (row.href === pathname ? "page" : "true") : undefined}
              className={cx(
                // The entity's 24px tile keeps its row at the 32px of the icon rows.
                "flex max-w-full shrink-0 items-center gap-2 rounded px-3 text-sm",
                row.entity ? "py-1" : "py-1.5",
                row.lit ? "bg-pen-wash font-medium text-pen" : "hover:bg-desk hover:text-ink",
              )}
            >
              {row.entity ? (
                <Tile id={row.entity.id} name={row.entity.name} size="sm" />
              ) : (
                Icon && <Icon size={16} />
              )}
              <span className="truncate">{row.label}</span>
            </Link>
          );
        })}
      </nav>
    </header>
  );
}
