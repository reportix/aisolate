import { env } from "@/lib/env";
import { trimSlash } from "@/ui";
import { NextResponse } from "next/server";

// Browsers cannot register anonymously at the identity provider (Keycloak rejects requests with an
// Origin header), so this same-origin route forwards the standard RFC 7591 registration. Nothing the
// caller sends picks a host: the chain starts at the image's own AIsolate URL, and the client it
// registers redirects to this chat's /callback only. "This chat" is the Host the ingress forwards: a
// caller reaching the pod directly can forge it, so the identity provider's own check that a client's
// URIs match is what holds there.
//
// A registration the browser already holds is confirmed first (RFC 7592): the provider can forget a
// client at any time (a development Keycloak does on every `down -v`), and a forgotten client id
// lands the user on an error page the chat never gets control back from.
type Existing = { registrationClientUri: string; registrationAccessToken: string };
type Client = {
  client_id: string;
  post_logout_redirect_uris?: string[];
  registration_client_uri?: string;
  registration_access_token?: string;
};

export async function POST(req: Request) {
  const { redirectUri, existing } = (await req.json().catch(() => ({}))) as {
    redirectUri?: string;
    existing?: Partial<Existing>;
  };
  const origin = ownOrigin(req);
  if (redirectUri !== `${origin}/callback`) {
    return NextResponse.json(
      { error: "redirectUri must be this chat's /callback" },
      { status: 400 },
    );
  }
  if (!env.aisolateUrl) {
    return NextResponse.json({ error: "This chat has no AIsolate URL" }, { status: 500 });
  }
  // Upstream text is never echoed: a caller learns nothing about what answered, or failed to.
  try {
    const provider = await discover(trimSlash(env.aisolateUrl));
    if ("error" in provider) return NextResponse.json({ error: provider.error }, { status: 502 });

    // Signing out returns to the chat's origin; the provider refuses any URI the client did not register.
    const kept = await stillRegistered(provider.registrationEndpoint, existing, origin);
    const client = kept ?? (await register(provider.registrationEndpoint, redirectUri, origin));
    if (!client) return NextResponse.json({ error: REFUSED }, { status: 502 });
    return NextResponse.json({
      clientId: client.client_id,
      issuer: provider.issuer,
      scope: ["openid", "profile", "email", ...provider.scopes].join(" "),
      registrationClientUri: client.registration_client_uri,
      registrationAccessToken: client.registration_access_token,
    });
  } catch {
    return NextResponse.json({ error: REFUSED }, { status: 502 });
  }
}

const REFUSED = "The identity provider did not register this chat";

/** The origin the browser reached: behind a proxy, req.url names the server's bind address instead. */
function ownOrigin(req: Request): string {
  const url = new URL(req.url);
  const proto =
    req.headers.get("x-forwarded-proto")?.split(",")[0].trim() || url.protocol.slice(0, -1);
  return `${proto}://${req.headers.get("host") ?? url.host}`;
}

/** RFC 9728 → RFC 8414: from the AIsolate URL to its identity provider's registration endpoint. */
async function discover(base: string) {
  const probe = await fetch(`${base}/v1/airlocks`, {
    headers: { Accept: "application/json" },
  });
  const challenge = probe.headers.get("www-authenticate") ?? "";
  const metadataUrl =
    challenge.match(/resource_metadata="([^"]+)"/)?.[1] ??
    `${base}/.well-known/oauth-protected-resource`;
  if (new URL(metadataUrl).origin !== new URL(base).origin) {
    return { error: "AIsolate names metadata on another host" };
  }
  const resource = (await (await fetch(metadataUrl)).json()) as {
    authorization_servers?: string[];
    scopes_supported?: string[];
  };
  const issuer = resource.authorization_servers?.[0];
  if (!issuer) return { error: "AIsolate names no identity provider" };
  const server = (await (
    await fetch(`${trimSlash(issuer)}/.well-known/openid-configuration`)
  ).json()) as { registration_endpoint?: string };
  if (!server.registration_endpoint) {
    return { error: "The identity provider offers no dynamic client registration" };
  }
  return {
    issuer,
    registrationEndpoint: trimSlash(server.registration_endpoint),
    scopes: resource.scopes_supported ?? [],
  };
}

/**
 * The client as the provider still knows it; undefined once the provider has forgotten it, or when
 * it predates the post-logout redirect (registered before sign-out ended the provider's session).
 */
async function stillRegistered(
  registrationEndpoint: string,
  existing: Partial<Existing> | undefined,
  postLogoutUri: string,
): Promise<Client | undefined> {
  const raw = existing?.registrationClientUri;
  const token = existing?.registrationAccessToken;
  // The management URI comes from the browser: only one below the discovered endpoint is followed,
  // compared as fetch will resolve it, so `..` cannot climb out.
  const uri = raw && URL.canParse(raw) ? new URL(raw).href : undefined;
  if (!uri || !token || !uri.startsWith(`${registrationEndpoint}/`)) return undefined;
  const res = await fetch(uri, { headers: { Authorization: `Bearer ${token}` } });
  if (!res.ok) return undefined;
  const body = (await res.json()) as Client;
  if (!body.post_logout_redirect_uris?.includes(postLogoutUri)) return undefined;
  // Some providers rotate the management token on every request; keep whichever is current.
  return {
    ...body,
    registration_client_uri: body.registration_client_uri ?? uri,
    registration_access_token: body.registration_access_token ?? token,
  };
}

async function register(
  registrationEndpoint: string,
  redirectUri: string,
  postLogoutUri: string,
): Promise<Client | undefined> {
  const res = await fetch(registrationEndpoint, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      client_name: `AIsolate chat (${new URL(redirectUri).host})`,
      redirect_uris: [redirectUri],
      post_logout_redirect_uris: [postLogoutUri],
      grant_types: ["authorization_code", "refresh_token"],
      response_types: ["code"],
      token_endpoint_auth_method: "none",
    }),
  });
  const body = (await res.json()) as Partial<Client>;
  return res.ok && body.client_id ? (body as Client) : undefined;
}
