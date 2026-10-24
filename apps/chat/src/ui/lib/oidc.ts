import {
  InMemoryWebStorage,
  WebStorageStateStore,
  type User,
  type UserManagerSettings,
} from "oidc-client-ts";

/** The full name the profile scope carries ("Nora Varga"); else the username, capitalised. */
export function displayName(user: User): string {
  const { name, given_name, family_name, preferred_username, email, sub } = user.profile;
  const full = name ?? [given_name, family_name].filter(Boolean).join(" ");
  const fallback = preferred_username ?? email ?? sub;
  return full || fallback.charAt(0).toUpperCase() + fallback.slice(1);
}

type Client = { authority: string; clientId: string; scope: string };

/** Authorization Code + PKCE, tokens in memory only, the redirect lands on /callback. */
export function userManagerSettings(origin: string, client: Client): UserManagerSettings {
  return {
    authority: client.authority,
    client_id: client.clientId,
    redirect_uri: `${origin}/callback`,
    post_logout_redirect_uri: origin,
    response_type: "code",
    scope: client.scope,
    userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
    automaticSilentRenew: true,
  };
}
