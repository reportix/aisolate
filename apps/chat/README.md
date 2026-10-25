# AIsolate chat

An AIsolate chat client (how a client connects: [Connect a client](../../docs/api.md#connect-a-client)).
Connected with one button: the chat discovers the company identity provider from AIsolate (`/.well-known/oauth-protected-resource`), registers itself once (RFC 7591, forwarded
through `/api/aisolate/register` because browsers may not register anonymously), and sends the user to
the identity provider's login (PKCE). No username, password or API key is ever entered in the chat.

```bash
npm ci                    # from the repository root, installs the whole web workspace
npm run dev -w apps/chat  # http://localhost:3000
```

Environment (`apps/chat/.env.local`, inlined at build time): `NEXT_PUBLIC_AISOLATE_URL` (the AIsolate URL) and optionally
`NEXT_PUBLIC_AISOLATE_AIRLOCK_ID` (airlock to preselect). The composer's picker offers every airlock
AIsolate admits for the signed-in user; the sidebar lists the saved chats, newest first, and a chat stays
bound to the airlock it was started in. The last airlock, each user's own chats (dropped on sign-out) and the
registration live in the browser's localStorage; the registration keeps its RFC 7592 management
token, which can read or drop only this browser's client. Access tokens stay in memory.

`src/ui/` holds the chat's look and helpers: a neutral gray sidebar, answers on white, purple for what is
selected and for source markers, 12–16px radii and soft shadows, Geist from the `geist` package (design tokens in
`theme.css`, every text/background pair checked for WCAG AA by a test; Banner, Button, UserMenu, Muted, Avatar,
and the HTTP and OIDC helpers).
Gates, from the root: `npm run lint && npm run format && npm test && npm run build`. The image builds from the
repository root: `docker build -f apps/chat/Dockerfile --build-arg NEXT_PUBLIC_AISOLATE_URL=... .`
