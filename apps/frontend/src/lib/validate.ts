import type { DataSourceInput, Llm } from "./types";

/** Same rule as the backend's for data sources and LLMs: a data source's id becomes part of its Secret's name. */
const ENTRY_ID = /^[a-z0-9][a-z0-9_-]{0,40}$/;

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
