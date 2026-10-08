import type { Origin } from "./types";

/** A row one level sees: who defines it, and whose row of its id it replaces, if any. */
export type Entry<R> = R & { from: Origin; replaces: Origin | null };

/**
 * The LLMs or data sources one level sees, by id: `levels` nearest first, from the airlock's (or
 * the page's own) up to AIsolate's. A nearer row replaces a farther one of its id, which is left
 * out; the nearer one names the level it replaces. Rows keep their level's order, nearest first.
 */
export function catalogOf<R extends { id: string }>(levels: [Origin, R[]][]): Entry<R>[] {
  const byId = new Map<string, Entry<R>>();
  for (const [from, rows] of levels) {
    for (const row of rows) {
      const nearer = byId.get(row.id);
      if (nearer) nearer.replaces ??= from;
      else byId.set(row.id, { ...row, from, replaces: null });
    }
  }
  return [...byId.values()];
}

/** Where an airlock's value comes from, in GitLab's words: its own is "Direct", else "Inherited from" its definer. */
export function originLabel(from: Origin, names: { space: string }): string {
  if (from === "airlock") return "Direct";
  return `Inherited from ${from === "global" ? "the platform" : names.space}`;
}
