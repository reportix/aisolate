import type { Space } from "./types";

const ADMIN = "aisolate-admin";
/** Reads the audit and nothing else. */
export const AUDITOR = "aisolate-auditor";

/** The platform admin: creates spaces and names their developers and roles. */
export const isAdmin = (roles: readonly string[]) => roles.includes(ADMIN);

/** Who reads every space's audit; a developer reads the audit of the spaces they see as well. */
export const mayAudit = (roles: readonly string[]) => isAdmin(roles) || roles.includes(AUDITOR);

/**
 * What the backend lets the caller change in this space: its own developers, roles, LLM and data sources are
 * the admin's; its airlocks and their parts its developers' too.
 */
export const mayChange = (space: Space | null, inAirlock: boolean) =>
  space?.caller_role === "admin" || (inAirlock && space?.caller_role === "developer");
