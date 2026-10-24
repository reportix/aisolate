import { describe, expect, it } from "vitest";
import nextConfig from "../../next.config";

describe("next.config headers", () => {
  it("givenAnyPage_whenServed_thenImagesStayOnThisOriginAndNoOtherSiteFramesIt", async () => {
    // Arrange / Act
    const [all] = await nextConfig.headers!();
    const csp = all.headers.find((h) => h.key === "Content-Security-Policy")?.value;
    // Assert
    expect(all.source).toBe("/:path*");
    expect(csp).toContain("img-src 'self' data:");
    expect(csp).toContain("frame-ancestors 'self'");
  });
});
