import { describe, expect, it } from "vitest";
import { metricsQuery, slotOf, ticks } from "../metrics";

describe("ticks", () => {
  it.each([
    [0, [0, 1]],
    [1.5, [0, 0.5, 1, 1.5]],
    [7, [0, 2, 4, 6, 8]],
    [1840, [0, 500, 1000, 1500, 2000]],
  ])("givenTheLargestValue_%d_whenTicked_thenRoundStepsFromZeroAboveIt", (max, expected) => {
    // Arrange / Act
    const read = ticks(max);

    // Assert
    expect(read).toEqual(expected);
  });
});

describe("slotOf", () => {
  it("givenAFilterThatHidesAnAirlock_whenColoured_thenTheOthersKeepTheirSlot", () => {
    // Arrange
    const keys = ["acme/hr", "acme/sales", "aldermoor/cardiology"];

    // Act
    const slots = keys.map((k) => slotOf(k, keys));
    const sales = slotOf("acme/sales", keys);

    // Assert
    expect(slots).toEqual([1, 2, 3]);
    expect(sales).toBe(2);
    expect(
      slotOf(
        "acme/x9",
        [...Array(8)].map((_, i) => `s/${i}`),
      ),
    ).toBe(1);
  });
});

describe("metricsQuery", () => {
  it.each([
    [{}, "?period=24h"],
    [{ space: "acme", airlock: "hr", period: "7d" }, "?space=acme&airlock=hr&period=7d"],
  ] as const)("givenTheFilters_%o_whenQueried_then_%s", (filters, query) => {
    // Arrange / Act
    const q = metricsQuery(filters);

    // Assert
    expect(q).toBe(query);
  });
});
