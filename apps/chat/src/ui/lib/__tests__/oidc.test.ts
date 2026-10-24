import type { User } from "oidc-client-ts";
import { describe, expect, it } from "vitest";
import { displayName } from "../oidc";

const userWith = (profile: Record<string, unknown>) => ({ profile }) as unknown as User;

describe("displayName", () => {
  it("givenFullName_whenDisplaying_thenUsesItAsIs", () => {
    // Arrange
    const user = userWith({ name: "Nora Varga", preferred_username: "nvarga" });
    // Act / Assert
    expect(displayName(user)).toBe("Nora Varga");
  });

  it("givenNoFullNameButGivenAndFamilyName_whenDisplaying_thenJoinsThem", () => {
    // Arrange
    const user = userWith({ given_name: "Nora", family_name: "Varga" });
    // Act / Assert
    expect(displayName(user)).toBe("Nora Varga");
  });

  it("givenNoNameClaimsAtAll_whenDisplaying_thenCapitalisesTheUsername", () => {
    // Arrange
    const user = userWith({ preferred_username: "nvarga" });
    // Act / Assert
    expect(displayName(user)).toBe("Nvarga");
  });

  it("givenNoUsername_whenDisplaying_thenFallsBackToEmailThenSub", () => {
    // Arrange
    const byEmail = userWith({ email: "nora@example.com" });
    const bySub = userWith({ sub: "abc-123" });
    // Act / Assert
    expect(displayName(byEmail)).toBe("Nora@example.com");
    expect(displayName(bySub)).toBe("Abc-123");
  });
});
