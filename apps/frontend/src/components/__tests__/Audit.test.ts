import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { broken, detail, intact, summary } from "@/lib/__tests__/audit.fixture";
import { AuditEventView } from "../AuditEvent";
import { AuditTable } from "../AuditTable";

const table = (events = [summary]) => renderToStaticMarkup(createElement(AuditTable, { events }));
const view = (chain = intact, d = detail) =>
  renderToStaticMarkup(createElement(AuditEventView, { detail: d, chain }));

describe("AuditTable", () => {
  it("givenNoEvent_whenRendered_thenTheHeadsStayOverOneQuietRow", () => {
    // Arrange / Act
    const html = table([]);

    // Assert
    expect(html).toContain("Time (UTC)");
    expect(html).toContain("Tokens");
    expect(html).toContain("There are no audit events for these filters.");
  });

  it("givenAnEvent_whenRendered_thenItsTimeLinksToItAndItsFiguresAreBadges", () => {
    // Arrange / Act
    const html = table();

    // Assert
    expect(html).toContain('href="/audit/1042"');
    expect(html).toContain(">hr<");
    expect(html).toContain("2026-10-18 09:11<");
    expect(html).toContain("1 sourced");
    expect(html).toContain("1 unsourced");
    expect(html).not.toContain("0 computed");
    expect(html).toContain("1,840 / 212");
  });
});

describe("AuditEventView", () => {
  it("givenAGroundedAnswer_whenRendered_thenEachFigureQuoteAndTableCarriesItsMarker", () => {
    // Arrange / Act
    const html = view();

    // Assert
    expect(html).toContain("What is my gross salary?");
    expect(html).toContain('aria-label="78,000 EUR, sourced from hr.compensation"');
    expect(html).toContain("hr.compensation · gross_salary · 3");
    expect(html).toContain('aria-label="2026, unsourced"');
    expect(html).toContain('aria-label="Quoted from Pay policy · Salary"');
    expect(html).toContain("<q>paid in twelve parts</q>");
    expect(html).toContain("From read_my_records(entity: hr.compensation)");
  });

  it("givenTheEvent_whenRendered_thenToolsVerdictsTokensAndModelShow", () => {
    // Arrange / Act
    const html = view();

    // Assert
    expect(html).toContain("read_my_records(entity: hr.compensation)");
    expect(html).toContain("FigureVerifier: PASS");
    expect(html).toContain("ollama/qwen3.6:27b-q4_K_M");
    expect(html).toContain("1,840 / 212");
    expect(html).toContain("6.1 s");
  });

  it("givenTheEvent_whenRendered_thenItsIdsAndHashesSitInAClosedTechnicalDetailsPanel", () => {
    // Arrange / Act
    const html = view();
    const [shown, technical] = html.split("<details");

    // Assert
    expect(shown).toContain("Unchanged since it was recorded");
    expect(shown).not.toContain(detail.hash);
    expect(shown).not.toContain("Rows hash");
    expect(technical).not.toMatch(/^[^>]*\sopen/);
    expect(technical).toContain("Technical details");
    for (const value of [
      detail.hash,
      detail.event.client,
      detail.event.trace_id,
      "read_my_records: 5c1e9b0a4d7f",
    ]) {
      expect(technical).toContain(value);
    }
  });

  it.each([
    [intact, "Intact: 1,042 events, 1 to 1042", "text-ok"],
    [broken, "Broken at event 517", "text-error"],
  ])("givenTheChain_whenRendered_thenItsStatus %#", (chain, line, tone) => {
    // Arrange / Act
    const html = view(chain);

    // Assert
    expect(html).toContain(line);
    expect(html).toContain(tone);
  });

  it("givenARefusal_whenRendered_thenNothingAnsweredAndNoTool", () => {
    // Arrange
    const refusal = {
      ...detail,
      event: {
        ...detail.event,
        event: "refusal" as const,
        outcome: "refused" as const,
        reason: "role",
        answer: null,
        grounding: null,
        tools: [],
        model: null,
      },
    };

    // Act
    const html = view(intact, refusal);

    // Assert
    expect(html).toContain("Nothing was answered: refused · no listed role.");
    expect(html).toContain("No tool was called.");
  });
});
