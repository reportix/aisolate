/** Fails where the variable is used, not at import; Next inlines NEXT_PUBLIC_* at build time. */
export function required(name: string, value: string | undefined): string {
  if (!value) throw new Error(`Missing environment variable ${name}`);
  return value;
}
