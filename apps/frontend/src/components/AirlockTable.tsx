import { EmptyRow, Table, Td, Th, Tr, rowLinkClass } from "@aisolate/ui";
import Link from "next/link";
import type { Airlock, Space } from "@/lib/types";

type Props = {
  airlocks: Airlock[];
  /** Given when the list spans spaces (the dashboard): Space takes Description's column. */
  spaces?: Space[];
};

/** Every list of airlocks reads the same, inside a Section's frame: name, what or where, how many versions run. */
export function AirlockTable({ airlocks, spaces }: Props) {
  const spaceName = (id: string) => spaces?.find((s) => s.id === id)?.name ?? id;
  return (
    <Table>
      <thead>
        <tr>
          <Th>Name</Th>
          <Th>{spaces ? "Space" : "Description"}</Th>
          <Th>Versions</Th>
        </tr>
      </thead>
      <tbody>
        {airlocks.length === 0 && <EmptyRow cols={3}>There are no airlocks yet.</EmptyRow>}
        {airlocks.map((a) => (
          <Tr key={`${a.space_id}/${a.id}`} link>
            <Td>
              <Link href={`/spaces/${a.space_id}/airlocks/${a.id}`} className={rowLinkClass}>
                {a.name}
              </Link>
            </Td>
            {spaces ? (
              <Td>
                <Link href={`/spaces/${a.space_id}`} className="underline-offset-2 hover:underline">
                  {spaceName(a.space_id)}
                </Link>
              </Td>
            ) : (
              <Td className="max-w-md truncate" title={a.description || undefined}>
                {a.description || "—"}
              </Td>
            )}
            <Td>{a.versions}</Td>
          </Tr>
        ))}
      </tbody>
    </Table>
  );
}
