# AIsolate UI

Where the admin defines a **space** (its users with their role, its models and its data sources) and its developers its **airlocks** (the
users who may ask them, and their own models and data sources over the space's), and read each
airlock's code, which `aisolate deploy` brings, as a graph, in details and as tools. A value an
airlock does not set reads "from the space" or "from the platform". What each page holds and what a save does:
[Define airlocks in the AIsolate UI](../../docs/develop.md#define-airlocks-in-the-aisolate-ui).

```bash
npm ci                        # from the repository root, installs the whole web workspace
npm run dev -w apps/frontend  # http://localhost:3001
```

Environment (`.env.local`): `NEXT_PUBLIC_AISOLATE_URL`, `NEXT_PUBLIC_OIDC_AUTHORITY`,
`NEXT_PUBLIC_OIDC_CLIENT_ID=aisolate-ui` ([Configuration](../../docs/configuration.md#clients)); the footer's version and Source link (AGPL-3.0 section 13) come from `AISOLATE_VERSION` and `NEXT_PUBLIC_AISOLATE_SOURCE_URL`. Sign-in is
the organisation's identity provider; the realm role `aisolate-admin` may do everything, and each space names its
developers (its airlocks) and users (who only ask), which the space's `caller_role` tells the pages.
Demo users: `jasmine` (a developer once an admin adds her to a space), `andrew` and `nora` (admins),
password `password`.

Shared look and helpers come from `../../libs/ui` (`@aisolate/ui`); the design system is `DESIGN.md`, and
so are the words the UI keeps to: Users (never Members), Models (never LLMs), Data sources, Overview,
Details, Tools. Gates, from the repository root: `npm run lint && npm run format && npm test && npm run build`.
