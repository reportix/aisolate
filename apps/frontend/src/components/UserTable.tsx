import { Button, EmptyRow, Table, Td, Th, Tile, Tr } from "@aisolate/ui";
import { InheritedFrom } from "./InheritedFrom";
import type { Role, User } from "@/lib/types";

/** GitLab's words for a space's roles. */
export const ROLES: Record<Role, string> = { developer: "Developer", user: "User" };

export type UserRow = User & { inherited?: boolean };

type Props = {
  rows: UserRow[];
  /** With a directory connected: the Name column, from it. */
  named: boolean;
  /**
   * On an airlock's page: the Source column in GitLab's words, "Direct" or "Inherited from"
   * the space, whose name is a chip linking to its Users page.
   */
  source?: { name: string; href: string };
  /** On a space's page: the Role column; `onChange` only for whoever may change it, the admin. */
  role?: { busy: boolean; onChange?: (email: string, role: Role) => void };
  /** Where the page may change them: an own row's Remove, one click. */
  remove?: { busy: boolean; onRemove: (email: string) => void };
};

/** A space's or an airlock's users: who they are, the e-mail they sign in with, where from. */
export function UserTable({ rows, named, source, role, remove }: Props) {
  const cols = 1 + [named, source, role, remove].filter(Boolean).length;
  return (
    <Table>
      <thead>
        <tr>
          {named && <Th>Name</Th>}
          <Th>E-mail</Th>
          {source && <Th>Source</Th>}
          {role && <Th>Role</Th>}
          {remove && (
            <Th>
              <span className="sr-only">Actions</span>
            </Th>
          )}
        </tr>
      </thead>
      <tbody>
        {rows.length === 0 && <EmptyRow cols={cols}>There are no users yet.</EmptyRow>}
        {rows.map((u) => (
          <Tr key={`${u.inherited ? "space" : "own"}:${u.email}`}>
            {named && (
              <Td>
                <span className="flex items-center gap-3">
                  <Tile id={u.email} name={u.name ?? u.email} size="sm" />
                  {u.name}
                  {u.in_directory === false && <NotInDirectory />}
                </span>
              </Td>
            )}
            <Td>
              <span className="flex items-center gap-3">
                {!named && <Tile id={u.email} name={u.email} size="sm" />}
                <span>{u.email}</span>
              </span>
            </Td>
            {source && (
              <Td className="font-normal">
                {u.inherited ? <InheritedFrom {...source} /> : "Direct"}
              </Td>
            )}
            {role && (
              <Td className="font-normal">
                {role.onChange && u.role ? (
                  // 32px with Remove's border, overhanging the cell's padding as Remove does.
                  <select
                    aria-label={`Role of ${u.name ?? u.email}`}
                    className="-my-1.5 inline-flex h-8 items-center rounded border border-hairline-strong bg-paper pl-3 text-sm"
                    value={u.role}
                    disabled={role.busy}
                    onChange={(e) => role.onChange!(u.email, e.target.value as Role)}
                  >
                    {Object.entries(ROLES).map(([value, label]) => (
                      <option key={value} value={value}>
                        {label}
                      </option>
                    ))}
                  </select>
                ) : (
                  u.role && ROLES[u.role]
                )}
              </Td>
            )}
            {remove && (
              <Td className="text-right">
                {!u.inherited && (
                  // The 32px button overhangs the cell's padding, so the row keeps its height.
                  <Button
                    variant="secondary"
                    className="-my-1.5"
                    aria-label={`Remove ${u.name ?? u.email}`}
                    disabled={remove.busy}
                    onClick={() => remove.onRemove(u.email)}
                  >
                    Remove
                  </Button>
                )}
              </Td>
            )}
          </Tr>
        ))}
      </tbody>
    </Table>
  );
}

/** Quiet: the e-mail stays listed and admitted until someone removes it. */
function NotInDirectory() {
  return (
    <span className="rounded-full border border-control px-2 py-0.5 text-xs font-normal text-ink-secondary">
      Not in the directory
    </span>
  );
}
