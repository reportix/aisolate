import { humanize } from "./humanize";
import { handlersOf, noLlm } from "./airlock";
import type { Airlock, Stage } from "./types";

/** What a node shows on hover or focus: the facts behind its title. */
export type GraphDetail = { label: string; value: string; mono: boolean };

/**
 * One node of the airlock's graph: a handler of its code, titled by its short description, or the
 * LLM that answers between them. Its kind names the step and picks its colour.
 */
export type GraphNode = {
  key: string;
  kind: "Context preparator" | "Memory" | "Model" | "Verifier";
  title: string;
  icon: "file-search" | "history" | "cpu" | "check";
  details: GraphDetail[];
};

/**
 * The airlock as its code runs an exchange, on one line: the context preparators, the
 * conversation memories, the LLM that answers, the answer verifiers; the other LLMs of its catalog
 * are its code's business. Empty until a deploy brings the code.
 */
export function nodesOf(airlock: Airlock): GraphNode[] {
  const code = airlock.jar;
  if (!code) return [];
  const { form, main_llm } = airlock;
  const handlers = (
    stage: Stage,
    listed: string[],
    kind: GraphNode["kind"],
    icon: GraphNode["icon"],
  ): GraphNode[] =>
    handlersOf(code, stage, listed).map((h) => ({
      key: h.name,
      kind,
      title: h.description?.trim() || humanize(h.name),
      icon,
      details: [
        { label: "Class", value: h.name, mono: true },
        ...(h.data_sources.length
          ? [{ label: "Source", value: h.data_sources.join(", "), mono: true }]
          : []),
      ],
    }));
  return [
    ...handlers("context_prep", form.context_preparators, "Context preparator", "file-search"),
    ...handlers("memory", form.memory_handlers, "Memory", "history"),
    {
      key: "llm",
      kind: "Model",
      title: "Model",
      icon: "cpu",
      details: main_llm
        ? [
            { label: "Id", value: main_llm.id, mono: true },
            { label: "Model", value: main_llm.model, mono: true },
          ]
        : [{ label: "Id", value: noLlm(form.llm), mono: false }],
    },
    ...handlers("verifier", form.verifiers, "Verifier", "check"),
  ];
}

/** The graph's zoom, in percent. */
export const ZOOM = { min: 50, max: 200, step: 10 };

/** The zoom a chain opens at: the canvas's width when the chain is wider, never above 100%. */
export const fitZoom = (chainWidth: number, room: number) =>
  Math.max(ZOOM.min, Math.min(100, Math.floor((room / chainWidth) * 100)));

/** One zoom step in or out, onto the 10% grid, so a fitted 79% steps to 80% and back to 100%. */
export const stepZoom = (zoom: number, direction: 1 | -1) => {
  const next =
    direction > 0
      ? Math.floor(zoom / ZOOM.step) * ZOOM.step + ZOOM.step
      : Math.ceil(zoom / ZOOM.step) * ZOOM.step - ZOOM.step;
  return Math.min(ZOOM.max, Math.max(ZOOM.min, next));
};

/** What the Overview tab says before a deploy, as its title and the line under it. */
export type DeployHint = { lead: string; rest: string };

/** Where the project names this airlock, before its first deploy. */
export function deployHint(airlockId: string): DeployHint {
  return {
    lead: "No code yet.",
    rest: `In the project's aisolate.yaml set name: ${airlockId}, then run aisolate deploy.`,
  };
}
