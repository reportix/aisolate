"use client";

import { Button } from "@aisolate/ui";
import { useEffect, useEffectEvent, useState, type FormEvent, type ReactNode } from "react";
import { flushSync } from "react-dom";

/** A field's control id; the kit's Field puts its errors at `${id}-error`, its help at `${id}-help`. */
const fieldId = (field: string) => `field-${field}`;

/** Field name to label, in the order the form shows the fields. */
export type Labels = Record<string, string>;
/** One entry of the footer's summary; `field` is empty when no control matches the message. */
type Problem = { field: string; label: string };
type Problems = ReturnType<typeof useProblems>;

const NONE: ReadonlySet<string> = new Set();
const unique = <T,>(items: T[]) => [...new Set(items)];

/** The field of a "field: message" error. */
function fieldOf(message: string): string {
  const colon = message.indexOf(":");
  return colon < 0 ? "" : message.slice(0, colon).trim();
}

/** Focuses the first of the fields in form order. */
function focusFirst(labels: Labels, fields: string[]) {
  const controls = Object.keys(labels)
    .filter((f) => fields.includes(f))
    .map((f) => document.getElementById(fieldId(f)));
  controls.find(Boolean)?.focus();
}

/**
 * The problems a form shows: `found` (validate.ts over the current values) for the fields the user
 * has left, all of them once a submit was tried, and the server's `errors` until their field is
 * edited. `labels` orders the summary and picks the field a refused submit focuses.
 */
export function useProblems(labels: Labels, found: string[], server: string[]) {
  const [touched, setTouched] = useState(NONE);
  const [submitted, setSubmitted] = useState(false);
  // Built against one server answer; the next answer starts with nothing edited.
  const [edited, setEdited] = useState({ of: server, fields: NONE });
  const changed = edited.of === server ? edited.fields : NONE;

  // Once per server answer, not per render: forms build `labels` anew on every render.
  const focusServer = useEffectEvent(() => focusFirst(labels, server.map(fieldOf)));
  useEffect(() => {
    if (server.length > 0) focusServer();
  }, [server]);

  const shown = unique([
    ...found.filter((e) => submitted || touched.has(fieldOf(e))),
    ...server.filter((e) => !changed.has(fieldOf(e))),
  ]);
  const fields = unique(shown.map(fieldOf));
  const errorsFor = (field: string) => shown.filter((e) => fieldOf(e) === field);

  const list: Problem[] = [
    ...Object.keys(labels)
      .filter((f) => fields.includes(f))
      .map((f) => ({ field: f, label: labels[f] })),
    ...shown
      .filter((e) => !Object.hasOwn(labels, fieldOf(e)))
      .map((e) => ({ field: "", label: e })),
  ];

  return {
    list,
    /** The props of a field's `Field`. */
    field: (field: string) => ({ id: fieldId(field), errors: errorsFor(field) }),
    /** The props of a field's control: id, its errors and help as description, blur and edit. */
    control: (field: string) => ({
      id: fieldId(field),
      "aria-invalid": errorsFor(field).length > 0 || undefined,
      "aria-describedby": `${fieldId(field)}-error ${fieldId(field)}-help`,
      autoComplete: "off",
      onBlur: () => setTouched((s) => new Set([...s, field])),
      onInput: () =>
        setEdited((d) => ({
          of: server,
          fields: new Set([...(d.of === server ? d.fields : []), field]),
        })),
    }),
    /** The form's onSubmit: sends only without problems, else shows them and focuses the first. */
    submit: (send: () => void) => (e: FormEvent) => {
      e.preventDefault();
      const invalid = unique(found.map(fieldOf));
      if (invalid.length === 0) return send();
      // Commit the errors first, so the focused field is announced with its error.
      flushSync(() => setSubmitted(true));
      focusFirst(labels, invalid);
    },
  };
}

type FooterProps = {
  problems: Problems;
  busy: boolean;
  /** The primary's words, "Save space". */
  label: string;
  onCancel: () => void;
};

/** Save and Cancel, then the count of problems; a server error no control shows is spelled out. */
export function FormFooter({ problems, busy, label, onCancel }: FooterProps) {
  const n = problems.list.length;
  const loose = problems.list.filter((p) => !p.field).map((p) => p.label);
  return (
    <div className="flex flex-wrap items-center gap-2">
      <Button type="submit" disabled={busy}>
        {busy ? "Saving…" : label}
      </Button>
      <Button variant="secondary" onClick={onCancel}>
        Cancel
      </Button>
      <p aria-live="polite" className="ml-2 text-sm text-error">
        {n > 0 && `${n} ${n === 1 ? "problem" : "problems"}`}
        {loose.length > 0 && `: ${loose.join(", ")}`}
      </p>
    </div>
  );
}

type ColumnProps = Omit<FooterProps, "problems"> & {
  problems: Problems;
  /** Sent only when the form finds no problem ({@link useProblems}' submit). */
  onSubmit: () => void;
  children: ReactNode;
};

/**
 * Every form as GitLab sets one: a single max-w-2xl column, no card, each field a label over its
 * control over its hint, every control the column's full width, 20px between fields, then the
 * primary and Cancel under the last field at the same left edge.
 */
export function FormColumn({ problems, onSubmit, children, ...footer }: ColumnProps) {
  return (
    <form noValidate onSubmit={problems.submit(onSubmit)} className="grid max-w-2xl gap-5">
      {children}
      <FormFooter problems={problems} {...footer} />
    </form>
  );
}

/** A form that edits one row of a list of models or data sources: LlmForm, DataSourceForm. */
export type RowFormProps<R> = {
  /** Its level's rows with this one among them, a new one last: the form edits one row. */
  rows: R[];
  /** Its place in `rows`: the backend names a row's errors by it (`llms[1].model`). */
  index: number;
  /** An existing row: its id is fixed, and a blank API key, user or password keeps its Secret. */
  edit?: boolean;
  /** The primary's words: "Add model" on a new one, "Save model" on an edit. */
  label: string;
  errors: string[];
  busy: boolean;
  /** All the rows with this one as typed; its credentials only when typed. */
  onSubmit: (rows: R[]) => void;
  onCancel: () => void;
};
