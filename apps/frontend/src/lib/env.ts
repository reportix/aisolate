import { required } from "@aisolate/ui";

// Literal process.env.NEXT_PUBLIC_* member access is required: Next inlines these at build time.
export const env = {
  get aisolateUrl() {
    return required("NEXT_PUBLIC_AISOLATE_URL", process.env.NEXT_PUBLIC_AISOLATE_URL);
  },
  get oidcAuthority() {
    return required("NEXT_PUBLIC_OIDC_AUTHORITY", process.env.NEXT_PUBLIC_OIDC_AUTHORITY);
  },
  /** Optional: a trace viewer's address with `{trace_id}` in it, for an audit event's View trace. */
  get traceUrl() {
    return process.env.NEXT_PUBLIC_TRACE_URL || undefined;
  },
  /** The running version, for the footer; the build injects it (AGPL section 13). */
  get version() {
    return process.env.NEXT_PUBLIC_AISOLATE_VERSION || "dev";
  },
  /** Where the source of this version lives (AGPL section 13); a fork's build points it at the fork. */
  get sourceUrl() {
    return (
      process.env.NEXT_PUBLIC_AISOLATE_SOURCE_URL || "https://gitlab.reportix.com/aisolate/aisolate"
    );
  },
  get oidcClientId() {
    return required("NEXT_PUBLIC_OIDC_CLIENT_ID", process.env.NEXT_PUBLIC_OIDC_CLIENT_ID);
  },
};
