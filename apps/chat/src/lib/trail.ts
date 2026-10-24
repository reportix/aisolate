import type { Verdict } from "./types";

export const OUTCOMES: Record<Verdict["status"], string> = {
  PASS: "pass",
  WARN: "warning",
  FAIL: "fail",
};

/** A verifier's word as a line of the trail: its note, or just its outcome when it left none. */
export function checkLine({ handler, status, note }: Verdict): string {
  const name = handler.replace(/([a-z])([A-Z])/g, "$1 $2").toLowerCase();
  const said = note?.trim() || (OUTCOMES[status] ?? status);
  return `${name.charAt(0).toUpperCase()}${name.slice(1)}: ${said}`;
}
