import { EmptyRow, Table, Td, Th, Tr } from "@aisolate/ui";
import type { ReactNode } from "react";
import type { Llm } from "@/lib/types";
import { providerLabel } from "./LlmForm";
import { RowActions, type Actions } from "./RowActions";

type Props<R> = {
  rows: R[];
  /** Given where the page edits its rows: on each row `editable` allows, all without it. */
  actions?: Actions;
  editable?: (row: R) => boolean;
  /** Given where rows of several levels meet: the Source column, "Direct" or "Inherited from …". */
  source?: (row: R) => ReactNode;
};

/** Models by id, as AIsolate, a space and an airlock list theirs; read-only without `actions`. */
export function LlmTable<R extends Llm>({
  rows,
  actions,
  editable = () => true,
  source,
}: Props<R>) {
  const cols = 3 + [source, actions].filter(Boolean).length;
  return (
    <Table>
      <thead>
        <tr>
          <Th>Id</Th>
          <Th>Provider</Th>
          <Th>Model</Th>
          {source && <Th>Source</Th>}
          {actions && (
            <Th>
              <span className="sr-only">Actions</span>
            </Th>
          )}
        </tr>
      </thead>
      <tbody>
        {rows.length === 0 && <EmptyRow cols={cols}>There are no models yet.</EmptyRow>}
        {rows.map((l) => (
          <Tr key={l.id}>
            <Td>{l.id}</Td>
            <Td>{providerLabel(l.provider)}</Td>
            <Td>{l.model}</Td>
            {source && <Td className="font-normal">{source(l)}</Td>}
            {actions && (
              <Td>
                {editable(l) && <RowActions actions={actions} what={{ noun: "model", id: l.id }} />}
              </Td>
            )}
          </Tr>
        ))}
      </tbody>
    </Table>
  );
}
