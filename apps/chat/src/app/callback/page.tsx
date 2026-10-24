"use client";

import { Banner, Muted } from "@/ui";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { completeConnect } from "@/lib/aisolateAuth";

/** The identity provider sends the user back here; finish the connection and return to the chat. */
export default function Callback() {
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        await completeConnect();
        if (!cancelled) router.replace("/");
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : "Sign-in failed");
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [router]);

  return (
    <main className="mx-auto max-w-md px-4 py-16">
      {error ? (
        <Banner
          message={error}
          actionLabel="Back to the chat"
          onAction={() => router.replace("/")}
        />
      ) : (
        <Muted>Completing sign-in…</Muted>
      )}
    </main>
  );
}
