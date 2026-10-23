import { EmptyRow, Table, Td, Th, Tr } from "@aisolate/ui";
import type { ReactNode } from "react";
import type { Llm } from "@/lib/types";
import { providerLabel } from "./LlmForm";
import { RowActions, type Actions } from "./RowActions";

/** What View shows of a model: its fields, and its Secret's one key. */
export const llmView = (row: Llm) => ({
  title: "View model",
  fields: [
    { label: "Id", value: row.id },
    { label: "Provider", value: providerLabel(row.provider) },
    { label: "URL", value: row.url },
    { label: "Model", value: row.model },
  ],
  secretRef: row.secret_ref || null,
  keys: [{ name: "apiKey", label: "API key" }],
});

type Props<R> = {
  rows: R[];
  /** Given on every list page: View on each row; its `edit` on the rows `editable` allows. */
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
          {/* The URL and the key are the View's. */}
          <Th>Id</Th>
          <Th>Provider</Th>
          <Th>Model</Th>
          {source && <Th>Source</Th>}
          {actions && <Th className="text-right">Actions</Th>}
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
              <Td className="text-right">
                {/* An inherited row is viewed here and edited where it is defined. */}
                <RowActions
                  actions={editable(l) ? actions : { onView: actions.onView }}
                  what={{ noun: "model", id: l.id }}
                />
              </Td>
            )}
          </Tr>
        ))}
      </tbody>
    </Table>
  );
}
