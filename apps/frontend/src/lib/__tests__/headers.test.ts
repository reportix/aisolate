import { describe, expect, it } from "vitest";
import nextConfig from "../../../next.config";

describe("next.config headers", () => {
  it("givenAnyPage_whenServed_thenNoPageMayFrameIt", async () => {
    // Arrange / Act
    const [all] = await nextConfig.headers!();
    const value = (key: string) => all.headers.find((h) => h.key === key)?.value;
    // Assert
    expect(all.source).toBe("/:path*");
    expect(value("Content-Security-Policy")).toBe("frame-ancestors 'none'");
    expect(value("X-Content-Type-Options")).toBe("nosniff");
  });
});
