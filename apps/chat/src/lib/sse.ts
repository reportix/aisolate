import type { StreamEvent } from "./types";

/**
 * Reads a fetch body as server-sent events and hands over each `data:` payload. Blocks split
 * across chunks are reassembled; a trailing block without the final blank line is still delivered.
 */
async function readSseData(
  body: ReadableStream<Uint8Array>,
  onData: (payload: string) => void | Promise<void>,
): Promise<void> {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  const emit = async (block: string) => {
    for (const line of block.split("\n")) {
      if (line.startsWith("data:")) await onData(line.slice(5).trim());
    }
  };

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    // Proxies may end lines with \r\n; a \r left at a chunk's end pairs up on the next pass.
    buffer = (buffer + decoder.decode(value, { stream: true })).replace(/\r\n/g, "\n");
    const blocks = buffer.split("\n\n");
    buffer = blocks.pop() ?? "";
    for (const block of blocks) await emit(block);
  }
  buffer += decoder.decode();
  if (buffer.trim()) await emit(buffer);
}

/** AIsolate's stream: one JSON event per block; payloads that are not JSON (keep-alives) are skipped. */
export async function parseSse(
  body: ReadableStream<Uint8Array>,
  onEvent: (e: StreamEvent) => void,
): Promise<void> {
  await readSseData(body, async (payload) => {
    let evt: StreamEvent;
    try {
      evt = JSON.parse(payload) as StreamEvent;
    } catch {
      return;
    }
    onEvent(evt);
    // Yield so React can paint between answer chunks.
    if (evt.type === "answer_chunk") await new Promise((r) => setTimeout(r, 0));
  });
}
