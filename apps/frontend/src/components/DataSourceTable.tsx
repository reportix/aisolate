import { EmptyRow, Table, Td, Th, Tr } from "@aisolate/ui";
import type { ReactNode } from "react";
import type { DataSource } from "@/lib/types";
import { RowActions, type Actions } from "./RowActions";

type Props<R> = {
  rows: R[];
  /** Given where the page edits its rows: on each row `editable` allows, all without it. */
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
          {actions && (
            <Th>
              <span className="sr-only">Actions</span>
            </Th>
          )}
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
              <Td>
                {editable(d) && (
                  <RowActions actions={actions} what={{ noun: "data source", id: d.id }} />
                )}
              </Td>
            )}
          </Tr>
        ))}
      </tbody>
    </Table>
  );
}
