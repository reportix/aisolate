/** The letter an avatar shows for a user: the first character of their name, or "?" without one. */
export function initialOf(name: string | null | undefined): string {
  const trimmed = name?.trim();
  if (!trimmed) return "?";
  return Array.from(trimmed)[0].toUpperCase();
}
