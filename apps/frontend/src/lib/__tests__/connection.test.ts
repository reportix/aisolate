import { describe, expect, it } from "vitest";
import { backoffMs, next, type Connection } from "../connection";

const NOW = 1_000_000;
const NO_JITTER = () => 0.5;
const UP: Connection = { state: "up" };
const down = (attempt: number, nextAt = NOW): Connection => ({
  state: "down",
  kind: "server",
  attempt,
  nextAt,
});

describe("backoffMs", () => {
  it.each([
    [0, 2_000],
    [1, 4_000],
    [4, 30_000],
  ])("givenAttempt%i_whenBackoff_thenWaits%ims", (attempt, expected) => {
    // Arrange / Act
    const ms = backoffMs(attempt, NO_JITTER);
    // Assert
    expect(ms).toBe(expected);
  });

  it("givenJitter_whenBackoff_thenStaysWithinAFifthOfTheBase", () => {
    // Arrange / Act
    const low = backoffMs(0, () => 0);
    const high = backoffMs(0, () => 1);
    // Assert
    expect(low).toBe(1_600);
    expect(high).toBe(2_400);
  });
});

describe("next", () => {
  it.each<[string, Connection, Parameters<typeof next>[1], Connection]>([
    [
      "Up_whenACallFails_thenProbesAfterTheFirstBackoff",
      UP,
      { type: "down", kind: "offline", now: NOW },
      { state: "down", kind: "offline", attempt: 0, nextAt: NOW + 2_000 },
    ],
    [
      "Down_whenAnotherCallFails_thenTheBackoffIsNotReset",
      down(3, NOW + 16_000),
      { type: "down", kind: "server", now: NOW + 1 },
      down(3, NOW + 16_000),
    ],
    [
      "Down_whenTheProbeFails_thenTheNextWaitGrows",
      down(0),
      { type: "probeFailed", now: NOW },
      down(1, NOW + 4_000),
    ],
    ["Down_whenTheProbeAnswers_thenBack", down(2), { type: "probeOk" }, { state: "back" }],
    [
      "Down_whenTheUserRetries_thenTheProbeRunsNow",
      down(4, NOW + 30_000),
      { type: "retryNow", now: NOW },
      down(4, NOW),
    ],
    ["Up_whenTheProbeAnswers_thenNothingChanges", UP, { type: "probeOk" }, UP],
  ])("given%s", (_, from, event, to) => {
    // Arrange / Act
    const c = next(from, event, NO_JITTER);
    // Assert
    expect(c).toEqual(to);
  });
});
