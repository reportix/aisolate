import type { Airlock, AirlockVersion, Space } from "../types";
import { emptyVersionForm } from "../version";

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

/** An airlock without a version yet, as the API answers it, with what a test overrides. */
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
  versions: 0,
  created_by: "seed",
  created_by_name: null,
  created_at: "2026-09-01T00:00:00Z",
  updated_at: "2026-09-01T00:00:00Z",
  ...over,
});

/** A version before its first deploy, as the API answers it, with what a test overrides. */
export const versionOf = (over: Partial<AirlockVersion> = {}): AirlockVersion => ({
  airlock_id: "hr",
  version: "v1",
  description: "",
  revision: 1,
  endpoint: "http://aisolate.local/v1/spaces/acme/airlocks/hr/versions/v1/chat",
  created_by: "seed",
  created_by_name: null,
  created_at: "2026-09-01T00:00:00Z",
  updated_at: "2026-09-01T00:00:00Z",
  form: { ...emptyVersionForm(), version: "v1", llm: "granite" },
  main_llm: null,
  jar: null,
  ...over,
});
