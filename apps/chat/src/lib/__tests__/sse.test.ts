import { describe, expect, it } from "vitest";
import { parseSse } from "../sse";
import type { StreamEvent } from "../types";

const streamOf = (...chunks: string[]) =>
  new ReadableStream<Uint8Array>({
    start(controller) {
      const enc = new TextEncoder();
      for (const c of chunks) controller.enqueue(enc.encode(c));
      controller.close();
    },
  });

const collect = async (body: ReadableStream<Uint8Array>) => {
  const events: StreamEvent[] = [];
  await parseSse(body, (e) => events.push(e));
  return events;
};

describe("parseSse", () => {
  it("givenCrlfLineEndings_whenABlockEnds_thenItsEventArrivesBeforeTheStreamCloses", async () => {
    // Arrange
    let push!: (text: string) => void;
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        push = (text) => controller.enqueue(new TextEncoder().encode(text));
      },
    });
    const first = new Promise<StreamEvent>((resolve) => void parseSse(body, resolve));
    // Act
    push('data:{"type":"answer_chunk","content":"Hi"}\r');
    push("\n\r\n");
    // Assert
    expect(await first).toEqual({ type: "answer_chunk", content: "Hi" });
  });

  it("givenTwoDataBlocks_whenParsed_thenOneEventEach", async () => {
    // Arrange
    const body = streamOf(
      'data:{"type":"answer_chunk","content":"Hi"}\n\ndata:{"type":"done","evidence_ids":[],"grounded":false}\n\n',
    );
    // Act
    const events = await collect(body);
    // Assert
    expect(events.map((e) => e.type)).toEqual(["answer_chunk", "done"]);
  });

  it("givenEventSplitAcrossTwoChunks_whenParsed_thenReassembled", async () => {
    // Arrange
    const body = streamOf('data:{"type":"answer_ch', 'unk","content":"split"}\n\n');
    // Act
    const events = await collect(body);
    // Assert
    expect(events).toEqual([{ type: "answer_chunk", content: "split" }]);
  });

  it("givenCommentLineAndMalformedJson_whenParsed_thenBothSkipped", async () => {
    // Arrange
    const body = streamOf(
      ': keep-alive\n\ndata: not json\n\ndata:{"type":"error","message":"x"}\n\n',
    );
    // Act
    const events = await collect(body);
    // Assert
    expect(events).toEqual([{ type: "error", message: "x" }]);
  });

  it("givenFinalEventWithoutTrailingBlankLine_whenParsed_thenEmitted", async () => {
    // Arrange
    const body = streamOf('data:{"type":"answer_chunk","content":"tail"}');
    // Act
    const events = await collect(body);
    // Assert
    expect(events).toEqual([{ type: "answer_chunk", content: "tail" }]);
  });
});
