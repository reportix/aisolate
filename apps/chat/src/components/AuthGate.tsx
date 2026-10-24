"use client";

import { Banner } from "@/ui";
import type { User } from "oidc-client-ts";
import { type ReactNode, useEffect, useState } from "react";
import { connect, signingOut } from "@/lib/aisolateAuth";

type Props = {
  user: User | null;
  aisolateUrl: string;
  children: (user: User) => ReactNode;
};

/**
 * Renders children only for a signed-in user; otherwise sends the browser straight to the identity
 * provider's login page, the way the AIsolate UI does. No redirect loop can form: every way out of the chat's session (sign-out, a refused token)
 * ends the provider's session too, so the redirect always lands on a login form.
 */
export function AuthGate({ user, aisolateUrl, children }: Props) {
  const [unreachable, setUnreachable] = useState(false);

  useEffect(() => {
    if (user || signingOut()) return;
    void connect(aisolateUrl).catch(() => setUnreachable(true));
  }, [aisolateUrl, user]);

  if (user) return <>{children(user)}</>;

  // AIsolate's discovery, the registration or the identity provider: any of them may be what failed.
  if (unreachable) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-desk p-4">
        <div className="w-full max-w-md">
          <Banner
            message="Can't reach the sign-in service"
            actionLabel="Try again"
            onAction={() => window.location.reload()}
          />
        </div>
      </div>
    );
  }
  return (
    <div className="flex min-h-screen items-center justify-center bg-desk px-4 text-center">
      <p className="text-sm text-ink-secondary">Signing in…</p>
    </div>
  );
}
