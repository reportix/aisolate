"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import type { Global } from "./types";
import { type RowFormState, type Section, useSave } from "./scope";
import { plainErrors } from "./text";
import { useLoad } from "./useLoad";

/** AIsolate's LLMs or data sources in the API: one row per call, the row's id fixed once created. */
type Rows<R> = {
  list: (t: string) => Promise<Global<R>[]>;
  create: (t: string, row: R) => Promise<unknown>;
  update: (t: string, row: R, readAt: string) => Promise<Global<R>>;
  remove: (t: string, id: string) => Promise<unknown>;
};

const NOUNS = { llms: "models", "data-sources": "data sources" };

/**
 * AIsolate's LLMs or data sources page: the rows; Delete, which deletes at once; and Edit, which
 * opens the row's form above the list (`form`) and PUTs the row as read, the list keeping the saved
 * one. A refused delete's message ("hr/v1 would have no model granite; …") reads in the banner.
 *
 * @param field what the form names the row's errors by: the backend names a global row's by field alone
 */
export function useGlobalList<R extends { id: string }>(
  kind: Rows<R>,
  section: Section,
  field: "llms" | "data_sources",
) {
  const { data, setData, banner, setBanner } = useLoad(
    section,
    kind.list,
    `Could not load the ${NOUNS[section]}`,
  );
  const [editing, setEditing] = useState<string | null>(null);
  const index = data && editing ? data.findIndex((r) => r.id === editing) : -1;
  const { errors, busy, save } = useSave(setBanner, (e) =>
    index >= 0 ? `${field}[${index}].${e}` : e,
  );
  const form: RowFormState<R> | null =
    data && index >= 0
      ? {
          rows: data,
          index,
          banner: null, // the page's
          errors,
          busy,
          submit: (all) =>
            void save(
              (t) => kind.update(t, all[index], data[index].updated_at),
              (saved) => {
                setData((rows) => rows && rows.map((r) => (r.id === saved.id ? saved : r)));
                setEditing(null);
              },
            ),
          cancel: () => setEditing(null),
        }
      : null;
  return {
    rows: data,
    busy,
    problem: banner ?? (plainErrors(errors) || null),
    remove: (id: string) =>
      void save(
        (t) => kind.remove(t, id),
        () => setData((rows) => rows && rows.filter((r) => r.id !== id)),
      ),
    edit: setEditing,
    form,
  };
}

/**
 * The add page of AIsolate's LLMs or data sources (no `id`) or a row's edit page: its rows with a
 * `blank` one last on add, and the row's place among them, by which `field` names its errors (the
 * backend names a global row's errors by field alone). A save POSTs the form's row, or PUTs it as
 * read, then goes back to the list.
 */
export function useGlobalForm<R extends { id: string }>(
  kind: Rows<R>,
  section: Section,
  field: "llms" | "data_sources",
  id: string | undefined,
  blank: R,
): RowFormState<R> {
  const router = useRouter();
  const back = `/${section}`;
  const { data, banner, setBanner } = useLoad(
    section,
    kind.list,
    `Could not load the ${NOUNS[section]}`,
  );
  const rows: R[] | null = data && (id ? data : [...data, blank]);
  const index = rows ? (id ? rows.findIndex((r) => r.id === id) : rows.length - 1) : -1;
  const { errors, busy, save } = useSave(setBanner, (e) => `${field}[${index}].${e}`);
  return {
    rows,
    index,
    banner,
    errors,
    busy,
    submit: (all) =>
      void save(
        (t) =>
          id && data
            ? kind.update(t, all[index], data[index].updated_at)
            : kind.create(t, all[index]),
        () => back,
      ),
    cancel: () => router.push(back),
  };
}
