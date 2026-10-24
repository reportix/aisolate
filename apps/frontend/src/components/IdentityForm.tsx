"use client";

import { Field, cx, fieldClass } from "@aisolate/ui";
import { useState, useSyncExternalStore } from "react";
import { validateName } from "@/lib/validate";
import { RoleList, roleProblem } from "./RoleList";
import { FormColumn, useProblems, type Labels } from "./FormProblems";

export type Identity = {
  id?: string;
  name: string;
  description: string;
  /** A space's developers (e-mails) and the roles that may ask; only where the form shows them. */
  developers?: string[];
  roles?: string[];
};

const emailProblem = (e: string) =>
  /^[^\s@]+@[^\s@]+$/.test(e) ? null : `${e}: an e-mail has one @ and no whitespace`;

const FIELDS: Labels = {
  id: "Id",
  name: "Name",
  description: "Description",
  developers: "Developers",
  roles: "Roles",
};

type Props = {
  initial: Identity;
  /**
   * A space's or an airlock's id. A create page asks none: the backend makes it from the name. Once
   * saved it shows read-only, as GitLab's group ID, since every URL, endpoint and project's .env holds it.
   */
  id?: "new" | "fixed";
  /** With `id`: the field's label ("Space URL") and the path before the id ("/spaces/"). */
  url?: { label: string; path: string };
  /** The primary's words: "Save space" on a new one, "Save" in place. */
  /** The chip lists to show after the description: a space has both, an airlock roles. */
  lists?: ("developers" | "roles")[];
  label: string;
  errors: string[];
  busy: boolean;
  onSubmit: (identity: Identity) => void;
  onCancel: () => void;
};

/**
 * The name and description of a space or an airlock (its URL read-only once saved): asked for on
 * its create page, and edited in place under its header. Everything else of the entity has a page
 * of its own.
 */
export function IdentityForm({
  initial,
  id,
  url,
  lists = [],
  label,
  errors,
  busy,
  onSubmit,
  onCancel,
}: Props) {
  const [draft, setDraft] = useState({
    ...initial,
    id: initial.id ?? "",
    developers: initial.developers ?? [],
    roles: initial.roles ?? [],
  });
  // The host is the browser's; the server renders none, so hydration matches.
  const host = useSyncExternalStore(
    () => () => {},
    () => window.location.host,
    () => "",
  );
  const problems = useProblems(
    url ? { ...FIELDS, id: url.label } : FIELDS,
    validateName(draft.name),
    errors,
  );

  return (
    <FormColumn
      problems={problems}
      onSubmit={() =>
        onSubmit({
          name: draft.name.trim(),
          description: draft.description.trim(),
          ...(lists.includes("developers") && { developers: draft.developers }),
          ...(lists.includes("roles") && { roles: draft.roles }),
        })
      }
      busy={busy}
      label={label}
      onCancel={onCancel}
    >
      <Field {...problems.field("name")} label="Name">
        <input
          {...problems.control("name")}
          className={fieldClass}
          value={draft.name}
          onChange={(e) => setDraft({ ...draft, name: e.target.value })}
        />
      </Field>
      {id === "fixed" && url && (
        <Field
          {...problems.field("id")}
          label={url.label}
          help="Made from the name when it was created. Every endpoint and each project's .env use it, so it cannot change."
        >
          <div className="flex">
            <span className="flex shrink-0 items-center rounded-l border border-r-0 border-control bg-desk px-3 text-sm text-ink-secondary">
              {host}
              {url.path}
            </span>
            <input
              {...problems.control("id")}
              className={cx(fieldClass, "rounded-l-none read-only:bg-desk")}
              readOnly
              value={draft.id}
            />
          </div>
        </Field>
      )}
      <Field {...problems.field("description")} label="Description" optional>
        {/* Three rows, as GitLab's project description; it stays one line of text when saved. */}
        <textarea
          {...problems.control("description")}
          rows={3}
          className={cx(fieldClass, "resize-y")}
          value={draft.description}
          onChange={(e) => setDraft({ ...draft, description: e.target.value })}
        />
      </Field>
      {lists.includes("developers") && (
        <Field
          {...problems.field("developers")}
          label="Developers"
          help="Who edits this space in the AIsolate UI (e-mails)"
          optional
        >
          <RoleList
            control={problems.control("developers")}
            value={draft.developers}
            onChange={(developers) => setDraft({ ...draft, developers })}
            check={emailProblem}
            placeholder="name@example.com, then Enter"
          />
        </Field>
      )}
      {lists.includes("roles") && (
        <Field
          {...problems.field("roles")}
          label="Roles"
          help="Who may ask: the roles your identity provider puts in the token"
          optional
        >
          <RoleList
            control={problems.control("roles")}
            value={draft.roles}
            onChange={(roles) => setDraft({ ...draft, roles })}
            check={roleProblem}
            placeholder="a role, then Enter"
          />
        </Field>
      )}
    </FormColumn>
  );
}
