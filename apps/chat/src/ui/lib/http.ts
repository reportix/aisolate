export class ApiError extends Error {
  readonly status: number;
  constructor(status: number, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

export class Unauthorized extends ApiError {
  constructor() {
    super(401, "Sign in again");
    this.name = "Unauthorized";
  }
}

/** A gateway answered for a backend that did not: the one retryable failure. */
export class Unreachable extends ApiError {
  constructor() {
    super(0, "AIsolate is not answering");
    this.name = "Unreachable";
  }
}

export function authHeaders(token: string, accept = "application/json"): Record<string, string> {
  return { Authorization: `Bearer ${token}`, Accept: accept };
}

/**
 * Passes a 2xx response through; a 401 becomes Unauthorized, a dead gateway Unreachable, anything
 * else the problem it carries.
 */
export async function expectOk(res: Response): Promise<Response> {
  if (res.status === 401) throw new Unauthorized();
  if (res.status >= 502 && res.status <= 504) throw new Unreachable();
  if (!res.ok) throw await problem(res);
  return res;
}

/** RFC 9457 problem+json: prefer detail, then title, then the status line. */
export async function problem(res: Response): Promise<ApiError> {
  try {
    const body = (await res.json()) as { detail?: string; title?: string };
    return new ApiError(res.status, body.detail ?? body.title ?? `HTTP ${res.status}`);
  } catch {
    return new ApiError(res.status, `HTTP ${res.status}`);
  }
}
