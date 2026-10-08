/**
 * A description's one-line lead, its first sentence up to a colon that opens the details, and
 * whether more follows it.
 */
export function leadOf(text: string): { lead: string; more: boolean } {
  const [lead, ...rest] = text.trim().split(/[.:](?:\s|$)/);
  return { lead, more: rest.some(Boolean) };
}

/** The names a field holds, one per line or comma-separated, trimmed, blanks dropped. */
export const listOf = (text: string) =>
  text
    .split(/[\n,]/)
    .map((s) => s.trim())
    .filter(Boolean);

/** Backend messages without their "field: " prefix, for a page with no field to show them under. */
export const plainErrors = (errors: string[]) =>
  errors.map((e) => e.slice(e.indexOf(":") + 1).trim()).join(" ");

/** A long id cut to its ends, "ff802241…897b"; the whole one goes in a title. */
export const shortId = (id: string) => (id.length > 16 ? `${id.slice(0, 8)}…${id.slice(-4)}` : id);

const counts = new Intl.NumberFormat("en");

/** A count with its thousands separated: "1,840". */
export const number = (n: number) => counts.format(n);
