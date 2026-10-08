"use client";

import { Banner, Muted } from "@aisolate/ui";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { login, onSignInUnreachable, returnTo, userManager } from "@/lib/auth";

export default function CallbackPage() {
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);

  const started = useRef(false);

  useEffect(() => {
    // The code is single-use: React's development double effect must not exchange it twice.
    if (started.current) return;
    started.current = true;
    userManager()
      .signinCallback()
      .then((user) => router.replace(returnTo(user?.state)))
      // The library's own words ("No matching state found in storage") mean nothing to the user.
      .catch(() => setError("Sign-in did not complete."));
  }, [router]);

  // AuthGate is not mounted here, so a "Sign in again" that cannot leave would otherwise do nothing.
  useEffect(() => onSignInUnreachable(() => setError("Can't reach the sign-in service")), []);

  if (error) {
    return (
      <main className="p-6">
        <Banner message={error} actionLabel="Sign in again" onAction={() => void login()} />
      </main>
    );
  }
  return <Muted className="p-6">Completing sign-in…</Muted>;
}
