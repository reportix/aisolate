import type { Airlock, Code, Stage } from "./types";

/** One stage's handlers the airlock lists, in the order its code runs them: order, then name. */
export const handlersOf = (code: Code, stage: Stage, listed: string[]) =>
  code.handlers
    .filter((h) => h.stage === stage && listed.includes(h.name))
    .sort((a, b) => a.order - b.order || a.name.localeCompare(b.name));

/** An airlock's endpoint as a full URL, whether the API gives it whole or as a path on its own host. */
export const endpointUrl = (endpoint: string, apiUrl: string) =>
  new URL(endpoint, apiUrl).toString();

/** Said wherever an airlock's code would show while no deploy has brought it. */
export const NO_CODE = "No code yet. aisolate deploy brings it.";

/** The LLM that answers, by id and model: `granite · granite4.1:8b`; null when the airlock names none it sees. */
export const mainLlmOf = ({ main_llm }: Airlock) =>
  main_llm && `${main_llm.id} · ${main_llm.model}`;

/** Said in place of the LLM while the airlock's `llm` names none it sees, in the backend's words. */
export const noLlm = (id: string) =>
  id ? `no model named ${id} in this airlock's space or globally` : "none";
