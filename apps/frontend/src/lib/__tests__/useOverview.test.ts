import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { loadOverview, reloadOverview, subscribeOverview } from "../useOverview";

describe("loadOverview", () => {
  beforeEach(() => vi.stubEnv("NEXT_PUBLIC_AISOLATE_URL", "http://backend.test"));
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
  });

  it("givenAFreshCache_whenReloadOverview_thenTheNextLoadGoesToTheApi", async () => {
    // Arrange
    const fetch = vi.fn(async () => Response.json([]));
    vi.stubGlobal("fetch", fetch);
    reloadOverview();
    await loadOverview("token");
    await loadOverview("token");
    const before = fetch.mock.calls.length;

    // Act
    reloadOverview();
    await loadOverview("token");

    // Assert
    expect(before).toBe(1);
    expect(fetch).toHaveBeenCalledTimes(2);
  });

  it("givenAnEmptyCache_whenTwoLoadsOverlap_thenTheApiIsCalledOnce", async () => {
    // Arrange
    const fetch = vi.fn(async () => Response.json([]));
    vi.stubGlobal("fetch", fetch);
    reloadOverview();

    // Act
    await Promise.all([loadOverview("token"), loadOverview("token")]);

    // Assert
    expect(fetch).toHaveBeenCalledTimes(1);
  });
});

describe("reloadOverview", () => {
  it("givenAMountedAndAnUnsubscribedListener_whenReloadOverview_thenOnlyTheMountedOneNotified", () => {
    // Arrange
    const mounted = vi.fn();
    const gone = vi.fn();
    const unsubscribe = subscribeOverview(mounted);
    subscribeOverview(gone)();

    // Act
    reloadOverview();

    // Assert
    expect(mounted).toHaveBeenCalledTimes(1);
    expect(gone).not.toHaveBeenCalled();
    unsubscribe();
  });
});
