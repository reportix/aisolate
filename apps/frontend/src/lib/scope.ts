"use client";

import { useParams, usePathname, useRouter } from "next/navigation";
import { useState } from "react";
import { api, clusterTookLastWrite, onApiError } from "./api";
import { getAccessToken } from "./auth";
import { notAppliedPath, setNotAppliedOn } from "./notice";
import { catalogOf } from "./resolve";
import { mayChange } from "./roles";
import { plainErrors } from "./text";
import type {
  Airlock,
  AirlockForm,
  DataSource,
  DataSourceInput,
  Global,
  Llm,
  LlmInput,
  Origin,
  Space,
  SpaceForm,
} from "./types";
import { useLoad } from "./useLoad";
import { reloadOverview } from "./useOverview";

const emptyParts = () => ({
  id: "",
  name: "",
  description: "",
  llms: [],
  data_sources: [],
});

/** A new space: a name and a description to type; users, LLMs and data sources come after. */
export const emptySpaceForm = (): SpaceForm => ({ ...emptyParts(), members: [] });

/**
 * A new airlock: no users or machine clients of its own, and any client may call it. Every key here
 * is sent on a save, so the clients set in a project file survive a save in the UI.
 */
export const emptyAirlockForm = (): AirlockForm => ({
  ...emptyParts(),
  users: [],
  callers: ["*"],
  clients: [],
});

/** What a part's page changes: an airlock's users, a space's members, either's LLMs or data sources. */
export type Part = Partial<AirlockForm & SpaceForm>;

/** The fields of `entity` that `form` has: a save never sends the server's own (dates, counts). */
const pick = <F extends object>(entity: F, form: F) =>
  Object.fromEntries(Object.keys(form).map((k) => [k, entity[k as keyof F]])) as F;

/** What a save sends: every part of the entity, so changing one keeps the rest. */
export const spaceFormOf = (s: Space) => pick<SpaceForm>(s, emptySpaceForm());
export const airlockFormOf = (a: Airlock) => pick<AirlockForm>(a, emptyAirlockForm());

/**
 * One write: busy meanwhile, the banner and errors cleared, the rail reloaded after. `done` gets
 * the saved entity; a path it returns is where the page goes, with busy left on so a second click
 * cannot save twice, and where a save not yet in effect says so. `toField` renames a backend
 * error's field to the form's.
 */
export function useSave(setBanner: (m: string | null) => void, toField = (e: string) => e) {
  const router = useRouter();
  const pathname = usePathname();
  const [errors, setErrors] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);

  const save = async <T>(
    write: (token: string) => Promise<T>,
    done: (saved: T) => string | void,
  ) => {
    setBusy(true);
    setBanner(null);
    setErrors([]);
    try {
      const next = done(await write(await getAccessToken()));
      setNotAppliedOn(notAppliedPath(clusterTookLastWrite(), next, pathname));
      reloadOverview();
      if (next) return router.push(next);
    } catch (e) {
      onApiError(e, "Could not save", {
        banner: setBanner,
        errors: (es) => setErrors(es.map(toField)),
      });
    }
    setBusy(false);
  };

  return { errors, setErrors, busy, save };
}

type Loaded = { space: Space; airlock: Airlock | null };

type Route = { spaceId: string; airlockId?: string; space: Space | null; airlock: Airlock | null };

/**
 * PUTs the whole owner the route names with `part` replaced: the airlock under airlocks/[id], else
 * the space, never the other one because it loaded first.
 */
export function writeOwner(t: string, route: Route, part: Part) {
  const { spaceId, airlockId, space, airlock } = route;
  if (airlockId) {
    if (!airlock) return Promise.reject(new Error("The airlock has not loaded yet"));
    return api.airlocks.update(
      t,
      spaceId,
      { ...airlockFormOf(airlock), ...part },
      airlock.updated_at,
    );
  }
  if (!space) return Promise.reject(new Error("The space has not loaded yet"));
  return api.spaces.update(t, { ...spaceFormOf(space), ...part }, space.updated_at);
}

/**
 * The route's space, and its airlock under airlocks/[id]: read fresh, never from the rail's cache,
 * since a save PUTs all of it. `owner` is the airlock there, else the space: whose users, LLM and
 * data sources the page shows.
 */
export function useScope() {
  const { space: spaceId, id: airlockId } = useParams<{ space: string; id?: string }>();
  const { data, setData, banner, setBanner } = useLoad<Loaded>(
    `${spaceId}/${airlockId}`,
    async (t) => {
      const [space, airlock] = await Promise.all([
        api.spaces.get(t, spaceId),
        airlockId ? api.airlocks.get(t, spaceId, airlockId) : null,
      ]);
      return { space, airlock };
    },
    airlockId ? "Could not load the airlock" : "Could not load the space",
  );
  const space = data?.space ?? null;
  const airlock = data?.airlock ?? null;
  const owner: Space | Airlock | null = airlockId ? airlock : space;
  const spaceHome = `/spaces/${spaceId}`;

  return {
    spaceId,
    airlockId,
    space,
    airlock,
    owner,
    /** The owner's name, its id until it loads. */
    name: owner?.name ?? airlockId ?? spaceId,
    /** Whether this page's Add, Edit, Set and trash show: the space's own parts are the admin's. */
    mayChange: mayChange(space, Boolean(airlockId)),
    base: airlockId ? `${spaceHome}/airlocks/${airlockId}` : spaceHome,
    banner,
    setBanner,
    setOwner: (saved: Space | Airlock) =>
      setData(
        (d) =>
          d && (airlockId ? { ...d, airlock: saved as Airlock } : { ...d, space: saved as Space }),
      ),
    /** PUTs the whole owner with `part` replaced ({@link writeOwner}). */
    write: (t: string, part: Part): Promise<Space | Airlock> =>
      writeOwner(t, { spaceId, airlockId, space, airlock }, part),
  };
}

/** Where a list of models or data sources is, under its owner's address or AIsolate's. */
export type Section = "llms" | "data-sources";

/**
 * The add page of a list of models or data sources, or a row's edit page, whoever owns the list:
 * its rows with a new one last on add (null until they load), the row's place among them (-1 when
 * the edit page's row is not there), and `submit`, which saves the rows as the form sends them.
 */
export type RowFormState<R> = {
  rows: R[] | null;
  index: number;
  banner: string | null;
  errors: string[];
  busy: boolean;
  submit: (rows: R[]) => void;
  cancel: () => void;
};

/** A row of an owner's `field`, as a save carries it. */
type RowOf = { llms: LlmInput; data_sources: DataSourceInput };

/**
 * The add page of a space's or an airlock's models or data sources (no `id`) or a row's edit page:
 * the owner's `field` rows with a `blank` one last on add. A save PUTs the whole owner with them as
 * typed, the backend naming errors by row (`llms[1].model`), then goes back to `${base}/${section}`.
 */
export function useScopeForm<F extends keyof RowOf>(
  section: Section,
  field: F,
  id: string | undefined,
  blank: RowOf[F],
): RowFormState<RowOf[F]> & { airlockId?: string; name: string } {
  const scope = useScope();
  const router = useRouter();
  const { errors, busy, save } = useSave(scope.setBanner);
  const back = `${scope.base}/${section}`;
  const own = scope.owner && (scope.owner[field] as RowOf[F][]);
  const rows = own && (id ? own : [...own, blank]);
  const index = rows ? (id ? rows.findIndex((r) => r.id === id) : rows.length - 1) : -1;
  return {
    airlockId: scope.airlockId,
    name: scope.name,
    rows,
    index,
    banner: scope.banner,
    errors,
    busy,
    submit: (all) =>
      void save(
        (t) => scope.write(t, { [field]: all }),
        () => back,
      ),
    cancel: () => router.push(back),
  };
}

/**
 * A space's or an airlock's LLMs or Data sources page: what it sees by id, nearest first (its own,
 * the space's on an airlock, AIsolate's `globals`), and the trash on its own rows: the whole owner
 * goes back without the row. A refused remove's message reads in the banner: there is no form.
 */
export function useCatalog<F extends "llms" | "data_sources">(
  field: F,
  globals: (t: string) => Promise<Global<F extends "llms" ? Llm : DataSource>[]>,
) {
  type R = F extends "llms" ? Llm : DataSource;
  const scope = useScope();
  const { spaceId, airlockId, space, owner } = scope;
  const section = field === "llms" ? "llms" : "data-sources";
  const above = useLoad(
    `globals/${field}`,
    globals,
    `Could not load the platform's ${field === "llms" ? "models" : "data sources"}`,
  );
  const { errors, busy, save } = useSave(scope.setBanner);
  const own: Origin = airlockId ? "airlock" : "space";
  const mine = (owner?.[field] ?? []) as R[];
  const levels: [Origin, R[]][] = [[own, mine]];
  if (airlockId && space) levels.push(["space", space[field] as R[]]);
  levels.push(["global", above.data ?? []]);
  // Edit opens the row's form above the list: a save PUTs the owner with its rows as typed.
  const [editing, setEditing] = useState<string | null>(null);
  const index = editing ? mine.findIndex((r) => r.id === editing) : -1;
  const form: RowFormState<RowOf[F]> | null =
    index >= 0
      ? {
          rows: mine as RowOf[F][],
          index,
          banner: null, // the page's
          errors,
          busy,
          submit: (all) =>
            void save(
              (t) => scope.write(t, { [field]: all }),
              (saved) => {
                scope.setOwner(saved);
                setEditing(null);
              },
            ),
          cancel: () => setEditing(null),
        }
      : null;
  return {
    ...scope,
    own,
    rows: owner && space && above.data ? catalogOf(levels) : null,
    owners: {
      space: { name: space?.name ?? spaceId, href: `/spaces/${spaceId}/${section}` },
    },
    busy,
    problem: scope.banner ?? above.banner ?? (plainErrors(errors) || null),
    remove: (id: string) =>
      void save(
        (t) => scope.write(t, { [field]: mine.filter((r) => r.id !== id) }),
        scope.setOwner,
      ),
    edit: setEditing,
    form,
  };
}
