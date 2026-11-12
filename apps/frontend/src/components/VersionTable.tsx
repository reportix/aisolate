import { EmptyRow, Table, Td, Th, Tr, rowLinkClass } from "@aisolate/ui";
import Link from "next/link";
import type { Airlock, AirlockVersion, Space } from "@/lib/types";
import { byVersionName, mainLlmOf } from "@/lib/version";

/** "…/versions/v1/chat": the host and airlock part is the same on every row. */
const shortPath = (endpoint: string) => {
  const at = endpoint.indexOf("/versions/");
  return at < 0 ? endpoint : `…${endpoint.slice(at)}`;
};

type Props = {
  space: Space;
  airlock: Airlock;
  versions: AirlockVersion[];
};

/** An airlock's versions in a Section's frame, by name: endpoint, model and tools of each. */
export function VersionTable({ space, airlock, versions }: Props) {
  return (
    <Table>
      <thead>
        <tr>
          <Th>Version</Th>
          <Th>Description</Th>
          <Th>Endpoint</Th>
          <Th>Model</Th>
          <Th>Tools</Th>
        </tr>
      </thead>
      <tbody>
        {versions.length === 0 && <EmptyRow cols={5}>There are no versions yet.</EmptyRow>}
        {[...versions]
          .sort((a, b) => byVersionName(a.version, b.version))
          .map((v) => (
            <Tr key={v.version} link>
              <Td>
                <Link
                  href={`/spaces/${space.id}/airlocks/${airlock.id}/versions/${v.version}`}
                  className={rowLinkClass}
                >
                  {v.version}
                </Link>
              </Td>
              <Td className="max-w-md truncate" title={v.description || undefined}>
                {v.description || "—"}
              </Td>
              <Td title={v.endpoint}>{shortPath(v.endpoint)}</Td>
              {/* A version always names a model; "none" means its id resolves to nothing it sees. */}
              <Td>{mainLlmOf(v) ?? <span className="text-ink-tertiary">none</span>}</Td>
              <Td>{v.form.tools.length}</Td>
            </Tr>
          ))}
      </tbody>
    </Table>
  );
}
