import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import type { Message } from "@/lib/types";
import { MessageList } from "../MessageList";

const user = (more: Partial<Message>): Message => ({
  id: "u",
  role: "user",
  content: "",
  toolCalls: [],
  evidence: [],
  status: "done",
  ...more,
});
const text = (html: string) => html.replace(/<[^>]+>/g, "");

describe("MessageList", () => {
  it("givenAQuestionWithADocument_whenRendered_thenTheChipSitsUnderTheBubble", () => {
    // Arrange
    const m = user({ content: "What does my contract say?", attachment: "amendment.pdf" });

    // Act
    const html = renderToStaticMarkup(createElement(MessageList, { messages: [m] }));

    // Assert
    expect(text(html)).toContain("What does my contract say?amendment.pdf");
    expect(html).toMatch(/<p[^>]*>What does my contract say\?<\/p><div[^>]*title="amendment.pdf"/);
  });

  it("givenADocumentNotYetAsked_whenRendered_thenTheChipStandsAlone", () => {
    // Arrange
    const m = user({ attachment: "amendment.pdf" });

    // Act
    const html = renderToStaticMarkup(createElement(MessageList, { messages: [m] }));

    // Assert
    expect(text(html)).toBe("amendment.pdf");
  });
});
