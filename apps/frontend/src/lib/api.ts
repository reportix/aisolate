import {
  ApiError,
  Unauthorized,
  Unreachable,
  authHeaders,
  errorMessage,
  expectOk,
} from "@aisolate/ui";
import { forgetAndLogin } from "./auth";
import { reportDown } from "./connection";
import { env } from "./env";
import {
  type AuditDetail,
  type AuditFilters,
  type AuditPage,
  type ChainStatus,
  type DataError,
  type ExportFormat,
  eventsQuery,
} from "./audit";
import { type Charts, type MetricsFilters, metricsQuery } from "./metrics";
import type { MonitoringViews } from "./monitoring";
import { type TraceDetail, type TraceFilters, type TracePage, tracesQuery } from "./trace";
import type {
  Airlock,
  AirlockForm,
  DataSource,
  DataSourceInput,
  Global,
  Llm,
  LlmInput,
  Person,
  Space,
  SpaceForm,
  Users,
} from "./types";

type Method = "GET" | "POST" | "PUT" | "DELETE";

// False after a create or save the cluster did not take yet; without a cluster nothing is to take.
let applied = true;
/** Whether the last create or save is in effect: its answer's `AIsolate-Cluster` is not "not applied". */
export const clusterTookLastWrite = () => applied;

/** A call to the AIsolate control API with the user's token; JSON unless `headers` say otherwise. */
async function send(
  token: string,
  method: Method,
  path: string,
  body?: unknown,
  headers: Record<string, string> = {},
) {
  const res = await expectOk(
    await fetch(`${env.aisolateUrl}/v1${path}`, {
      method,
      headers: {
        ...authHeaders(token),
        ...(body === undefined ? {} : { "Content-Type": "application/json" }),
        ...headers,
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    }).catch(unreachable),
  );
  if (method !== "GET") applied = res.headers.get("AIsolate-Cluster") !== "not applied";
  return res;
}

const call = async <T>(
  token: string,
  method: Method,
  path: string,
  body?: unknown,
  headers?: Record<string, string>,
) => (await (await send(token, method, path, body, headers)).json()) as T;

/**
 * What a save's form was read at, the `updated_at` of a space or an airlock: the backend refuses
 * the save when another came between, rather than undo it.
 */
const ifMatch = (readAt: string) => ({ "If-Match": `"${readAt}"` });

/** A refused connection rejects the fetch with "Failed to fetch"; nobody can act on that sentence. */
function unreachable(): never {
  throw new Unreachable(
    typeof navigator !== "undefined" && navigator.onLine === false ? "offline" : "server",
  );
}

const space = (id: string) => `/spaces/${encodeURIComponent(id)}`;
const airlock = (spaceId: string, id: string) =>
  `${space(spaceId)}/airlocks/${encodeURIComponent(id)}`;
const llm = (id: string) => `/llms/${encodeURIComponent(id)}`;
const dataSource = (id: string) => `/data-sources/${encodeURIComponent(id)}`;

/**
 * A data source row as a save sends it: trimmed, a credential only when typed. Typed credentials
 * make a new Secret; without them the row names its stored Secret, which keeps it.
 */
function dataSourceBody(row: DataSourceInput): Partial<DataSourceInput> {
  const username = row.username?.trim() ?? "";
  const password = row.password ?? "";
  const body: Partial<DataSourceInput> = { id: row.id.trim(), url: row.url.trim() };
  if (username) body.username = username;
  if (password) body.password = password;
  if (!username && !password && row.secret_ref) body.secret_ref = row.secret_ref;
  return body;
}

/** A model row as a save sends it, as dataSourceBody does: its API key only when typed. */
function llmBody(row: LlmInput): LlmInput {
  const apiKey = row.api_key?.trim() ?? "";
  const body: LlmInput = { id: row.id, provider: row.provider, url: row.url, model: row.model };
  if (apiKey) body.api_key = apiKey;
  else if (row.secret_ref) body.secret_ref = row.secret_ref;
  return body;
}

/** An audit export's body and the name the backend gives it; a stand-in when the header is not exposed. */
async function file(res: Response, format: ExportFormat) {
  const named = /filename="?([^";]+)/.exec(res.headers.get("Content-Disposition") ?? "");
  const filename = named?.[1] ?? `aisolate-audit.${format}`;
  return { blob: await res.blob(), filename };
}

/** A space or airlock save: the form, its rows as llmBody and dataSourceBody send them. */
const bodyOf = <T extends { llms: LlmInput[]; data_sources: DataSourceInput[] }>(form: T) => ({
  ...form,
  llms: form.llms.map(llmBody),
  data_sources: form.data_sources.map(dataSourceBody),
});

export const api = {
  /** AIsolate's own, which every space sees: an admin changes them, a developer of any space reads them. */
  llms: {
    list: (t: string) => call<Global<Llm>[]>(t, "GET", "/llms"),
    create: (t: string, row: LlmInput) => call<Global<Llm>>(t, "POST", "/llms", llmBody(row)),
    /** The id is fixed; the rest changes. */
    update: (t: string, row: LlmInput, readAt: string) =>
      call<Global<Llm>>(t, "PUT", llm(row.id), llmBody(row), ifMatch(readAt)),
    remove: (t: string, id: string) => send(t, "DELETE", llm(id)),
  },
  dataSources: {
    list: (t: string) => call<Global<DataSource>[]>(t, "GET", "/data-sources"),
    create: (t: string, row: DataSourceInput) =>
      call<Global<DataSource>>(t, "POST", "/data-sources", dataSourceBody(row)),
    update: (t: string, row: DataSourceInput, readAt: string) =>
      call<Global<DataSource>>(t, "PUT", dataSource(row.id), dataSourceBody(row), ifMatch(readAt)),
    remove: (t: string, id: string) => send(t, "DELETE", dataSource(id)),
  },
  audit: {
    /** A page of the audit lines, newest first; `cursor` is the page before's `next_cursor`. */
    events: (t: string, filters: AuditFilters, now: number, cursor?: string | null) =>
      call<AuditPage>(t, "GET", `/audit/events${eventsQuery(filters, now, cursor)}`),
    event: (t: string, id: string) =>
      call<AuditDetail>(t, "GET", `/audit/events/${encodeURIComponent(id)}`),
    /** Every line the list finds for the filters, as one file named by the backend. */
    export: async (t: string, format: ExportFormat, filters: AuditFilters, now: number) => {
      const query = new URLSearchParams(eventsQuery(filters, now));
      query.set("format", format);
      return file(
        await send(t, "GET", `/audit/export?${query}`, undefined, { Accept: "*/*" }),
        format,
      );
    },
    /** One event alone, as one file named by the backend. */
    exportEvent: async (t: string, id: string, format: ExportFormat) =>
      file(
        await send(
          t,
          "GET",
          `/audit/events/${encodeURIComponent(id)}/export?format=${format}`,
          undefined,
          { Accept: "*/*" },
        ),
        format,
      ),
    verify: (t: string, space: string, airlock: string) =>
      call<ChainStatus>(t, "GET", `/audit/verify?${new URLSearchParams({ space, airlock })}`),
    /** The data source the airlock's newest tool call could not read; null when it read them. */
    dataError: async (t: string, space: string, airlock: string) =>
      (
        await call<{ data_error: DataError | null }>(
          t,
          "GET",
          `/audit/data-error?${new URLSearchParams({ space, airlock })}`,
        )
      ).data_error,
  },
  /** Which of Monitoring's views the install has; 403 for whom Monitoring is not. */
  monitoring: (t: string) => call<MonitoringViews>(t, "GET", "/monitoring"),
  /** Monitoring's charts over the period, the caller's spaces alone. */
  metrics: (t: string, filters: MetricsFilters) =>
    call<Charts>(t, "GET", `/metrics${metricsQuery(filters)}`),
  traces: {
    /** A page of the exchanges, newest first; the period counts back from `now`. */
    list: (t: string, filters: TraceFilters, now: number, cursor?: string | null) =>
      call<TracePage>(t, "GET", `/traces${tracesQuery(filters, now, cursor)}`),
    get: (t: string, traceId: string) =>
      call<TraceDetail>(t, "GET", `/traces/${encodeURIComponent(traceId)}`),
  },
  spaces: {
    list: (t: string) => call<Space[]>(t, "GET", "/spaces"),
    get: (t: string, id: string) => call<Space>(t, "GET", space(id)),
    // No id on create: the backend takes the name's slug; JSON leaves the undefined key out.
    create: (t: string, form: SpaceForm) =>
      call<Space>(t, "POST", "/spaces", { ...bodyOf(form), id: undefined }),
    update: (t: string, form: SpaceForm, readAt: string) =>
      call<Space>(t, "PUT", space(form.id), bodyOf(form), ifMatch(readAt)),
    /** Its users, named from the directory when one is connected. */
    users: (t: string, id: string) => call<Users>(t, "GET", `${space(id)}/users`),
  },
  airlocks: {
    list: (t: string, spaceId: string) => call<Airlock[]>(t, "GET", `${space(spaceId)}/airlocks`),
    get: (t: string, spaceId: string, id: string) => call<Airlock>(t, "GET", airlock(spaceId, id)),
    /** Its code, stages, LLM and prompt come from `aisolate deploy`. */
    create: (t: string, spaceId: string, form: AirlockForm) =>
      call<Airlock>(t, "POST", `${space(spaceId)}/airlocks`, { ...bodyOf(form), id: undefined }),
    update: (t: string, spaceId: string, form: AirlockForm, readAt: string) =>
      call<Airlock>(t, "PUT", airlock(spaceId, form.id), bodyOf(form), ifMatch(readAt)),
    /** Its own users and those it inherits from the space, named from the directory. */
    users: (t: string, spaceId: string, id: string) =>
      call<Users>(t, "GET", `${airlock(spaceId, id)}/users`),
  },
  directory: {
    /** People whose name, username or e-mail holds `q`, at most 20; 404 with no directory. */
    search: (t: string, q: string) =>
      call<Person[]>(t, "GET", `/directory/people?q=${encodeURIComponent(q)}`),
  },
};

type Show = { banner: (message: string) => void; errors?: (errors: string[]) => void };

export const STALE = "Someone else changed this; reload to see their changes.";

/** A save refused because another came between since its form was read (see ifMatch). */
const stale = (e: ApiError) =>
  e.status === 412 || (e.status === 409 && e.errors.some((m) => /^(updated_at|revision):/.test(m)));

/**
 * What every page does with a failed call: a 401 restarts the sign-in, an unreachable backend takes
 * the whole UI over (no page works without it), a stale save asks for a reload, form errors go back
 * to the form when the page has one, anything else to the banner.
 */
export function onApiError(e: unknown, fallback: string, show: Show): void {
  if (e instanceof Unauthorized) {
    void forgetAndLogin();
    return;
  }
  if (e instanceof Unreachable) {
    reportDown(e.kind);
    return;
  }
  if (e instanceof ApiError && stale(e)) {
    show.banner(STALE);
    return;
  }
  if (show.errors && e instanceof ApiError && e.errors.length > 0) {
    show.errors(e.errors);
    return;
  }
  show.banner(errorMessage(e, fallback));
}
