"use client";

import { Field, Muted, Tile, cx, fieldClass, tableFrame } from "@aisolate/ui";
import { useRouter } from "next/navigation";
import { useEffect, useMemo, useState } from "react";
import { FormPage } from "@/components/FormPage";
import { FormColumn, useProblems, type Labels } from "@/components/FormProblems";
import { ROLES } from "@/components/UserTable";
import { api, onApiError } from "@/lib/api";
import { getAccessToken } from "@/lib/auth";
import { addMembers, addUsers, chosenEmails, sameEmail, searchAsYouType } from "@/lib/people";
import { useSave, useScope } from "@/lib/scope";
import type { Person, Role, Users } from "@/lib/types";
import { useLoad } from "@/lib/useLoad";

// A space's checks name its members; an airlock's its users. Both are the people picked here.
const FIELDS: Labels = { users: "People", members: "People" };

/**
 * Add users, a page of its own: people are picked from the company's directory, searched as one
 * types, and become users by their e-mail; with no directory connected, e-mails are typed. On a
 * space each comes in with the role chosen (User unless the admin picks Developer). AIsolate never
 * creates anyone: the identity provider holds the people.
 */
export default function AddUsersPage() {
  const router = useRouter();
  const { spaceId, airlockId, space, airlock, owner, name, base, banner, setBanner, write } =
    useScope();
  const listed = useLoad<Users>(
    `${spaceId}/${airlockId}/users`,
    (t) => (airlockId ? api.airlocks.users(t, spaceId, airlockId) : api.spaces.users(t, spaceId)),
    "Could not load the users",
  );
  const users = listed.data;
  const { errors, busy, save } = useSave(setBanner);
  const back = `${base}/users`;

  const [text, setText] = useState("");
  const [found, setFound] = useState<Person[] | null>(null);
  const [ticked, setTicked] = useState<Person[]>([]);
  const [typed, setTyped] = useState("");
  const [role, setRole] = useState<Role>("user");
  const field = useMemo(
    () =>
      searchAsYouType(
        async (q) => api.directory.search(await getAccessToken(), q),
        (people, e) => {
          setFound(people);
          if (e) onApiError(e, "Could not search the directory", { banner: setBanner });
        },
      ),
    [setBanner],
  );
  useEffect(() => field.cancel, [field]);

  const directory = users?.directory ?? false;
  const chosen = chosenEmails(directory, ticked, typed);
  const problems = useProblems(
    FIELDS,
    chosen.length === 0
      ? [directory ? "users: tick at least one person" : "users: type at least one e-mail"]
      : [],
    errors,
  );

  /** Already asking: the owner's own users, and on an airlock the space's. */
  const assigned = (email: string) =>
    users?.users.some((u) => sameEmail(u.email, email))
      ? "Already a user"
      : users?.inherited.some((u) => sameEmail(u.email, email))
        ? "From the space"
        : null;
  const isTicked = (p: Person) => ticked.some((t) => sameEmail(t.email, p.email));
  const toggle = (p: Person) =>
    setTicked(isTicked(p) ? ticked.filter((t) => !sameEmail(t.email, p.email)) : [...ticked, p]);

  return (
    <FormPage
      title="Add users"
      description={
        airlockId
          ? `People who may ask ${name} only; the space's users may ask it anyway.`
          : "People who may ask every airlock of this space, by the e-mail they sign in with."
      }
      banner={banner ?? listed.banner}
    >
      {owner && users && (
        <FormColumn
          problems={problems}
          onSubmit={() =>
            void save(
              (t) =>
                write(
                  t,
                  airlock
                    ? { users: addUsers(airlock.users, chosen) }
                    : { members: addMembers(space!.members, chosen, role) },
                ),
              () => back,
            )
          }
          busy={busy}
          label="Add"
          onCancel={() => router.push(back)}
        >
          {directory ? (
            <>
              <Field
                {...problems.field("users")}
                label="Search people"
                help="By name, username or e-mail, in the company's directory."
              >
                <input
                  {...problems.control("users")}
                  type="search"
                  spellCheck={false}
                  className={fieldClass}
                  value={text}
                  onChange={(e) => {
                    setText(e.target.value);
                    field.run(e.target.value);
                  }}
                />
              </Field>
              {found?.length === 0 && (
                <Muted>Nobody in the directory matches “{text.trim()}”.</Muted>
              )}
              {found && found.length > 0 && (
                <ul
                  aria-label="People found"
                  className={cx(tableFrame, "divide-y divide-hairline")}
                >
                  {found.map((p) => {
                    const already = assigned(p.email);
                    return (
                      <li key={p.email}>
                        <label
                          className={cx(
                            "flex items-center gap-3 px-4 py-3 text-sm",
                            already ? "text-ink-tertiary" : "cursor-pointer hover:bg-desk",
                          )}
                        >
                          <input
                            type="checkbox"
                            className="size-4 accent-pen"
                            disabled={Boolean(already)}
                            checked={Boolean(already) || isTicked(p)}
                            onChange={() => toggle(p)}
                          />
                          <Tile id={p.email} name={p.name ?? p.email} size="sm" />
                          <span className="font-medium">{p.name ?? p.username}</span>
                          <span className="min-w-0 truncate text-ink-secondary">{p.email}</span>
                          {already && <span className="ml-auto text-xs">{already}</span>}
                        </label>
                      </li>
                    );
                  })}
                </ul>
              )}
              {ticked.length > 0 && (
                <p aria-live="polite" className="text-sm text-ink-secondary">
                  To add: {ticked.map((p) => p.name ?? p.email).join(", ")}
                </p>
              )}
            </>
          ) : (
            <>
              <Muted>No directory connected: type sign-in e-mails.</Muted>
              <Field {...problems.field("users")} label="E-mails" help="One e-mail per line.">
                <textarea
                  {...problems.control("users")}
                  rows={4}
                  spellCheck={false}
                  className={cx(fieldClass, "resize-y")}
                  value={typed}
                  onChange={(e) => setTyped(e.target.value)}
                />
              </Field>
            </>
          )}
          {!airlockId && (
            <Field
              id="role"
              label="Role"
              help="A user asks the space's airlocks; a developer also edits them and deploys into the space."
            >
              <select
                id="role"
                aria-describedby="role-help"
                className={fieldClass}
                value={role}
                onChange={(e) => setRole(e.target.value as Role)}
              >
                {Object.entries(ROLES).map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                ))}
              </select>
            </Field>
          )}
        </FormColumn>
      )}
    </FormPage>
  );
}
