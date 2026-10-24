"use client";

import {
  Banner,
  EmptyRow,
  Section,
  Table,
  Td,
  Th,
  Tile,
  Tr,
  buttonClass,
  rowLinkClass,
} from "@aisolate/ui";
import Link from "next/link";
import { PageHeader } from "@/components/PageHeader";
import { useSession } from "@/components/Session";
import { useOverview } from "@/lib/useOverview";

export default function SpacesPage() {
  const session = useSession();
  const { data, error } = useOverview();

  return (
    <div className="grid gap-6">
      <PageHeader
        title="Spaces"
        description="Each space holds the airlocks of one team or product, and the roles, models and data sources they share."
      />
      {error && <Banner message={error} />}
      {!error && (
        <Section
          action={
            session.admin && (
              <Link href="/spaces/new" className={buttonClass()}>
                New space
              </Link>
            )
          }
          loading={!data && { rows: 2, cols: 4 }}
        >
          {data && (
            <Table>
              <thead>
                <tr>
                  <Th>Name</Th>
                  <Th>Description</Th>
                  <Th>Airlocks</Th>
                  <Th>Roles</Th>
                </tr>
              </thead>
              <tbody>
                {data.spaces.length === 0 && <EmptyRow cols={4}>There are no spaces yet.</EmptyRow>}
                {data.spaces.map((s) => (
                  <Tr key={s.id} link>
                    <Td>
                      <Link href={`/spaces/${s.id}`} className="flex items-center gap-3">
                        <Tile id={s.id} name={s.name} size="sm" />
                        <span className={rowLinkClass}>{s.name}</span>
                      </Link>
                    </Td>
                    <Td className="max-w-md truncate" title={s.description || undefined}>
                      {s.description || "—"}
                    </Td>
                    <Td>{data.airlocks.filter((a) => a.space_id === s.id).length}</Td>
                    <Td>{s.roles.length}</Td>
                  </Tr>
                ))}
              </tbody>
            </Table>
          )}
        </Section>
      )}
    </div>
  );
}
