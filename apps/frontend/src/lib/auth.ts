// Client-only module: oidc-client-ts touches window and crypto.subtle. Never import from a
// server component; construct the manager lazily inside effects or handlers.
import { Unauthorized, rolesFromClaims, userManagerSettings } from "@aisolate/ui";
import { UserManager, type User } from "oidc-client-ts";
import { env } from "./env";

let manager: UserManager | undefined;

export function userManager(): UserManager {
  // Tokens live in memory only; a reload goes back through the identity provider's SSO cookie.
  return (manager ??= new UserManager(
    userManagerSettings(window.location.origin, {
      authority: env.oidcAuthority,
      clientId: env.oidcClientId,
      scope: "openid profile email",
    }),
  ));
}

/** The current token; a missing or expired session is the same 401 AIsolate would answer. */
export async function getAccessToken(): Promise<string> {
  const user = await userManager().getUser();
  if (!user || user.expired) throw new Unauthorized();
  return user.access_token;
}

// A redirect to the identity provider that cannot start (it is down, or the authority is wrong)
// reports here, so the gate can say so instead of spinning on "Signing in…".
const unreachable = new EventTarget();

/** Calls `listener` whenever a redirect to the identity provider fails; returns the unsubscribe. */
export function onSignInUnreachable(listener: () => void): () => void {
  unreachable.addEventListener("unreachable", listener);
  return () => unreachable.removeEventListener("unreachable", listener);
}

const reported = (redirect: Promise<void>) =>
  redirect.catch(() => void unreachable.dispatchEvent(new Event("unreachable")));

/** Sends the browser to the identity provider and remembers the page to come back to. */
export const login = () =>
  reported(
    userManager().signinRedirect({
      state: { returnTo: window.location.pathname + window.location.search },
    }),
  );

/** Where a finished sign-in should land: the remembered page, or the overview. */
export function returnTo(state: unknown): string {
  const path = (state as { returnTo?: unknown } | undefined)?.returnTo;
  // "//host" and "/\host" are another site to the browser, not a path here.
  return typeof path === "string" && /^\/(?![/\\])/.test(path) && !path.startsWith("/callback")
    ? path
    : "/";
}
export const logout = () => reported(userManager().signoutRedirect());

/** Drop the stale session and start a fresh sign-in (AIsolate answered 401). */
export async function forgetAndLogin(): Promise<void> {
  await userManager().removeUser();
  await login();
}

/** Realm roles from the ID token, for display only. Authorization happens in AIsolate. */
export const rolesOf = (user: User) => rolesFromClaims(user.profile);
