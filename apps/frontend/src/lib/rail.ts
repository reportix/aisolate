import { shortId } from "./text";
import type { Overview } from "./useOverview";

type RailContext = "top" | "space" | "airlock";
export type RailIcon =
  "home" | "spaces" | "audit" | "airlocks" | "users" | "llm" | "data-sources" | "monitoring";

export type RailRow = {
  key: string;
  href: string;
  label: string;
  icon: RailIcon | null;
  lit: boolean;
  /** The space's or airlock's own row: its tile stands where the icon would. */
  entity?: { id: string; name: string };
};

export type Rail = { context: RailContext; label: "Space" | "Airlock" | null; rows: RailRow[] };

/** A section's key is also the path segment it lives at, under its space or airlock. */
const SECTIONS = {
  airlocks: { label: "Airlocks", icon: "airlocks" },
  users: { label: "Users", icon: "users" },
  llms: { label: "Models", icon: "llm" },
  "data-sources": { label: "Data sources", icon: "data-sources" },
} as const satisfies Record<string, { label: string; icon: RailIcon }>;

type Section = keyof typeof SECTIONS;

const SPACE_SECTIONS: Section[] = ["airlocks", "users", "llms", "data-sources"];
const AIRLOCK_SECTIONS: Section[] = ["users", "llms", "data-sources"];

/** The airlock's own tabs, under its row in the trail; the rail lights its row for them. */
const TABS: Record<string, string> = { details: "Details", tools: "Tools" };

/** What an add or edit page of a section is about: "Add model", "Edit data source". */
const NOUNS: Partial<Record<Section, string>> = { llms: "model", "data-sources": "data source" };

/**
 * Who is signed in: `admin` and `auditor` from the realm's roles (the admin audits too), `developer`
 * when they see a space, `monitoring` when the install has a view of it for them.
 */
export type Viewer = { admin: boolean; auditor: boolean; developer: boolean; monitoring: boolean };

const NOBODY: Viewer = { admin: false, auditor: false, developer: false, monitoring: false };

/** An auditor and nothing else: the audit is all they may read, so it is their whole rail. */
export const auditsOnly = (v: Viewer) => v.auditor && !v.admin && !v.developer;

/**
 * Dashboard, Spaces, then for who may read them AIsolate's Models and Data sources, Monitoring and
 * the Audit; an auditor alone sees the Audit only. Each is lit anywhere under its path.
 */
function topRows(pathname: string, v: Viewer): RailRow[] {
  const root = pathname.split("/").filter(Boolean)[0];
  const row = (key: string, label: string, icon: RailIcon): RailRow => ({
    key,
    href: `/${key}`,
    label,
    icon,
    lit: root === key,
  });
  const audit = row("audit", "Audit", "audit");
  if (auditsOnly(v)) return [audit];
  return [
    { key: "dashboard", href: "/", label: "Dashboard", icon: "home", lit: !root },
    row("spaces", "Spaces", "spaces"),
    ...(v.admin || v.developer
      ? [row("llms", "Models", "llm"), row("data-sources", "Data sources", "data-sources")]
      : []),
    ...(v.monitoring ? [row("monitoring", "Monitoring", "monitoring")] : []),
    ...(v.auditor || v.developer ? [audit] : []),
  ];
}

/** The entity's row, then its sections under it; the entity row is lit when no section is. */
function contextRail(
  context: "space" | "airlock",
  base: string,
  entity: { id: string; name: string },
  sections: Section[],
  segment: string | undefined,
): Rail {
  const current = sections.find((s) => s === segment);
  return {
    context,
    label: context === "space" ? "Space" : "Airlock",
    rows: [
      { key: context, href: base, label: entity.name, icon: null, lit: !current, entity },
      ...sections.map((s) => ({
        key: s,
        href: `${base}/${s}`,
        label: SECTIONS[s].label,
        icon: SECTIONS[s].icon,
        lit: s === current,
      })),
    ],
  };
}

/**
 * The rail a path shows: one context per level. Top (Dashboard, Spaces, Models, Data sources,
 * Audit), a space (its row, then Airlocks, Users, Models, Data sources) or an airlock (its row, then Users,
 * Models, Data sources). Entity rows carry the name from the overview, the id until it arrives.
 */
export function railOf(pathname: string, overview: Overview | null, viewer: Viewer = NOBODY): Rail {
  const [root, spaceId, segment, airlockId, section] = pathname.split("/").filter(Boolean);
  if (root !== "spaces" || !spaceId || spaceId === "new") {
    return { context: "top", label: null, rows: topRows(pathname, viewer) };
  }
  // An airlock id is only unique within its space; `new` is the create page, not an airlock.
  if (segment === "airlocks" && airlockId && airlockId !== "new") {
    const airlock = overview?.airlocks.find((a) => a.space_id === spaceId && a.id === airlockId);
    const entity = { id: airlockId, name: airlock?.name || airlockId };
    return contextRail(
      "airlock",
      `/spaces/${spaceId}/airlocks/${airlockId}`,
      entity,
      AIRLOCK_SECTIONS,
      section,
    );
  }
  const entity = {
    id: spaceId,
    name: overview?.spaces.find((s) => s.id === spaceId)?.name || spaceId,
  };
  return contextRail("space", `/spaces/${spaceId}`, entity, SPACE_SECTIONS, segment);
}

export type Crumb = { label: string; href?: string };
/** What the top bar shows; `heading` on the dashboard, whose last crumb is its only title. */
export type Trail = { crumbs: Crumb[]; heading: boolean };

/**
 * The top bar's trail for a path, read from it as the rail is: Spaces › space [› Airlocks ›
 * airlock] › section › form, or AIsolate's Models or Data sources › form; names from the overview,
 * ids until it arrives. Every page but the dashboard has a title of its own, so only the
 * dashboard's crumb is its heading. A trace's crumb is its id cut to its ends, as its title.
 */
export function trailOf(pathname: string, overview: Overview | null): Trail {
  const [root, spaceId, ...rest] = pathname.split("/").filter(Boolean);
  if (root === "monitoring") {
    const top = { label: "Monitoring", href: "/monitoring" };
    if (spaceId === "metrics") return { crumbs: [top, { label: "Metrics" }], heading: false };
    if (spaceId !== "traces" || !rest[0])
      return { crumbs: [top, { label: "Tracing" }], heading: false };
    return {
      crumbs: [
        top,
        { label: "Tracing", href: "/monitoring" },
        { label: `Trace ${shortId(rest[0])}` },
      ],
      heading: false,
    };
  }
  if (root === "audit") {
    return {
      crumbs: spaceId
        ? [{ label: "Audit", href: "/audit" }, { label: `Event ${spaceId}` }]
        : [{ label: "Audit" }],
      heading: false,
    };
  }
  if (root === "llms" || root === "data-sources") {
    return sectionTrail([], "", root, spaceId ? [spaceId, ...rest] : []);
  }
  if (root !== "spaces") return { crumbs: [{ label: "Dashboard" }], heading: true };
  if (!spaceId) return { crumbs: [{ label: "Spaces" }], heading: false };
  const crumbs: Crumb[] = [{ label: "Spaces", href: "/spaces" }];
  const end = (label: string) => ({ crumbs: [...crumbs, { label }], heading: false });
  if (spaceId === "new") return end("New space");

  let base = `/spaces/${spaceId}`;
  crumbs.push({
    label: overview?.spaces.find((s) => s.id === spaceId)?.name || spaceId,
    href: base,
  });
  let [segment, ...tail] = rest;
  if (segment === "airlocks") {
    const [airlockId, ...after] = tail;
    if (!airlockId) return end("Airlocks");
    crumbs.push({ label: "Airlocks", href: `${base}/airlocks` });
    if (airlockId === "new") return end("New airlock");
    base += `/airlocks/${airlockId}`;
    const airlock = overview?.airlocks.find((a) => a.space_id === spaceId && a.id === airlockId);
    crumbs.push({ label: airlock?.name || airlockId, href: base });
    [segment, ...tail] = after;
  }
  // The entity's own page: its crumb ends the trail, unlinked.
  if (!segment) return end(crumbs.pop()!.label);
  return sectionTrail(crumbs, base, segment, tail);
}

/** A section's crumb under `base`, then its add or edit page's. */
function sectionTrail(crumbs: Crumb[], base: string, segment: string, tail: string[]): Trail {
  const label = SECTIONS[segment as Section]?.label ?? TABS[segment] ?? segment;
  const end = (last: string) => ({ crumbs: [...crumbs, { label: last }], heading: false });
  if (tail.length === 0) return end(label);
  crumbs = [...crumbs, { label, href: `${base}/${segment}` }];
  if (tail[0] === "add") return end("Add users");
  const noun = NOUNS[segment as Section] ?? segment;
  return end(`${tail[0] === "new" ? "Add" : "Edit"} ${noun}`);
}
