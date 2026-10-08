import { AirlockIcon, CalendarIcon, RefreshIcon, SpacesIcon } from "@aisolate/ui";
import Link from "next/link";
import { ago, stamp } from "@/lib/time";

export type Dated = {
  created_at: string;
  updated_at: string;
};

/** What the entity belongs to, linked to its page. */
export type Parent = { kind: "Space" | "Airlock"; name: string; href: string };

const LINE = "mt-1.5 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-ink-tertiary";

/** Under an entity page's title: when it was created and last updated, exact on hover, then on its own line what it belongs to. */
export function Dates({ of, parents = [] }: { of: Dated; parents?: Parent[] }) {
  return (
    <>
      <p className={LINE}>
        <span className="flex items-center gap-1.5" title={stamp(of.created_at)}>
          <CalendarIcon size={12} className="shrink-0" />
          Created {ago(of.created_at)}
        </span>
        <span className="flex items-center gap-1.5" title={stamp(of.updated_at)}>
          <RefreshIcon size={12} className="shrink-0" />
          Updated {ago(of.updated_at)}
        </span>
      </p>
      {parents.length > 0 && (
        <p className={LINE}>
          {parents.map((parent) => {
            const ParentIcon = parent.kind === "Airlock" ? AirlockIcon : SpacesIcon;
            return (
              // The icon says the kind; the word stays for hover and screen readers.
              <span key={parent.href} className="flex items-center gap-1.5" title={parent.kind}>
                <ParentIcon size={12} className="shrink-0" />
                <span className="sr-only">{parent.kind}</span>
                <Link
                  href={parent.href}
                  className="text-ink-secondary hover:text-ink hover:underline"
                >
                  {parent.name}
                </Link>
              </span>
            );
          })}
        </p>
      )}
    </>
  );
}
