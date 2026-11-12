import type { AirlockVersion, Code, Stage, VersionForm } from "./types";

/** Version names in the versions table's order: digits by value, v2 before v10. */
export const byVersionName = (a: string, b: string) =>
  a.localeCompare(b, undefined, { numeric: true });

/** A new version: a name, a description and its LLM to pick, all else blank for its deploy to fill. */
export const emptyVersionForm = (): VersionForm => ({
  version: "",
  description: "",
  context_preparators: [],
  memory_handlers: [],
  llm: "",
  prompt: "",
  tools: [],
  verifiers: [],
  llms: [],
});

/** One stage's handlers the version lists, in the order its code runs them: order, then name. */
export const handlersOf = (code: Code, stage: Stage, listed: string[]) =>
  code.handlers
    .filter((h) => h.stage === stage && listed.includes(h.name))
    .sort((a, b) => a.order - b.order || a.name.localeCompare(b.name));

/** A version's endpoint as a full URL, whether the API gives it whole or as a path on its own host. */
export const endpointUrl = (endpoint: string, apiUrl: string) =>
  new URL(endpoint, apiUrl).toString();

/** Said wherever a version's code would show while no deploy has brought it. */
export const NO_CODE = "No code yet. aisolate deploy brings it.";

/** The LLM that answers, by id and model: `granite · granite4.1:8b`; null when the version names none it sees. */
export const mainLlmOf = ({ main_llm }: AirlockVersion) =>
  main_llm && `${main_llm.id} · ${main_llm.model}`;

/** Said in place of the LLM while the version's `llm` names none it sees, in the backend's words. */
export const noLlm = (id: string) =>
  id ? `no model named ${id} in this airlock's space or globally` : "none";
