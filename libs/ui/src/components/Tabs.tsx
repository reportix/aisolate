import type { ComponentType, ReactNode } from "react";
import { cx } from "../cx";

type TabLink = ComponentType<{
  href: string;
  className?: string;
  "aria-current"?: "page";
  children: ReactNode;
}>;

type Props = {
  items: { href: string; label: string }[];
  /** The `href` of the open tab. */
  current: string;
  linkAs: TabLink;
  /** Names the nav landmark. */
  label?: string;
};

/**
 * A row of links on a hairline track; the open one is ink with a 2px pen bar.
 * `linkAs` is Next's `Link`, passed by the app so the kit stays Next-free; a plain `<a>` reloads.
 */
export function Tabs({ items, current, linkAs: Link, label = "Tabs" }: Props) {
  return (
    <nav aria-label={label} className="flex gap-6 border-b border-hairline">
      {items.map((item) => {
        const open = item.href === current;
        return (
          <Link
            key={item.href}
            href={item.href}
            aria-current={open ? "page" : undefined}
            className={cx(
              // -mb-px lays the 2px bar over the 1px track, so the open tab sits on the line.
              "-mb-px border-b-2 py-2 text-sm whitespace-nowrap transition-colors duration-150",
              open
                ? "border-pen font-medium text-ink"
                : "border-transparent text-ink-secondary hover:text-ink",
            )}
          >
            {item.label}
          </Link>
        );
      })}
    </nav>
  );
}
