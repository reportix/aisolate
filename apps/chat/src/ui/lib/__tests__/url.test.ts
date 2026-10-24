import { describe, expect, it } from "vitest";
import { trimSlash } from "../url";

describe("trimSlash", () => {
  it("givenUrls_whenTrimSlash_thenOnlyTrailingSlashesRemoved", () => {
    // Arrange / Act
    const trailing = trimSlash("http://aisolate.example///");
    const path = trimSlash("http://aisolate.example/a");
    // Assert
    expect(trailing).toBe("http://aisolate.example");
    expect(path).toBe("http://aisolate.example/a");
  });
});
