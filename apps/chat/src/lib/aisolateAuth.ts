// Generic OAuth 2.1 client for AIsolate (RFC 9728 + RFC 8414 + RFC 7591), the standard flow for a
// client of a protected resource. Nothing here knows about Keycloak: AIsolate's discovery document names the
// identity provider, the provider's own metadata names its endpoints. Registration is forwarded by
// the same-origin route /api/aisolate/register (browsers cannot register anonymously themselves).
import { trimSlash, userManagerSettings } from "@/ui";
import { UserManager, type User } from "oidc-client-ts";
import { useSyncExternalStore } from "react";
import { clearChats } from "./chats";

/**
 * What a client must remember per AIsolate URL. The management URI and token (RFC 7592) only let
 * this chat read or drop its own registration.
 */
type Registration = {
  aisolateUrl: string;
  issuer: string;
  clientId: string;
  scope: string;
  registrationClientUri?: string;
  registrationAccessToken?: string;
};

const REGISTRATIONS_KEY = "aisolate.registrations";
const CONNECTING_KEY = "aisolate.connecting";
let manager: UserManager | undefined;
let managerFor: string | undefined;

// One connection for every component. Fed by the UserManager's events, so a sign-in finishing, a
// silent renew, a sign-out or an expiry reaches the page and the dialog alike; polling getUser()
// from separate effects left them disagreeing.
type Connection = { aisolateUrl: string; user: User };
let connection: Connection | null = null;
const listeners = new Set<() => void>();

function setConnection(next: Connection | null) {
  connection = next;
  listeners.forEach((l) => l());
}

export const userStore = {
  subscribe(listener: () => void) {
    listeners.add(listener);
    return () => void listeners.delete(listener);
  },
  getSnapshot: () => connection,
  getServerSnapshot: () => null,
};

/** The user connected to this AIsolate URL, live. */
export function useAIsolateUser(aisolateUrl: string): User | null {
  const c = useSyncExternalStore(
    userStore.subscribe,
    userStore.getSnapshot,
    userStore.getServerSnapshot,
  );
  return c && c.aisolateUrl === trimSlash(aisolateUrl) ? c.user : null;
}

/**
 * Registers with the identity provider, or confirms the registration this browser already holds.
 * The provider can forget a client at any time (a development Keycloak does on every `down -v`), and
 * a forgotten client id lands the user on an error page the chat never gets control back from.
 */
async function ensureRegistration(aisolateUrl: string): Promise<Registration> {
  const url = trimSlash(aisolateUrl);
  const existing = registrationFor(url);
  const res = await fetch("/api/aisolate/register", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      redirectUri: `${window.location.origin}/callback`,
      existing: existing && {
        registrationClientUri: existing.registrationClientUri,
        registrationAccessToken: existing.registrationAccessToken,
      },
    }),
  });
  const body = (await res.json()) as Partial<Omit<Registration, "aisolateUrl">> & {
    error?: string;
  };
  if (!res.ok || !body.clientId || !body.issuer)
    throw new Error(body.error ?? "Could not register with the identity provider");
  const registration: Registration = {
    aisolateUrl: url,
    issuer: body.issuer,
    clientId: body.clientId,
    scope: body.scope ?? "openid profile email aisolate",
    registrationClientUri: body.registrationClientUri,
    registrationAccessToken: body.registrationAccessToken,
  };
  saveRegistrations([...loadRegistrations().filter((r) => r.aisolateUrl !== url), registration]);
  return registration;
}

function userManager(reg: Registration): UserManager {
  if (manager && managerFor === reg.clientId) return manager;
  manager = new UserManager(
    userManagerSettings(window.location.origin, {
      authority: reg.issuer,
      clientId: reg.clientId,
      scope: reg.scope,
    }),
  );
  managerFor = reg.clientId;
  setConnection(null); // a new manager starts without a user
  manager.events.addUserLoaded((user) => setConnection({ aisolateUrl: reg.aisolateUrl, user }));
  manager.events.addUserUnloaded(() => setConnection(null));
  manager.events.addAccessTokenExpired(() => setConnection(null));
  return manager;
}

/**
 * Drops the stored registration by hand. Signing in already confirms and renews it; this remains
 * the way out when that check itself misleads.
 */
export function forgetRegistration(aisolateUrl: string): void {
  const url = trimSlash(aisolateUrl);
  saveRegistrations(loadRegistrations().filter((r) => r.aisolateUrl !== url));
  manager = undefined;
  managerFor = undefined;
  setConnection(null);
}

let redirecting: Promise<void> | undefined;

/**
 * Starts the redirect to the identity provider. `aisolateUrl` is remembered for the callback.
 * One at a time: React StrictMode runs the gate's effect twice, and a second registration racing
 * the first would leave the callback with a client its state was not made for.
 */
export function connect(aisolateUrl: string): Promise<void> {
  return (redirecting ??= (async () => {
    const reg = await ensureRegistration(aisolateUrl);
    window.sessionStorage.setItem(CONNECTING_KEY, reg.aisolateUrl);
    await userManager(reg).signinRedirect();
  })().catch((e: unknown) => {
    redirecting = undefined; // a failure may be retried
    throw e;
  }));
}

let completing: Promise<string | null> | undefined;

/**
 * Completes the redirect on /callback; returns the AIsolate URL that was being connected. Memoized:
 * React StrictMode runs the callback effect twice, and the second run must wait for the same code
 * exchange instead of navigating away before it finishes.
 */
export function completeConnect(): Promise<string | null> {
  return (completing ??= (async () => {
    const url = window.sessionStorage.getItem(CONNECTING_KEY);
    window.sessionStorage.removeItem(CONNECTING_KEY);
    const reg = url ? registrationFor(url) : undefined;
    if (!reg) return null;
    await userManager(reg).signinCallback();
    return url;
  })());
}

async function currentUser(aisolateUrl: string): Promise<User | null> {
  const reg = registrationFor(aisolateUrl);
  if (!reg) return null;
  const user = await userManager(reg).getUser();
  return user && !user.expired ? user : null;
}

/**
 * A token for one turn. Tokens live five minutes and a turn can run longer, so one close to expiry
 * is renewed first: the tool server checks it again on every call, mid-turn included.
 */
export async function accessToken(aisolateUrl: string): Promise<string> {
  const user = await currentUser(aisolateUrl);
  if (!user) throw new NotConnected();
  if (!expiringSoon(user)) return user.access_token;
  const reg = registrationFor(aisolateUrl);
  try {
    const renewed = reg ? await userManager(reg).signinSilent() : null;
    if (renewed) return renewed.access_token;
  } catch {
    // The identity provider would not renew silently; the old token still has a moment left.
  }
  return user.access_token;
}

/** Half the usual five-minute lifetime, so a turn starts with as much of it as possible. */
const RENEW_WITHIN_SECONDS = 150;

function expiringSoon(user: User): boolean {
  return user.expires_in !== undefined && user.expires_in < RENEW_WITHIN_SECONDS;
}

let leaving = false;

/**
 * Whether the page is on its way to the provider's logout. The user is dropped before that redirect
 * leaves, and a sign-in started in between would race it and bring the old session back.
 */
export const signingOut = () => leaving;

/**
 * Ends the identity provider's session too, then lands back on the chat, which redirects to the
 * provider's login page. Dropping only this tab's token would leave that session signing the user
 * straight back in. Every sign-out, forced or chosen, drops the user's chats from the browser.
 */
export async function signOut(aisolateUrl: string): Promise<void> {
  clearChats();
  const reg = registrationFor(aisolateUrl);
  if (!reg) return;
  leaving = true;
  try {
    await userManager(reg).signoutRedirect();
  } catch {
    // The provider is unreachable: drop the session here; the gate then reports it can't sign in.
    leaving = false;
    await userManager(reg).removeUser();
  }
}

export class NotConnected extends Error {
  constructor() {
    super("Not connected to AIsolate");
  }
}

function registrationFor(aisolateUrl: string): Registration | undefined {
  const url = trimSlash(aisolateUrl);
  return loadRegistrations().find((r) => r.aisolateUrl === url);
}

function loadRegistrations(): Registration[] {
  try {
    const raw = window.localStorage.getItem(REGISTRATIONS_KEY);
    return raw ? (JSON.parse(raw) as Registration[]) : [];
  } catch {
    return [];
  }
}

function saveRegistrations(regs: Registration[]) {
  try {
    window.localStorage.setItem(REGISTRATIONS_KEY, JSON.stringify(regs));
  } catch {
    // storage blocked: the registration lives for this page only
  }
}
