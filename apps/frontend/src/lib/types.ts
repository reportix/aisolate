/**
 * A Postgres database an airlock's code reads, by id. Its user and password sit in a Secret
 * AIsolate keeps; they are never returned, only the Secret's name.
 */
export type DataSource = {
  /** The name the code asks for: lowercase letters, digits, _ or -. */
  id: string;
  /** Its JDBC URL, without credentials. */
  url: string;
  /** Read-only: the Secret holding the user and password; "" while none is stored. */
  secret_ref: string;
};

/** A data source row as a save carries it: the user and password, write-only, when typed. */
export type DataSourceInput = DataSource & { username?: string; password?: string };

/**
 * A model server and the model it serves, by id: the same entry chats or embeds, as far as its
 * model can. AIsolate, a space and an airlock each define their own; the same id nearer the
 * airlock replaces the farther one.
 */
export type Llm = {
  /** What aisolate.yaml's `llm` and the code's `aisolate.llm(id)` name: lowercase letters, digits, _ or -. */
  id: string;
  /** The model client the airlock runs: ollama, or openai for an OpenAI-compatible server. */
  provider: string;
  /** The model server's base URL; an OpenAI-compatible one's with its version path. */
  url: string;
  /** The model's name as the server lists it, e.g. granite4.1:8b. */
  model: string;
  /** Read-only: the Secret holding an OpenAI-compatible model's API key; "" or null while none is. */
  secret_ref?: string | null;
};

/** A model row as a save carries it: the API key, write-only, when typed. */
export type LlmInput = Llm & { api_key?: string };

/** An LLM or a data source AIsolate defines for every space: `updated_at` is what an edit was read at. */
export type Global<R> = R & { updated_at: string };

/** Who defines an LLM or a data source an airlock sees, nearest first. */
export type Origin = "airlock" | "space" | "global";

/** Someone of the company's directory, as AIsolate sees them: nothing more. */
export type Person = { name: string | null; username: string; email: string };

/** What a space member may do: a developer also edits its airlocks; both ask them. */
export type Role = "developer" | "user";

/** One of a space's users by e-mail, with their role in it. */
export type Member = { email: string; role: Role };

/** A listed user with who they are; without a directory only the e-mail is known. */
export type User = {
  email: string;
  name: string | null;
  username: string | null;
  /** False once the e-mail no longer resolves in the directory; null without a directory. */
  in_directory: boolean | null;
  /** A space's row: the member's role; null on an airlock's own rows. */
  role: Role | null;
};

/** The users of a space, or an airlock's own and those it inherits from its space. */
export type Users = {
  /** Whether a directory is connected: people are picked from it, else e-mails typed. */
  directory: boolean;
  users: User[];
  /** An airlock's users that come from its space; empty for a space. */
  inherited: User[];
};

/** A space: the users every airlock of it admits, and the LLMs and data sources they share. */
export type Space = {
  id: string;
  name: string;
  description: string;
  /** Its users with their role: every one may ask every airlock of the space. */
  members: Member[];
  /** Its own; AIsolate's come with every space. */
  llms: Llm[];
  data_sources: DataSource[];
  /** What the signed-in person may do here: the admin all, a developer its airlocks. */
  caller_role: "admin" | "developer" | null;
  /** The username of who created it; `created_by_name` is the name shown. */
  created_by: string | null;
  created_by_name: string | null;
  created_at: string;
  updated_at: string;
};

export type SpaceForm = Omit<
  Space,
  | "llms"
  | "data_sources"
  | "caller_role"
  | "created_by"
  | "created_by_name"
  | "created_at"
  | "updated_at"
> & {
  llms: LlmInput[];
  data_sources: DataSourceInput[];
};

/** The pipeline stage a handler runs in. */
export type Stage = "context_prep" | "memory" | "verifier";

/** What `aisolate deploy` brought to an airlock, read-only: its tools and handlers. */
export type Code = {
  digest: string;
  size: number;
  /** The code's language, python or java. */
  language: string;
  /** Each tool with the plugin that publishes it and the data source that plugin reads. */
  tools: { name: string; description: string; plugin: string; data_source: string }[];
  /**
   * A handler's `description` is its annotation's plain words, null when it gives none; its
   * `data_sources` the ids it says it reads, empty when it names none.
   */
  handlers: {
    name: string;
    stage: Stage;
    order: number;
    description: string | null;
    data_sources: string[];
  }[];
};

/**
 * What an airlock runs, in the order an exchange runs it, as `aisolate deploy` set it from the
 * project's aisolate.yaml: the pages read it and never send it back. The handlers and tools it
 * lists are its code's.
 */
export type Pipeline = {
  /** The context preparators it runs, by class name. */
  context_preparators: string[];
  /** The conversation memories it runs, by class name. */
  memory_handlers: string[];
  /** The id of the LLM that answers, from its own, its space's or AIsolate's; "" before a deploy. */
  llm: string;
  prompt: string;
  /** The tools of its code the model may call. */
  tools: string[];
  /** The answer verifiers it runs, by class name. */
  verifiers: string[];
};

/**
 * An airlock, on its own endpoint: its name, who may ask it, the LLMs and data sources it defines
 * over the space's, and what it runs, which `aisolate deploy` brings. A save from the UI sends the
 * identity, users and catalogs ({@link AirlockForm}); the deploy's fields stay as they are.
 */
export type Airlock = {
  id: string;
  space_id: string;
  name: string;
  description: string;
  /** The e-mails of its own users, who may ask it besides the space's; maybe none. */
  users: string[];
  /** The OAuth clients (azp) they may ask from; `*` admits any. */
  callers: string[];
  /** The machine clients (azp, exact) it admits on a token without an e-mail; maybe none. */
  clients: string[];
  llms: Llm[];
  data_sources: DataSource[];
  /** Counts its saves, the UI's and the deploys'. */
  revision: number;
  /** Where it answers: a whole URL, or a path on the API's host (see endpointUrl). */
  endpoint: string;
  form: Pipeline;
  /** The LLM its `llm` names, and where that is defined; null while it names none it sees. */
  main_llm: (Llm & { from: Origin }) | null;
  /** Its code (the wire's name for it); null until `aisolate deploy` brings it. */
  jar: Code | null;
  /** The username of who created it; `created_by_name` is the name shown. */
  created_by: string | null;
  created_by_name: string | null;
  created_at: string;
  updated_at: string;
};

export type AirlockForm = Omit<
  Airlock,
  | "space_id"
  | "llms"
  | "data_sources"
  | "revision"
  | "endpoint"
  | "form"
  | "main_llm"
  | "jar"
  | "created_by"
  | "created_by_name"
  | "created_at"
  | "updated_at"
> & { llms: LlmInput[]; data_sources: DataSourceInput[] };
