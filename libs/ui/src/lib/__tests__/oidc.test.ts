import { afterEach, describe, expect, it, vi } from "vitest";
import type { User } from "oidc-client-ts";
import { displayName, rolesFromClaims, userManagerSettings } from "../oidc";

describe("rolesFromClaims", () => {
  it("givenRealmRoles_whenRolesFromClaims_thenKeepsThemAndDropsKeycloaksBuiltInOnes", () => {
    // Arrange
    const claims = {
      realm_access: { roles: ["ceo", "default-roles-acme", "offline_access", 7] },
    };
    // Act / Assert
    expect(rolesFromClaims(claims)).toEqual(["ceo"]);
    expect(rolesFromClaims({})).toEqual([]);
  });
});

describe("displayName", () => {
  it.each([
    ["NameClaim", { name: "Nora Varga", preferred_username: "nora" }, "Nora Varga"],
    ["GivenAndFamilyNameOnly", { given_name: "Nora", family_name: "Varga" }, "Nora Varga"],
    ["UsernameOnly", { preferred_username: "nora" }, "Nora"],
  ])("given%s_whenDisplayed_thenTheName", (_, profile, expected) => {
    // Arrange
    const user = { profile: { sub: "42", ...profile } } as unknown as User;
    // Act / Assert
    expect(displayName(user)).toBe(expected);
  });
});

describe("userManagerSettings", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("givenTheUi_whenSettings_thenCodeFlowWithPkceAndTokensInMemoryOnly", async () => {
    // Arrange: browser storage that would keep a token past the tab
    const storage = { getItem: vi.fn(), setItem: vi.fn(), removeItem: vi.fn(), key: vi.fn() };
    vi.stubGlobal("localStorage", storage);
    vi.stubGlobal("sessionStorage", storage);

    // Act
    const settings = userManagerSettings("https://ui.example", {
      authority: "https://idp.example/realms/acme",
      clientId: "aisolate-ui",
      scope: "openid",
    });
    await settings.userStore!.set("user", "token");

    // Assert
    expect(settings).toMatchObject({
      response_type: "code",
      redirect_uri: "https://ui.example/callback",
    });
    expect(settings.disablePKCE).toBeFalsy();
    expect(settings).not.toHaveProperty("client_secret");
    expect(await settings.userStore!.get("user")).toBe("token");
    expect(storage.setItem).not.toHaveBeenCalled();
  });
});
