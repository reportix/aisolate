export class ApiError extends Error {
  readonly status: number;
  /** Field errors of an invalid form ("field: message"), otherwise empty. */
  readonly errors: string[];
  /** Machine-readable cause a problem carries beside its text, e.g. "stale_login". */
  readonly reason?: string;
  constructor(status: number, message: string, errors: string[] = [], reason?: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.errors = errors;
    this.reason = reason;
  }
}

export class Unauthorized extends ApiError {
  constructor() {
    super(401, "Sign in again");
    this.name = "Unauthorized";
  }
}

/** Nothing answered, or a gateway answered for a backend that did not: the one retryable failure. */
export class Unreachable extends ApiError {
  readonly kind: "offline" | "server";
  constructor(kind: "offline" | "server") {
    super(0, kind === "offline" ? "You are offline" : "AIsolate is not answering");
    this.name = "Unreachable";
    this.kind = kind;
  }
}

export function authHeaders(token: string, accept = "application/json"): Record<string, string> {
  return { Authorization: `Bearer ${token}`, Accept: accept };
}

/**
 * Passes a 2xx response through; a 401 becomes Unauthorized, a dead gateway Unreachable, anything
 * else the problem it carries. A 502 with a problem body is the backend's own (its directory did
 * not answer): the backend is up, so it is that problem, not Unreachable.
 */
export async function expectOk(res: Response): Promise<Response> {
  if (res.status === 401) throw new Unauthorized();
  const own = res.headers.get("Content-Type")?.startsWith("application/problem+json");
  if (res.status >= 502 && res.status <= 504 && !own) throw new Unreachable("server");
  if (!res.ok) throw await problem(res);
  return res;
}

/** RFC 9457 problem+json: prefer detail, then title, then the status line. */
export async function problem(res: Response): Promise<ApiError> {
  try {
    const body = (await res.json()) as {
      detail?: string;
      title?: string;
      errors?: unknown;
      reason?: unknown;
    };
    const errors = Array.isArray(body.errors)
      ? body.errors.filter((e): e is string => typeof e === "string")
      : [];
    const reason = typeof body.reason === "string" ? body.reason : undefined;
    return new ApiError(
      res.status,
      body.detail ?? body.title ?? `HTTP ${res.status}`,
      errors,
      reason,
    );
  } catch {
    return new ApiError(res.status, `HTTP ${res.status}`);
  }
}

export const errorMessage = (e: unknown, fallback: string) =>
  e instanceof Error ? e.message : fallback;
