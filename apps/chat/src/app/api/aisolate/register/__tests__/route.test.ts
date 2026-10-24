import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { POST } from "../route";

const AISOLATE = "http://aisolate.test";
const ISSUER = "http://idp.test/realms/r";
const REGISTRATION = `${ISSUER}/clients-registrations/openid-connect`;

/**
 * AIsolate's discovery chain and an identity provider that still knows `known` (or nothing), with
 * `postLogout` as the post-logout redirect URIs it holds for that client. `metadata` is where
 * AIsolate's challenge points, `registered` how the provider answers a registration.
 */
function stubProvider(
  known: string | null,
  postLogout = ["http://chat.test"],
  {
    metadata = `${AISOLATE}/.well-known/oauth-protected-resource`,
    registered = () =>
      Response.json(
        {
          client_id: "fresh",
          registration_client_uri: `${REGISTRATION}/fresh`,
          registration_access_token: "token-fresh",
        },
        { status: 201 },
      ),
  }: { metadata?: string; registered?: () => Response } = {},
) {
  const register = vi.fn<(init?: RequestInit) => Promise<Response>>(async () => registered());
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === `${AISOLATE}/v1/airlocks`) {
        return new Response(null, {
          status: 401,
          headers: { "www-authenticate": `Bearer resource_metadata="${metadata}"` },
        });
      }
      if (url === `${AISOLATE}/.well-known/oauth-protected-resource`) {
        return Response.json({ authorization_servers: [ISSUER], scopes_supported: ["aisolate"] });
      }
      if (url === `${ISSUER}/.well-known/openid-configuration`) {
        return Response.json({ registration_endpoint: REGISTRATION });
      }
      if (url === REGISTRATION && init?.method === "POST") return register(init);
      // Any host answers for the known client, so a management URI that is followed shows up.
      if (known && url.endsWith(`/${known}`)) {
        return Response.json({ client_id: known, post_logout_redirect_uris: postLogout });
      }
      return Response.json({ error: "invalid_token" }, { status: 401 });
    }),
  );
  return register;
}

function post(
  existing: { registrationClientUri: string; registrationAccessToken: string },
  body: Record<string, unknown> = {},
) {
  return POST(
    new Request("http://chat.test/api/aisolate/register", {
      method: "POST",
      body: JSON.stringify({ redirectUri: "http://chat.test/callback", existing, ...body }),
    }),
  );
}

const NONE = { registrationClientUri: "", registrationAccessToken: "" };

/** Every URL the route fetched. */
const fetched = () => vi.mocked(fetch).mock.calls.map(([input]) => String(input));

describe("POST /api/aisolate/register", () => {
  beforeEach(() => vi.stubEnv("NEXT_PUBLIC_AISOLATE_URL", AISOLATE));
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
  });

  it.each(
    [
      {
        given: "ProviderStillKnowsStoredClient",
        known: "c1",
        uri: `${REGISTRATION}/c1`,
        clientId: "c1",
        token: "token-c1",
        registrations: 0,
      },
      {
        given: "ProviderForgotStoredClient",
        known: null,
        uri: `${REGISTRATION}/c1`,
        clientId: "fresh",
        token: "token-fresh",
        registrations: 1,
      },
      {
        given: "StoredClientWithoutPostLogoutUri",
        known: "c1",
        postLogout: ["http://chat.test/callback"],
        uri: `${REGISTRATION}/c1`,
        clientId: "fresh",
        token: "token-fresh",
        registrations: 1,
      },
      {
        given: "ManagementUriClimbingOutOfTheEndpoint",
        known: "c1",
        uri: `${REGISTRATION}/../../../admin/c1`,
        clientId: "fresh",
        token: "token-fresh",
        registrations: 1,
      },
      {
        given: "ManagementUriOutsideProvider",
        known: "c1",
        uri: "http://evil.test/c1",
        clientId: "fresh",
        token: "token-fresh",
        registrations: 1,
      },
    ].map((c): [string, string, typeof c] => [c.given, c.clientId, c]),
  )(
    "given%s_whenPost_thenClientIs %s",
    async (_, __, { known, postLogout, uri, clientId, token, registrations }) => {
      // Arrange
      const register = stubProvider(known, postLogout);
      // Act
      const body = await (
        await post({ registrationClientUri: uri, registrationAccessToken: "token-c1" })
      ).json();
      // Assert
      expect(body).toMatchObject({ clientId, issuer: ISSUER, registrationAccessToken: token });
      expect(register).toHaveBeenCalledTimes(registrations);
    },
  );

  it("givenNoStoredClient_whenPost_thenRegistersChatOriginAsPostLogoutRedirect", async () => {
    // Arrange
    const register = stubProvider(null);
    // Act
    await post(NONE);
    // Assert
    const sent = JSON.parse(String(register.mock.calls[0][0]?.body));
    expect(sent.post_logout_redirect_uris).toEqual(["http://chat.test"]);
  });

  it("givenAisolateUrlInTheBody_whenPost_thenOnlyTheImagesOwnIsFetched", async () => {
    // Arrange
    stubProvider(null);
    // Act
    const res = await post(NONE, { aisolateUrl: "http://10.0.0.5:8080/admin?" });
    // Assert
    expect(res.status).toBe(200);
    expect(fetched().some((url) => url.includes("10.0.0.5"))).toBe(false);
  });

  it.each([
    ["AnotherOrigin", "https://evil.test/callback"],
    ["AnotherPath", "http://chat.test/elsewhere"],
    ["None", undefined],
  ])("givenRedirectUriOn%s_whenPost_thenRefusedBeforeAnyFetch", async (_, redirectUri) => {
    // Arrange
    stubProvider(null);
    // Act
    const res = await post(NONE, { redirectUri });
    // Assert
    expect(res.status).toBe(400);
    expect(fetched()).toEqual([]);
  });

  it("givenMetadataOnAnotherHost_whenPost_thenRefusedWithoutFollowingIt", async () => {
    // Arrange
    stubProvider(null, undefined, { metadata: "http://10.0.0.5/metadata" });
    // Act
    const res = await post(NONE);
    // Assert
    expect(res.status).toBe(502);
    expect(fetched()).not.toContain("http://10.0.0.5/metadata");
  });

  it.each([
    [
      "RefusesWithItsOwnText",
      () => Response.json({ error_description: "internal detail" }, { status: 403 }),
    ],
    [
      "IsUnreachable",
      () => {
        throw new TypeError("fetch failed: internal detail");
      },
    ],
  ])("givenProvider%s_whenPost_thenGeneric502", async (_, registered) => {
    // Arrange
    stubProvider(null, undefined, { registered });
    // Act
    const res = await post(NONE);
    // Assert
    expect(res.status).toBe(502);
    expect(JSON.stringify(await res.json())).not.toContain("internal detail");
  });
});
