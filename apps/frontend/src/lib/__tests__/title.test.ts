import { describe, expect, it } from "vitest";
import { tabTitle } from "../title";

describe("tabTitle", () => {
  it.each([
    ["Dashboard", "AIsolate | Dashboard"],
    ["Models", "AIsolate | Models"],
    ["Acme Financial Group", "AIsolate | Acme Financial Group"],
  ])("givenThePage%s_whenTitled_thenTheTabReadsAIsolateThenIt", (page, expected) => {
    // Arrange / Act / Assert
    expect(tabTitle(page)).toBe(expected);
  });

  it("givenNoPageName_whenTitled_thenTheTabReadsAIsolateAlone", () => {
    // Arrange / Act / Assert
    expect(tabTitle(undefined)).toBe("AIsolate");
  });
});
