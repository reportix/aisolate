import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { login, logout, onSignInUnreachable, reauthenticate, returnTo } from "../auth";

const signin = vi.hoisted(() => vi.fn());

// An identity provider that cannot be reached: its metadata never loads, so no redirect starts.
vi.mock("oidc-client-ts", async (original) => ({
  ...(await original<typeof import("oidc-client-ts")>()),
  UserManager: class {
    signinRedirect = signin;
    signoutRedirect = () => Promise.reject(new Error("metadata unreachable"));
  },
}));

signin.mockImplementation(() => Promise.reject(new Error("metadata unreachable")));

describe("returnTo", () => {
  it.each([
    [{ returnTo: "/spaces/hr-acme?tab=servers" }, "/spaces/hr-acme?tab=servers"],
    [{ returnTo: "/callback" }, "/"],
    [{ returnTo: "https://evil.example/" }, "/"],
    [{ returnTo: "//evil.example/" }, "/"],
    [{ returnTo: "/\\evil.example/" }, "/"],
    [undefined, "/"],
  ])("givenSignInState_%j_whenReturnTo_thenSafePathOrOverview", (state, expected) => {
    // Arrange / Act
    const path = returnTo(state);

    // Assert
    expect(path).toBe(expected);
  });
});

describe("onSignInUnreachable", () => {
  beforeEach(() => {
    vi.stubGlobal("window", { location: { origin: "http://ui.test", pathname: "/", search: "" } });
    vi.stubEnv("NEXT_PUBLIC_OIDC_AUTHORITY", "http://idp.test/realms/aisolate");
    vi.stubEnv("NEXT_PUBLIC_OIDC_CLIENT_ID", "aisolate-ui");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
  });

  it.each([
    ["Login", login],
    ["Logout", logout],
  ])("givenTheIdentityProviderUnreachable_when%s_thenTheGateIsTold", async (_, redirect) => {
    // Arrange
    const told = vi.fn();
    const stop = onSignInUnreachable(told);

    // Act
    await redirect();

    // Assert
    expect(told).toHaveBeenCalledOnce();
    stop();
  });
});

describe("reauthenticate", () => {
  beforeEach(() => {
    vi.stubGlobal("window", {
      location: { origin: "http://ui.test", pathname: "/llms/gpt", search: "?x=1" },
    });
    vi.stubEnv("NEXT_PUBLIC_OIDC_AUTHORITY", "http://idp.test/realms/aisolate");
    vi.stubEnv("NEXT_PUBLIC_OIDC_CLIENT_ID", "aisolate-ui");
    signin.mockClear();
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
  });

  it("givenAStaleLogin_whenReauthenticate_thenSigninRedirectCarriesPromptLoginAndMaxAge300", async () => {
    // Arrange / Act
    await reauthenticate();

    // Assert
    expect(signin).toHaveBeenCalledWith({
      prompt: "login",
      max_age: 300,
      state: { returnTo: "/llms/gpt?x=1" },
    });
  });
});
