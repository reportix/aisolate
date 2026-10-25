import { ApiError, authHeaders, expectOk } from "@/ui";
import type { Settings } from "./settings";
import { parseSse } from "./sse";
import type { StreamEvent } from "./types";

/** The part of the settings an AIsolate call needs. */
type Target = Pick<Settings, "aisolateUrl" | "airlockId">;

/** What AIsolate lists for a chat client: an airlock its token admits, with the URL to post to. */
export type AirlockListing = {
  id: string;
  name: string;
  description: string;
  endpoint: string;
  tools: string[];
};

/**
 * How long the stream may stay silent before we give up on it. Longer than one model round, so a
 * slow answer is not mistaken for a dead connection.
 */
const STALL_TIMEOUT_MS = 120_000;

/** POSTs one message to the AIsolate airlock endpoint and streams its events. */
export async function streamChat(
  message: string,
  token: string,
  signal: AbortSignal,
  onEvent: (e: StreamEvent) => void,
  target: Target,
): Promise<void> {
  // One discovery call per message resolves the airlock's endpoint; nothing here caches it, so an
  // airlock pulled mid-chat is caught on the very next message instead of failing silently later.
  const airlock = (await listAirlocks(token, target.aisolateUrl)).find(
    (a) => a.id === target.airlockId,
  );
  if (!airlock) throw new ApiError(404, `Airlock ${target.airlockId} is not available to you`);
  // The token goes to AIsolate only, whatever host a listing names.
  if (new URL(airlock.endpoint).origin !== new URL(target.aisolateUrl).origin) {
    throw new ApiError(502, "AIsolate named an endpoint on another host");
  }
  // The watchdog's abort ends the request too, so events arriving late change nothing.
  const stalled = new AbortController();
  const res = await expectOk(
    await fetch(airlock.endpoint, {
      method: "POST",
      signal: AbortSignal.any([signal, stalled.signal]),
      headers: { ...authHeaders(token, "text/event-stream"), "Content-Type": "application/json" },
      body: JSON.stringify({ message }),
    }),
  );
  if (!res.body) throw new ApiError(res.status, "Empty response");

  // Every event restarts the clock; nothing at all for two minutes means the stream is gone.
  let watchdog = setTimeout(() => stalled.abort(), STALL_TIMEOUT_MS);
  try {
    await Promise.race([
      parseSse(res.body, (e) => {
        clearTimeout(watchdog);
        watchdog = setTimeout(() => stalled.abort(), STALL_TIMEOUT_MS);
        onEvent(e);
      }),
      stalledAfter(stalled.signal, signal),
    ]);
  } finally {
    clearTimeout(watchdog);
  }
}

/** Never resolves; rejects when the watchdog fires, unless the user stopped the turn themselves. */
function stalledAfter(watchdog: AbortSignal, userStop: AbortSignal): Promise<never> {
  return new Promise((_, reject) =>
    watchdog.addEventListener("abort", () => {
      if (!userStop.aborted) reject(new Error("The assistant stopped responding"));
    }),
  );
}

/** Deployed airlocks this user may use: every one AIsolate admits for the token, in its order. */
export async function listAirlocks(token: string, aisolateUrl: string): Promise<AirlockListing[]> {
  return json<AirlockListing[]>(
    await fetch(`${aisolateUrl}/v1/airlocks`, { headers: authHeaders(token) }),
  );
}

async function json<T>(res: Response): Promise<T> {
  return (await expectOk(res)).json() as Promise<T>;
}
