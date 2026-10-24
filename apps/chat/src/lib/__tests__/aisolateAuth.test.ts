import type { User } from "oidc-client-ts";
import { beforeEach, describe, expect, it, vi } from "vitest";

// The real UserManager needs a browser and an identity provider; this double records the event
// handlers the module registers so a test can fire them.
type Handler = (u?: User) => void;
const handlers: Record<string, Handler[]> = {};
const signinCallback = vi.fn(async () => undefined);
const signoutRedirect = vi.fn(async () => undefined);
const removeUser = vi.fn(async () => undefined);
const signinRedirect = vi.fn(async () => undefined);

vi.mock("oidc-client-ts", async (importOriginal) => ({
  ...(await importOriginal<typeof import("oidc-client-ts")>()),
  UserManager: class {
    events = {
      addUserLoaded: (h: Handler) => (handlers.loaded ??= []).push(h),
      addUserUnloaded: (h: Handler) => (handlers.unloaded ??= []).push(h),
      addAccessTokenExpired: (h: Handler) => (handlers.expired ??= []).push(h),
    };
    getUser = async () => null;
    signinCallback = signinCallback;
    signoutRedirect = signoutRedirect;
    signinRedirect = signinRedirect;
    removeUser = removeUser;
  },
}));

const AISOLATE = "http://aisolate.test";
const registration = {
  aisolateUrl: AISOLATE,
  issuer: "http://idp.test",
  clientId: "c1",
  scope: "openid",
};
const eric = { access_token: "t", profile: { sub: "eric" } } as unknown as User;

function memoryStorage(): Storage {
  const map = new Map<string, string>();
  return {
    get length() {
      return map.size;
    },
    clear: () => map.clear(),
    getItem: (k) => map.get(k) ?? null,
    key: (i) => [...map.keys()][i] ?? null,
    removeItem: (k) => void map.delete(k),
    setItem: (k, v) => void map.set(k, v),
  };
}

async function freshModule() {
  vi.resetModules();
  return import("../aisolateAuth");
}

describe("userStore", () => {
  beforeEach(() => {
    for (const k of Object.keys(handlers)) delete handlers[k];
    signinCallback.mockClear();
    const localStorage = memoryStorage();
    localStorage.setItem("aisolate.registrations", JSON.stringify([registration]));
    vi.stubGlobal("window", {
      localStorage,
      sessionStorage: memoryStorage(),
      location: { origin: "http://chat.test" },
    });
  });

  it("givenManagerCreated_whenUserLoadedFires_thenSnapshotHoldsUserForThatUrl", async () => {
    // Arrange
    const { userStore, accessToken } = await freshModule();
    const listener = vi.fn();
    userStore.subscribe(listener);
    await accessToken(AISOLATE).catch(() => undefined); // creates the manager
    // Act
    handlers.loaded.forEach((h) => h(eric));
    // Assert
    expect(userStore.getSnapshot()).toEqual({ aisolateUrl: AISOLATE, user: eric });
    expect(listener).toHaveBeenCalled();
  });

  it("givenUserLoaded_whenUserUnloadedFires_thenSnapshotIsNull", async () => {
    // Arrange
    const { userStore, accessToken } = await freshModule();
    await accessToken(AISOLATE).catch(() => undefined);
    handlers.loaded.forEach((h) => h(eric));
    // Act
    handlers.unloaded.forEach((h) => h());
    // Assert
    expect(userStore.getSnapshot()).toBeNull();
  });

  it("givenUserLoaded_whenAccessTokenExpiredFires_thenSnapshotIsNull", async () => {
    // Arrange
    const { userStore, accessToken } = await freshModule();
    await accessToken(AISOLATE).catch(() => undefined);
    handlers.loaded.forEach((h) => h(eric));
    // Act
    handlers.expired.forEach((h) => h());
    // Assert
    expect(userStore.getSnapshot()).toBeNull();
  });
});

describe("completeConnect", () => {
  beforeEach(() => {
    signinCallback.mockClear();
    const localStorage = memoryStorage();
    localStorage.setItem("aisolate.registrations", JSON.stringify([registration]));
    const sessionStorage = memoryStorage();
    sessionStorage.setItem("aisolate.connecting", AISOLATE);
    vi.stubGlobal("window", {
      localStorage,
      sessionStorage,
      location: { origin: "http://chat.test" },
    });
  });

  it("givenTwoConcurrentCalls_whenBothAwaited_thenCallbackRunsOnceAndBothResolveTheUrl", async () => {
    // Arrange
    const { completeConnect } = await freshModule();
    // Act: React StrictMode runs the callback effect twice in development
    const [first, second] = await Promise.all([completeConnect(), completeConnect()]);
    // Assert
    expect(first).toBe(AISOLATE);
    expect(second).toBe(AISOLATE);
    expect(signinCallback).toHaveBeenCalledTimes(1);
  });
});

describe("signOut", () => {
  beforeEach(() => {
    signoutRedirect.mockReset();
    removeUser.mockClear();
    const localStorage = memoryStorage();
    localStorage.setItem("aisolate.registrations", JSON.stringify([registration]));
    vi.stubGlobal("window", {
      localStorage,
      sessionStorage: memoryStorage(),
      location: { origin: "http://chat.test" },
    });
  });

  it("givenConnected_whenSignOut_thenEndsProviderSessionAndHoldsTheGate", async () => {
    // Arrange
    const { signOut, signingOut } = await freshModule();
    // Act
    await signOut(AISOLATE);
    // Assert
    expect(signoutRedirect).toHaveBeenCalledTimes(1);
    expect(signingOut()).toBe(true);
  });

  it.each([
    ["Reachable", () => undefined],
    ["Unreachable", () => signoutRedirect.mockRejectedValue(new Error("unreachable"))],
  ])(
    "givenElenasChatsAndLogout%s_whenSignOut_thenTheChatsAreGoneFromTheBrowser",
    async (_, logout) => {
      // Arrange
      logout();
      const { signOut } = await freshModule();
      const { chatsOwner, upsertChat } = await import("../chats");
      chatsOwner("elena");
      upsertChat({
        id: "salary",
        title: "",
        airlockId: "hr",
        messages: [],
        updatedAt: 1,
      });
      // Act
      await signOut(AISOLATE);
      // Assert
      expect(window.localStorage.getItem("aisolate.chats.elena")).toBeNull();
    },
  );

  it("givenLogoutUnreachable_whenSignOut_thenDropsSessionAndReleasesTheGate", async () => {
    // Arrange
    signoutRedirect.mockRejectedValue(new Error("unreachable"));
    const { signOut, signingOut } = await freshModule();
    // Act
    await signOut(AISOLATE);
    // Assert
    expect(removeUser).toHaveBeenCalledTimes(1);
    expect(signingOut()).toBe(false);
  });
});

describe("connect", () => {
  beforeEach(() => {
    signinRedirect.mockClear();
    vi.stubGlobal("window", {
      localStorage: memoryStorage(),
      sessionStorage: memoryStorage(),
      location: { origin: "http://chat.test" },
    });
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => Response.json({ clientId: "c1", issuer: "http://idp.test" })),
    );
  });

  it("givenTwoConcurrentCalls_whenBothAwaited_thenRegistersAndRedirectsOnce", async () => {
    // Arrange
    const { connect } = await freshModule();
    // Act: React StrictMode runs the gate's effect twice in development
    await Promise.all([connect(AISOLATE), connect(AISOLATE)]);
    // Assert
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(signinRedirect).toHaveBeenCalledTimes(1);
  });
});
