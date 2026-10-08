"use client";

import { Field, cx, fieldClass } from "@aisolate/ui";
import { useState } from "react";
import type { LlmInput } from "@/lib/types";
import { validateLlms } from "@/lib/validate";
import { FormColumn, useProblems, type Labels, type RowFormProps } from "./FormProblems";

/** The model clients an airlock can run, by id with the UI's word for each; the backend refuses any other. */
export const PROVIDERS: Record<string, string> = {
  ollama: "Ollama",
  openai: "OpenAI-compatible",
};

/** The provider's word, a stored one the list lacks as its id. */
export const providerLabel = (id: string) => PROVIDERS[id] ?? id;

/** The one provider whose server takes an API key. */
const KEYED = "openai";

/** One model of AIsolate, a space or an airlock: the id code and aisolate.yaml name, its server and model. */
export function LlmForm({
  rows,
  index,
  edit,
  label,
  errors,
  busy,
  onSubmit,
  onCancel,
}: RowFormProps<LlmInput>) {
  const [row, setRow] = useState<LlmInput>({
    ...rows[index],
    provider: rows[index].provider || Object.keys(PROVIDERS)[0],
  });
  // A save that comes back refused drops the typed key, so none outlives its request.
  const [saving, setSaving] = useState(busy);
  if (busy !== saving) {
    setSaving(busy);
    if (!busy) setRow((r) => ({ ...r, api_key: "" }));
  }
  const set = (patch: Partial<LlmInput>) => setRow({ ...row, ...patch });

  const at = `llms[${index}]`;
  const labels: Labels = {
    [`${at}.id`]: "Id",
    [`${at}.provider`]: "Provider",
    [`${at}.url`]: "URL",
    [`${at}.model`]: "Model",
    [`${at}.api_key`]: "API key",
  };
  const all = rows.map((r, i) => (i === index ? row : r));
  // The other rows were valid when saved; only this one's problems have a field to show them in.
  const found = validateLlms(all).filter((e) => e.startsWith(`${at}.`));
  const problems = useProblems(labels, found, errors);
  // A stored provider the list lacks stays listed, so an edit never changes it unseen.
  const providers = [...new Set([...Object.keys(PROVIDERS), rows[index].provider].filter(Boolean))];
  const keyed = row.provider === KEYED;
  // A stored Secret keeps until a typed key replaces it.
  const keeps = Boolean(row.secret_ref);
  const trimmed = (r: LlmInput): LlmInput => ({
    ...r,
    id: r.id.trim(),
    url: r.url.trim(),
    model: r.model.trim(),
  });

  return (
    <FormColumn
      problems={problems}
      onSubmit={() => onSubmit(all.map((r, i) => (i === index ? trimmed(r) : r)))}
      busy={busy}
      label={label}
      onCancel={onCancel}
    >
      <Field
        {...problems.field(`${at}.id`)}
        label="Id"
        help="What aisolate.yaml's llm and the code's aisolate.llm(…) name. It cannot change once saved."
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
      <Field {...problems.field(`${at}.provider`)} label="Provider">
        <select
          {...problems.control(`${at}.provider`)}
          className={fieldClass}
          value={row.provider}
          // A typed key is for the server it was typed for: another provider takes none.
          onChange={(e) =>
            set({ provider: e.target.value, ...(e.target.value === KEYED ? {} : { api_key: "" }) })
          }
        >
          {providers.map((p) => (
            <option key={p} value={p}>
              {providerLabel(p)}
            </option>
          ))}
        </select>
      </Field>
      <Field
        {...problems.field(`${at}.url`)}
        label="URL"
        help={
          keyed
            ? "The server's base URL with its version path, for example https://api.openai.com/v1."
            : "The address of the model server, for example http://ollama:11434."
        }
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
        {...problems.field(`${at}.model`)}
        label="Model"
        help="The model's name as the provider lists it, for example granite4.1:8b."
      >
        <input
          {...problems.control(`${at}.model`)}
          spellCheck={false}
          className={fieldClass}
          value={row.model}
          onChange={(e) => set({ model: e.target.value })}
        />
      </Field>
      {keyed && (
        <Field
          {...problems.field(`${at}.api_key`)}
          label="API key"
          optional
          help={
            keeps
              ? "Leave blank to keep it."
              : "Kept as a Secret, never shown again. Leave blank for a server that needs none."
          }
        >
          <input
            {...problems.control(`${at}.api_key`)}
            // Not the user's own login: this one is the model server's.
            autoComplete="new-password"
            type="password"
            className={fieldClass}
            value={row.api_key ?? ""}
            onChange={(e) => set({ api_key: e.target.value })}
          />
        </Field>
      )}
    </FormColumn>
  );
}
