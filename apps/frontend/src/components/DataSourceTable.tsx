import { EmptyRow, Table, Td, Th, Tr } from "@aisolate/ui";
import type { ReactNode } from "react";
import type { DataSource } from "@/lib/types";
import { RowActions, type Actions } from "./RowActions";

/** What View shows of a data source: its fields, and its Secret's two keys. */
export const dataSourceView = (row: DataSource) => ({
  title: "View data source",
  fields: [
    { label: "Id", value: row.id },
    { label: "URL", value: row.url },
  ],
  secretRef: row.secret_ref || null,
  keys: [
    { name: "username", label: "Username" },
    { name: "password", label: "Password" },
  ],
});

type Props<R> = {
  rows: R[];
  /** Given on every list page: View on each row; its `edit` on the rows `editable` allows. */
  actions?: Actions;
  editable?: (row: R) => boolean;
  /** Given where rows of several levels meet: the Source column, "Direct" or "Inherited from …". */
  source?: (row: R) => ReactNode;
  /** The one quiet row when there are none. */
  empty?: string;
};

/** Data sources by id, as AIsolate, a space and an airlock list theirs; read-only without `actions`. */
export function DataSourceTable<R extends DataSource>({
  rows,
  actions,
  editable = () => true,
  source,
  empty = "There are no data sources yet.",
}: Props<R>) {
  const cols = 3 + [source, actions].filter(Boolean).length;
  return (
    <Table>
      <thead>
        <tr>
          <Th>Id</Th>
          <Th>URL</Th>
          {/* The API never returns a user; it names the Secret that holds it. */}
          <Th>Credentials</Th>
          {source && <Th>Source</Th>}
          {actions && <Th className="text-right">Actions</Th>}
        </tr>
      </thead>
      <tbody>
        {rows.length === 0 && <EmptyRow cols={cols}>{empty}</EmptyRow>}
        {rows.map((d) => (
          <Tr key={d.id}>
            <Td>{d.id}</Td>
            <Td>{d.url}</Td>
            <Td>
              {d.secret_ref ? "Kept as a Secret" : <span className="text-ink-tertiary">none</span>}
            </Td>
            {source && <Td className="font-normal">{source(d)}</Td>}
            {actions && (
              <Td className="text-right">
                {/* An inherited row is viewed here and edited where it is defined. */}
                <RowActions
                  actions={editable(d) ? actions : { onView: actions.onView }}
                  what={{ noun: "data source", id: d.id }}
                />
              </Td>
            )}
          </Tr>
        ))}
      </tbody>
    </Table>
  );
}
