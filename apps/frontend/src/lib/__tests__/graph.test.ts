import { describe, expect, it } from "vitest";
import { deployHint, fitZoom, nodesOf, stepZoom } from "../graph";
import type { Airlock, Code } from "../types";
import { airlockOf } from "./airlock.fixture";

const GRANITE = {
  id: "granite",
  provider: "ollama",
  url: "http://ollama:11434",
  model: "granite4.1:8b",
  from: "global",
} as const;

const handler = (
  name: string,
  stage: Code["handlers"][number]["stage"],
  order: number,
  description: string | null = `${name} words`,
  data_sources: string[] = [],
) => ({ name, stage, order, description, data_sources });

/** A deployed airlock answering with granite, listing `lists` of its code. */
const deployed = (jar: Code | null, lists: Partial<Airlock["form"]> = {}): Airlock =>
  airlockOf({
    form: { ...airlockOf().form, llm: "granite", ...lists },
    main_llm: GRANITE,
    jar,
  });

const codeOf = (handlers: Code["handlers"]): Code => ({
  digest: "sha256:abc",
  size: 1,
  language: "python",
  tools: [],
  handlers,
});

describe("nodesOf", () => {
  it("givenCodeAndListedHandlers_whenNodesOf_thenCodeOrderWithinEachStageAndTheLlmBetween", () => {
    // Arrange: listed out of code order, one handler of the code the airlock does not list
    const code = codeOf([
      handler("CitationVerifier", "verifier", 2),
      handler("SessionMemory", "memory", 1),
      handler("TodayContext", "context_prep", 2),
      handler("FigureVerifier", "verifier", 1),
      handler("HrDocumentsContextPreparator", "context_prep", 1),
      handler("UnlistedContext", "context_prep", 0),
    ]);
    const airlock = deployed(code, {
      context_preparators: ["TodayContext", "HrDocumentsContextPreparator"],
      memory_handlers: ["SessionMemory"],
      verifiers: ["CitationVerifier", "FigureVerifier"],
    });

    // Act
    const nodes = nodesOf(airlock);

    // Assert
    expect(nodes.map((n) => [n.kind, n.key, n.icon])).toEqual([
      ["Context preparator", "HrDocumentsContextPreparator", "file-search"],
      ["Context preparator", "TodayContext", "file-search"],
      ["Memory", "SessionMemory", "history"],
      ["Model", "llm", "cpu"],
      ["Verifier", "FigureVerifier", "check"],
      ["Verifier", "CitationVerifier", "check"],
    ]);
    // Every airlock's Model node reads the same; the card shows its id.
    expect(nodes[3]).toEqual({
      key: "llm",
      kind: "Model",
      title: "Model",
      icon: "cpu",
      details: [
        { label: "Id", value: "granite", mono: true },
        { label: "Model", value: "granite4.1:8b", mono: true },
      ],
    });
  });

  it("givenHandlerNamingDataSources_whenNodesOf_thenItsDetailsNameThemAsSource", () => {
    // Arrange
    const code = codeOf([
      handler("HrDocumentsContextPreparator", "context_prep", 1, "Read HR documents", ["hr"]),
      handler("BothBooks", "context_prep", 2, "Read both books", ["hr", "clients"]),
    ]);
    const airlock = deployed(code, {
      context_preparators: ["HrDocumentsContextPreparator", "BothBooks"],
    });

    // Act
    const [hr, both] = nodesOf(airlock);

    // Assert
    expect(hr.details).toEqual([
      { label: "Class", value: "HrDocumentsContextPreparator", mono: true },
      { label: "Source", value: "hr", mono: true },
    ]);
    expect(both.details[1]).toEqual({ label: "Source", value: "hr, clients", mono: true });
  });

  it("givenHandlerWithoutDescription_whenNodesOf_thenOneTitleHumanizedFromItsClass", () => {
    // Arrange
    const code = codeOf([
      handler("HrDocumentsContextPreparator", "context_prep", 1, null),
      handler("SessionMemory", "memory", 1, "Remember the conversation"),
    ]);
    const airlock = deployed(code, {
      context_preparators: ["HrDocumentsContextPreparator"],
      memory_handlers: ["SessionMemory"],
    });

    // Act
    const [preparator, memory] = nodesOf(airlock);

    // Assert
    expect(preparator).toEqual({
      key: "HrDocumentsContextPreparator",
      kind: "Context preparator",
      title: "Hr documents context preparator",
      icon: "file-search",
      details: [{ label: "Class", value: "HrDocumentsContextPreparator", mono: true }],
    });
    expect(memory).toMatchObject({ title: "Remember the conversation" });
  });

  it("givenAnLlmIdThatResolvesToNothing_whenNodesOf_thenTheLlmNodesDetailSaysSoInPlainText", () => {
    // Arrange
    const airlock = {
      ...deployed(codeOf([])),
      main_llm: null,
      form: { ...airlockOf().form, llm: "mistral" },
    };

    // Act
    const nodes = nodesOf(airlock);

    // Assert
    expect(nodes).toEqual([
      {
        key: "llm",
        kind: "Model",
        title: "Model",
        icon: "cpu",
        details: [
          {
            label: "Id",
            value: "no model named mistral in this airlock's space or globally",
            mono: false,
          },
        ],
      },
    ]);
  });

  it("givenNoCode_whenNodesOf_thenEmptyAndTheDeployHintNamesTheAirlock", () => {
    // Arrange
    const airlock = deployed(null, {
      context_preparators: ["HrDocumentsContextPreparator"],
    });

    // Act
    const nodes = nodesOf(airlock);
    const hint = deployHint(airlock.id);

    // Assert
    expect(nodes).toEqual([]);
    expect(hint).toEqual({
      lead: "No code yet.",
      rest: "In the project's aisolate.yaml set name: hr, then run aisolate deploy.",
    });
  });
});

describe("fitZoom", () => {
  it.each([
    ["four nodes that fit", 1054, 1054, 100],
    ["a narrow chain, never above 100", 500, 1054, 100],
    ["the Sales chain of five nodes", 1324, 1054, 79],
    ["a chain too wide to read, at the floor", 4000, 1054, 50],
  ])("givenChainAndRoom_%s_whenFitZoom_thenPercent", (_, chainWidth, room, expected) => {
    // Arrange / Act
    const zoom = fitZoom(chainWidth, room);

    // Assert
    expect(zoom).toBe(expected);
  });
});

describe("stepZoom", () => {
  it.each([
    [100, 1, 110],
    [100, -1, 90],
    [79, 1, 80],
    [79, -1, 70],
    [50, -1, 50],
    [200, 1, 200],
  ] as const)("givenZoom_%i_whenStep_%i_thenOnTheGrid_%i", (zoom, direction, expected) => {
    // Arrange / Act
    const next = stepZoom(zoom, direction);

    // Assert
    expect(next).toBe(expected);
  });
});
