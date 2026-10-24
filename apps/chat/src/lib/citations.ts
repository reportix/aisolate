// A fenced code block, or one left open while the answer streams.
const FENCE = /(^```[\s\S]*?(?:^```|(?![\s\S])))/m;

/**
 * The answer as the reader sees it: without the evidence ids the model writes after each fact. They
 * stay in the stream, where the airlock's verifier checks every figure against its citation. Code is
 * shown as written.
 */
export function stripCitations(text: string): string {
  return text
    .split(FENCE)
    .map((part, i) => (i % 2 ? part : stripProse(part)))
    .join("");
}

function stripProse(text: string): string {
  return (
    text
      // A citation on its own line takes its stop with it.
      .replace(/^[ \t]*\[[^[\]]*\bev-\d+[^[\]]*\][.,;]?[ \t]*$/gm, "")
      // Any square bracket naming an id is a citation, as the verifier reads it: [ev-1], [ev-1, ev-2],
      // [Evidence: ev-1 (Master agreement)]. A link's text is not.
      .replace(/[ \t]*\[[^[\]]*\bev-\d+[^[\]]*\](?!\()/g, "")
      .replace(/[ \t]*\((?:evidence(?: id)?:?\s*)?ev-\d+(?:\s*,\s*ev-\d+)*\)/gi, "")
  );
}
