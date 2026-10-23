"use client";

import { Field, cx, fieldClass } from "@aisolate/ui";
import { useState } from "react";
import type { DataSourceInput } from "@/lib/types";
import { validateDataSources } from "@/lib/validate";
import { FormColumn, useProblems, type Labels, type RowFormProps } from "./FormProblems";

/** One data source of a space or an airlock: the id its code asks for, its URL, user and password. */
export function DataSourceForm({
  rows,
  index,
  edit,
  label,
  errors,
  busy,
  onSubmit,
  onCancel,
}: RowFormProps<DataSourceInput>) {
  const [row, setRow] = useState(rows[index]);
  // A save that comes back refused drops the typed password, so none outlives its request.
  const [saving, setSaving] = useState(busy);
  if (busy !== saving) {
    setSaving(busy);
    if (!busy) setRow((r) => ({ ...r, password: "" }));
  }
  const set = (patch: Partial<DataSourceInput>) => setRow({ ...row, ...patch });

  const at = `data_sources[${index}]`;
  const labels: Labels = {
    [`${at}.id`]: "Id",
    [`${at}.url`]: "URL",
    [`${at}.username`]: "Username",
    [`${at}.password`]: "Password",
  };
  const all = rows.map((r, i) => (i === index ? row : r));
  // The other rows were valid when saved; only this one's problems have a field to show them in.
  const found = validateDataSources(all).filter((e) => e.startsWith(`${at}.`));
  const problems = useProblems(labels, found, errors);
  // A stored Secret keeps until a user and password replace it.
  const keeps = Boolean(row.secret_ref);

  return (
    <FormColumn
      problems={problems}
      onSubmit={() => onSubmit(all)}
      busy={busy}
      label={label}
      onCancel={onCancel}
    >
      <Field
        {...problems.field(`${at}.id`)}
        label="Id"
        help="What the code names: a tool plugin's dataSource(), a handler's aisolate.dataSource(id)."
      >
        <input
          {...problems.control(`${at}.id`)}
          spellCheck={false}
          className={cx(fieldClass, "read-only:bg-desk")}
          readOnly={edit}
          value={row.id}
          onChange={(e) => set({ id: e.target.value })}
        />
      </Field>
      <Field
        {...problems.field(`${at}.url`)}
        label="URL"
        help="A PostgreSQL database, by JDBC URL."
      >
        <input
          {...problems.control(`${at}.url`)}
          spellCheck={false}
          className={fieldClass}
          value={row.url}
          onChange={(e) => set({ url: e.target.value })}
        />
      </Field>
      <Field
        {...problems.field(`${at}.username`)}
        label="Username"
        optional={keeps}
        help={keeps && "Leave blank to keep it."}
      >
        <input
          {...problems.control(`${at}.username`)}
          spellCheck={false}
          className={fieldClass}
          value={row.username ?? ""}
          onChange={(e) => set({ username: e.target.value })}
        />
      </Field>
      <Field
        {...problems.field(`${at}.password`)}
        label="Password"
        optional={keeps}
        help={
          keeps ? "Leave blank to keep it." : "Stored encrypted; an admin can see it from View."
        }
      >
        <input
          {...problems.control(`${at}.password`)}
          // Not the user's own login: this one is the database's.
          autoComplete="new-password"
          type="password"
          className={fieldClass}
          value={row.password ?? ""}
          onChange={(e) => set({ password: e.target.value })}
        />
      </Field>
    </FormColumn>
  );
}
