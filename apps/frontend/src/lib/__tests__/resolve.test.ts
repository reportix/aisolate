import { describe, expect, it } from "vitest";
import { catalogOf, originLabel } from "../resolve";
import type { DataSource, Llm, Origin } from "../types";

const llm = (id: string, model: string): Llm => ({
  id,
  provider: "ollama",
  url: "http://ollama:11434",
  model,
});

const source = (id: string, url: string): DataSource => ({ id, url, secret_ref: "" });

describe("catalogOf", () => {
  it("givenSameIdOnThreeLevels_whenCatalogOf_thenTheNearestWinsNamingTheNextAndNewIdsAdd", () => {
    // Arrange: nearest first, as an airlock sees them
    const levels: [Origin, DataSource[]][] = [
      [
        "airlock",
        [
          source("acme_hr", "jdbc:postgresql://airlock-db:5432/hr"),
          source("payroll", "jdbc:postgresql://airlock-db:5432/payroll"),
        ],
      ],
      [
        "space",
        [
          source("acme_hr", "jdbc:postgresql://space-db:5432/acme_hr"),
          source("hr-documents", "jdbc:postgresql://space-db:5432/hr_documents"),
        ],
      ],
      ["global", [source("hr-documents", "jdbc:postgresql://shared-db:5432/hr_documents")]],
    ];

    // Act
    const catalog = catalogOf(levels);

    // Assert
    expect(catalog).toEqual([
      {
        ...source("acme_hr", "jdbc:postgresql://airlock-db:5432/hr"),
        from: "airlock",
        replaces: "space",
      },
      {
        ...source("payroll", "jdbc:postgresql://airlock-db:5432/payroll"),
        from: "airlock",
        replaces: null,
      },
      {
        ...source("hr-documents", "jdbc:postgresql://space-db:5432/hr_documents"),
        from: "space",
        replaces: "global",
      },
    ]);
  });

  it("givenAnAirlocksOwnAndAisolatesLlms_whenCatalogOf_thenOwnFirstThenTheGlobalsItDoesNotReplace", () => {
    // Arrange
    const own = [llm("granite", "granite4.1:8b-q4")];
    const globals = [llm("granite", "granite4.1:8b"), llm("nomic", "nomic-embed-text")];

    // Act
    const catalog = catalogOf<Llm>([
      ["airlock", own],
      ["space", []],
      ["global", globals],
    ]);

    // Assert
    expect(catalog.map((l) => [l.id, l.model, l.from, l.replaces])).toEqual([
      ["granite", "granite4.1:8b-q4", "airlock", "global"],
      ["nomic", "nomic-embed-text", "global", null],
    ]);
  });
});

describe("originLabel", () => {
  it.each<[Origin, string]>([
    ["airlock", "Direct"],
    ["space", "Inherited from Acme"],
    ["global", "Inherited from the platform"],
  ])("givenFrom_%s_whenOriginLabel_then_%s", (from, expected) => {
    // Arrange / Act
    const label = originLabel(from, { space: "Acme" });

    // Assert
    expect(label).toBe(expected);
  });
});
