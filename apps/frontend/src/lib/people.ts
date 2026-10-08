import { listOf } from "./text";
import type { Member, Person, Role } from "./types";

/** E-mails compare without case, as admission compares them. */
export const sameEmail = (a: string, b: string) => a.toLowerCase() === b.toLowerCase();

/** `current` with `added` after it, each e-mail once, the first spelling kept. */
export function addUsers(current: string[], added: string[]): string[] {
  return added.reduce(
    (all, email) => (all.some((e) => sameEmail(e, email)) ? all : [...all, email]),
    current,
  );
}

/** `current` without `email`, whatever its case. */
export const removeUser = (current: string[], email: string) =>
  current.filter((e) => !sameEmail(e, email));

/** A space's members with `added` after them in `role`; one already a member keeps their role. */
export const addMembers = (current: Member[], added: string[], role: Role): Member[] =>
  addUsers(
    current.map((m) => m.email),
    added,
  ).map((email) => current.find((m) => m.email === email) ?? { email, role });

export const removeMember = (current: Member[], email: string) =>
  current.filter((m) => !sameEmail(m.email, email));

/** `email` in `role`, everyone else as they were. */
export const withRole = (current: Member[], email: string, role: Role) =>
  current.map((m) => (sameEmail(m.email, email) ? { ...m, role } : m));

/**
 * What Add brings: the ticked people's e-mails, or with no directory connected the typed ones, one
 * per line or comma-separated.
 */
export const chosenEmails = (directory: boolean, ticked: Person[], typed: string) =>
  directory ? ticked.map((p) => p.email) : listOf(typed);

/** How long the field waits after the last keystroke before it asks the directory. */
export const SEARCH_DELAY = 250;

/**
 * A search as the user types: `run(text)` asks `search` once the text has stood still for `delay`
 * ms, and `show` gets the answer only while it is still the latest; blank text clears at once.
 */
export function searchAsYouType(
  search: (text: string) => Promise<Person[]>,
  show: (people: Person[] | null, error?: unknown) => void,
  delay = SEARCH_DELAY,
) {
  let timer: ReturnType<typeof setTimeout> | undefined;
  let latest = 0;
  const run = (text: string) => {
    clearTimeout(timer);
    const asked = ++latest;
    if (!text.trim()) return show(null);
    timer = setTimeout(() => {
      search(text.trim()).then(
        (people) => asked === latest && show(people),
        (e) => asked === latest && show(null, e),
      );
    }, delay);
  };
  return { run, cancel: () => clearTimeout(timer) };
}
