import type { DataSourceInput, Llm } from "./types";

/** Same rule as the backend's for data sources and LLMs: a data source's id becomes part of its Secret's name. */
const ENTRY_ID = /^[a-z0-9][a-z0-9_-]{0,40}$/;

/**
 * Same rule as the backend: 1 to 31 characters, no dash at the end, since the name ends the
 * endpoint and the Kubernetes names and labels of the version's resources.
 */
const VERSION_NAME = /^[a-z0-9]([a-z0-9-]{0,29}[a-z0-9])?$/;

/** Row `i`'s id, in the backend's words: present, well formed, and the first of its spelling. */
function idErrors(rows: { id: string }[], i: number, at: string): string[] {
  const id = rows[i].id.trim();
  if (!id) return [`${at}.id: required`];
  if (!ENTRY_ID.test(id)) return [`${at}.id: lowercase letters, digits, _ or -`];
  if (rows.slice(0, i).some((r) => r.id.trim() === id)) return [`${at}.id: ${id} is listed twice`];
  return [];
}

/**
 * The data sources a space or an airlock sets itself, row by row in the backend's words: an id
 * once, a URL, and a user and password unless a stored Secret holds them. Typed credentials
 * replace the Secret, so a user alone asks for the password too.
 */
export function validateDataSources(rows: DataSourceInput[]): string[] {
  return rows.flatMap((row, i) => {
    const at = `data_sources[${i}]`;
    const errors = idErrors(rows, i, at);
    const url = row.url.trim() !== "";
    if (!url) errors.push(`${at}.url: required`);
    const user = (row.username ?? "").trim() !== "";
    const password = (row.password ?? "") !== "";
    if (password && !user) errors.push(`${at}.username: required with password`);
    if (url && !password && (!row.secret_ref || user))
      errors.push(`${at}.password: required with url`);
    return errors;
  });
}

/** The name of a space or an airlock: shown everywhere, free to change. */
export function validateName(name: string): string[] {
  return name.trim() ? [] : ["name: required"];
}

/**
 * The LLMs one level defines, row by row in the backend's words: an id once, a provider, a URL and a
 * model. An id another level also has is no problem: this one replaces it there.
 */
export function validateLlms(rows: Llm[]): string[] {
  return rows.flatMap((row, i) => {
    const at = `llms[${i}]`;
    const errors = idErrors(rows, i, at);
    for (const field of ["provider", "url", "model"] as const) {
      if (!row[field].trim()) errors.push(`${at}.${field}: required`);
    }
    return errors;
  });
}

/** A new version's LLM: the backend refuses a version without one. */
export const validateMainLlm = (id: string) =>
  id.trim() ? [] : ["llm: name the model that answers, for example granite"];

/**
 * A new version's name, as the backend checks it; an edited version keeps its name. Keyed "name",
 * the field of the form that asks for it.
 */
export function validateVersion(name: string): string[] {
  const trimmed = name.trim();
  if (!trimmed) return ["name: required"];
  if (!VERSION_NAME.test(trimmed)) return ["name: lowercase letters, digits and dashes"];
  // The page that creates a version sits at versions/new: one by that name could not be opened.
  return trimmed === "new" ? ["name: new is reserved"] : [];
}
