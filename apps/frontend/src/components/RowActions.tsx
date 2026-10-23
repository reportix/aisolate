"use client";

import { MoreVerticalIcon, cx } from "@aisolate/ui";
import { useId, useRef, type ToggleEvent } from "react";

/**
 * A row's menu: View on every row; Edit and Delete where the page edits its rows and the row is its
 * own. View and Edit open above the list, one at a time.
 */
export type Actions = {
  onView: (id: string) => void;
  edit?: { onEdit: (id: string) => void; busy: boolean; onRemove: (id: string) => void };
};

const itemClass =
  "flex w-full items-center rounded px-3 py-2 text-left text-sm transition-colors duration-150 hover:bg-desk";

/**
 * The ⋮ at the row's end and its menu, named for screen readers by what the row is: "model granite".
 * A native popover sits in the top layer, so the table's sideways scroll clips nothing; Tab, Escape and
 * light dismiss are the browser's, as UserMenu's.
 */
export function RowActions({
  actions,
  what,
}: {
  actions: Actions;
  what: { noun: string; id: string };
}) {
  const name = `${what.noun} ${what.id}`;
  const panelId = useId();
  const button = useRef<HTMLButtonElement>(null);
  const panel = useRef<HTMLDivElement>(null);
  const close = () => panel.current?.togglePopover(false);

  // Placed under the button's right edge before it paints (anchor positioning is not in every browser
  // yet); a fixed menu would stay put while the page scrolls, so a scroll or resize closes it.
  const place = (e: ToggleEvent<HTMLDivElement>) => {
    const r = button.current?.getBoundingClientRect();
    if (e.newState !== "open" || !r) return;
    const menu = e.currentTarget;
    menu.style.top = `${r.bottom + 4}px`;
    menu.style.right = `${document.documentElement.clientWidth - r.right}px`;
    window.addEventListener("scroll", close, { capture: true, once: true });
    window.addEventListener("resize", close, { once: true });
  };
  // Measurable only once shown: a menu that would run off the bottom opens above the button instead.
  const flip = (e: ToggleEvent<HTMLDivElement>) => {
    const r = button.current?.getBoundingClientRect();
    const menu = e.currentTarget;
    if (e.newState === "open" && r && r.bottom + 4 + menu.offsetHeight > window.innerHeight) {
      menu.style.top = `${r.top - 4 - menu.offsetHeight}px`;
    }
  };

  return (
    <>
      <button
        ref={button}
        type="button"
        popoverTarget={panelId}
        aria-label={`Actions for ${name}`}
        title="Actions"
        className="-my-1 inline-flex size-8 items-center justify-center rounded text-ink-secondary transition-colors duration-150 hover:bg-desk hover:text-ink"
      >
        <MoreVerticalIcon size={16} />
      </button>
      <div
        ref={panel}
        id={panelId}
        popover="auto"
        onBeforeToggle={place}
        onToggle={flip}
        className="fixed inset-auto m-0 w-44 rounded-lg border border-hairline bg-paper p-1 text-ink shadow-overlay"
      >
        <button
          type="button"
          className={itemClass}
          onClick={() => {
            close();
            actions.onView(what.id);
          }}
        >
          View
        </button>
        {actions.edit && (
          <>
            <button
              type="button"
              className={itemClass}
              onClick={() => {
                close();
                actions.edit?.onEdit(what.id);
              }}
            >
              Edit
            </button>
            <button
              type="button"
              disabled={actions.edit.busy}
              className={cx(itemClass, "text-error disabled:opacity-50")}
              onClick={() => {
                close();
                actions.edit?.onRemove(what.id);
              }}
            >
              Delete
            </button>
          </>
        )}
      </div>
    </>
  );
}
