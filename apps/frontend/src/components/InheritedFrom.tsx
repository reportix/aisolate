import { chipClass } from "@aisolate/ui";
import Link from "next/link";
import type { Origin } from "@/lib/types";

type Owner = { name: string; href: string };

/** The space or airlock a row comes from: its name as the kit's chip, linking there. */
function OwnerBadge({ name, href }: Owner) {
  return (
    <Link href={href} className={chipClass("hover:border-pen hover:text-pen")}>
      {name}
    </Link>
  );
}

/** GitLab's words for what an airlock takes from further up. */
export function InheritedFrom(owner: Owner) {
  return (
    <span className="inline-flex flex-wrap items-center gap-x-1.5 gap-y-1">
      Inherited from
      <OwnerBadge {...owner} />
    </span>
  );
}

/** Where the levels a list shows live: the airlock's and the space's page of that list, AIsolate's. */
export type Owners = Partial<Record<Origin, Owner>>;

/**
 * A row's Source in GitLab's words: "Direct" for the list's own level, else "Inherited from" and
 * its definer's badge; a row that replaces one of its id further up says whose.
 */
export function Source({
  row,
  own,
  owners,
}: {
  row: { from: Origin; replaces: Origin | null };
  own: Origin;
  owners: Owners;
}) {
  const inherited = row.from !== own;
  // The install's own rows come from the platform itself: no page of a space or airlock to badge.
  const owner = inherited && row.from !== "global" ? owners[row.from] : undefined;
  const replaced =
    row.replaces === "global"
      ? "the platform"
      : row.replaces
        ? owners[row.replaces]?.name
        : undefined;
  return (
    <span className="inline-flex flex-wrap items-center gap-x-1.5 gap-y-1">
      {owner ? (
        <InheritedFrom {...owner} />
      ) : inherited && row.from === "global" ? (
        "Inherited from the platform"
      ) : (
        "Direct"
      )}
      {replaced && <span className="text-xs text-ink-tertiary">replaces {replaced}&apos;s</span>}
    </span>
  );
}
