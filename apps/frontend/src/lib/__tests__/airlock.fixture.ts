import type { Airlock, Space } from "../types";

/** A space as the API answers it, with what a test overrides. */
export const spaceOf = (over: Partial<Space> = {}): Space => ({
  id: "acme",
  name: "Acme",
  description: "",
  developers: ["eric@example.com"],
  roles: ["staff"],
  llms: [],
  data_sources: [],
  caller_role: "developer",
  created_by: "seed",
  created_by_name: null,
  created_at: "2026-09-01T00:00:00Z",
  updated_at: "2026-09-01T00:00:00Z",
  ...over,
});

/** An airlock before its first deploy, as the API answers it, with what a test overrides. */
export const airlockOf = (over: Partial<Airlock> = {}): Airlock => ({
  id: "hr",
  space_id: "acme",
  name: "HR",
  description: "",
  roles: [],
  callers: ["*"],
  clients: [],
  llms: [],
  data_sources: [],
  revision: 1,
  endpoint: "http://aisolate.local/v1/spaces/acme/airlocks/hr/chat",
  form: {
    context_preparators: [],
    memory_handlers: [],
    llm: "",
    prompt: "",
    tools: [],
    verifiers: [],
  },
  main_llm: null,
  jar: null,
  created_by: "seed",
  created_by_name: null,
  created_at: "2026-09-01T00:00:00Z",
  updated_at: "2026-09-01T00:00:00Z",
  ...over,
});
