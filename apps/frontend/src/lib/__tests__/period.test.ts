import { describe, expect, it } from "vitest";
import { periodOf } from "../period";

describe("periodOf", () => {
  it.each(["10m", "1h", "24h", "7d", "30d", "all"] as const)(
    "givenAKnownPeriod_%s_whenRead_thenItIsKept",
    (raw) => {
      // Arrange / Act
      const period = periodOf(raw, "all");

      // Assert
      expect(period).toBe(raw);
    },
  );

  it.each([
    ["Empty", ""],
    ["Unknown", "2y"],
    ["AnObjectsOwnName_constructor", "constructor"],
    ["AnObjectsOwnName_toString", "toString"],
    ["AnObjectsOwnName_hasOwnProperty", "hasOwnProperty"],
    ["TheProtoAccessor", "__proto__"],
  ])("givenAName_%s_whenRead_thenTheFallback", (_, raw) => {
    // Arrange / Act
    const period = periodOf(raw, "24h");

    // Assert
    expect(period).toBe("24h");
  });
});
