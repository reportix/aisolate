const relative = new Intl.RelativeTimeFormat("en", { numeric: "always" });

/** "5 days ago" for a header; the exact timestamp belongs in a title attribute next to it. */
export function ago(iso: string, now: number = Date.now()): string {
  const seconds = Math.max(0, Math.round((now - Date.parse(iso)) / 1000));
  if (seconds < 60) return "just now";
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return relative.format(-minutes, "minute");
  const hours = Math.round(minutes / 60);
  if (hours < 48) return relative.format(-hours, "hour");
  return relative.format(-Math.round(hours / 24), "day");
}

export const stamp = (iso: string) => new Date(iso).toLocaleString();

/** "10:43" today, the date before it on another day. */
export function clock(iso: string, now: Date = new Date()): string {
  const d = new Date(iso);
  const time = d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  return d.toDateString() === now.toDateString() ? time : `${d.toLocaleDateString()} ${time}`;
}
