import { describe, expect, it } from "vitest";
import { ago } from "../time";

describe("ago", () => {
  const now = Date.parse("2026-10-17T12:00:00Z");

  it.each([
    ["2026-10-17T11:59:30Z", "just now"],
    ["2026-10-17T11:59:00Z", "1 minute ago"],
    ["2026-10-17T11:55:00Z", "5 minutes ago"],
    ["2026-10-17T09:00:00Z", "3 hours ago"],
    ["2026-10-12T12:00:00Z", "5 days ago"],
  ])("givenStamp_%s_whenAgo_thenWords_%s", (iso, expected) => {
    // Arrange / Act
    const said = ago(iso, now);

    // Assert
    expect(said).toBe(expected);
  });
});
