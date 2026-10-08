"use client";

import { Banner, Muted } from "@aisolate/ui";
import type { User } from "oidc-client-ts";
import { useEffect, useState } from "react";
import { login, onSignInUnreachable, userManager } from "@/lib/auth";

/** Renders children only for a signed-in user; otherwise sends the browser to the identity provider. */
export function AuthGate({ children }: { children: (user: User) => React.ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [unreachable, setUnreachable] = useState(false);

  useEffect(() => {
    const um = userManager();
    let cancelled = false;
    void um.getUser().then((u) => {
      if (cancelled) return;
      if (u && !u.expired) setUser(u);
      else void login();
    });
    const toLogin = () => void login();
    const onLoaded = (u: User) => setUser(u);
    um.events.addUserLoaded(onLoaded);
    um.events.addSilentRenewError(toLogin);
    const stopReporting = onSignInUnreachable(() => setUnreachable(true));
    return () => {
      cancelled = true;
      stopReporting();
      um.events.removeUserLoaded(onLoaded);
      um.events.removeSilentRenewError(toLogin);
    };
  }, []);

  // Sign-in, renewal and sign-out alike: nothing works without the identity provider.
  if (unreachable) {
    return (
      <div className="flex min-h-screen items-center justify-center p-4">
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
  if (!user) {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <Muted>Signing in…</Muted>
      </div>
    );
  }
  return <>{children(user)}</>;
}
