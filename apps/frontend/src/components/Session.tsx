"use client";

import { displayName } from "@aisolate/ui";
import type { User } from "oidc-client-ts";
import { createContext, useContext, type ReactNode } from "react";
import { rolesOf } from "@/lib/auth";
import { isAdmin, mayAudit } from "@/lib/roles";

/** `admin` creates spaces; what one may do inside a space is the space's `caller_role`. */
type Session = { name: string; email: string | null; admin: boolean; auditor: boolean };

const SessionContext = createContext<Session | null>(null);

function sessionOf(user: User): Session {
  const roles = rolesOf(user);
  return {
    name: displayName(user),
    email: user.profile.email ?? null,
    admin: isAdmin(roles),
    auditor: mayAudit(roles),
  };
}

export function SessionProvider({ user, children }: { user: User; children: ReactNode }) {
  return <SessionContext value={sessionOf(user)}>{children}</SessionContext>;
}

/** The signed-in user; only the UI shell provides one, so a page outside it fails loudly. */
export function useSession(): Session {
  const session = useContext(SessionContext);
  if (!session) throw new Error("useSession() called outside the UI shell");
  return session;
}
