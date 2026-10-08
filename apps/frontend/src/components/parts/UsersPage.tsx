"use client";

import { Section, buttonClass } from "@aisolate/ui";
import Link from "next/link";
import { useState } from "react";
import { PageHeader } from "@/components/PageHeader";
import { SaveBanner } from "@/components/SaveBanner";
import { UserTable, type UserRow } from "@/components/UserTable";
import { api } from "@/lib/api";
import { removeMember, removeUser, sameEmail, withRole } from "@/lib/people";
import { useSave, useScope, type Part } from "@/lib/scope";
import type { Member, Role, User, Users } from "@/lib/types";
import { useLoad } from "@/lib/useLoad";

const unnamed = (email: string, role: Role | null = null): User => ({
  email,
  name: null,
  username: null,
  in_directory: null,
  role,
});

/**
 * Who may ask, as GitLab lists a project's members: a space's users with their role, or an
 * airlock's own and those it inherits from the space, each by name (from the directory) and sign-in
 * e-mail. People are added on Add users; the admin changes a role in its row and removes one here,
 * one click.
 */
export default function UsersPage() {
  const {
    spaceId,
    airlockId,
    space,
    airlock,
    owner,
    name,
    base,
    banner,
    setBanner,
    write,
    setOwner,
    mayChange,
  } = useScope();
  const [reads, setReads] = useState(0);
  const listed = useLoad<Users>(
    `${spaceId}/${airlockId}/users/${reads}`,
    (t) => (airlockId ? api.airlocks.users(t, spaceId, airlockId) : api.spaces.users(t, spaceId)),
    "Could not name the users",
  );
  const { errors, busy, save } = useSave(setBanner);
  // No form here: a refused remove's field errors read in the banner.
  const problem = banner ?? (errors.join(", ") || listed.banner);

  // Without the directory's answer the e-mails still list, unnamed.
  const users = listed.data;
  const members: Member[] = space?.members ?? [];
  // An airlock's own users; a space's members are all its own.
  const own = airlockId ? (airlock?.users ?? []) : members.map((m) => m.email);
  const rows: UserRow[] | null = users
    ? [...users.users, ...users.inherited.map((u) => ({ ...u, inherited: true }))]
    : owner && space
      ? [
          ...(airlockId
            ? own.map((e) => unnamed(e))
            : members.map((m) => unnamed(m.email, m.role))),
          ...(airlockId ? members : [])
            .filter((m) => !own.some((o) => sameEmail(o, m.email)))
            .map((m) => ({ ...unnamed(m.email), inherited: true })),
        ]
      : null;

  /** The whole owner goes back with the one change, as read (If-Match). */
  const change = (part: Part) =>
    void save(
      (t) => write(t, part),
      (saved) => {
        setOwner(saved);
        setReads((n) => n + 1);
      },
    );
  const remove = (email: string) =>
    change(
      airlockId ? { users: removeUser(own, email) } : { members: removeMember(members, email) },
    );

  return (
    <div className="grid gap-6">
      <PageHeader
        title="Users"
        description={
          airlockId
            ? `Who may ask ${name}: its own users and the space's.`
            : "Everyone listed may ask every airlock of this space; a developer also edits its airlocks and deploys into it. An airlock can add users of its own."
        }
      />
      {problem && <SaveBanner message={problem} />}
      {(owner || !banner) && (
        <Section
          action={
            mayChange &&
            owner && (
              <Link href={`${base}/users/add`} className={buttonClass()}>
                Add users
              </Link>
            )
          }
          loading={!rows && { rows: 3, cols: 2 }}
        >
          {rows && (
            <UserTable
              rows={rows}
              named={users?.directory ?? false}
              source={
                airlockId
                  ? { name: space?.name ?? spaceId, href: `/spaces/${spaceId}/users` }
                  : undefined
              }
              role={
                airlockId
                  ? undefined
                  : {
                      busy,
                      onChange: mayChange
                        ? (email, role) => change({ members: withRole(members, email, role) })
                        : undefined,
                    }
              }
              remove={mayChange ? { busy, onRemove: remove } : undefined}
            />
          )}
        </Section>
      )}
    </div>
  );
}
