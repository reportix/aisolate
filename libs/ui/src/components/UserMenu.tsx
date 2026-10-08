"use client";

import { useId } from "react";
import { cx } from "../cx";
import { Avatar } from "./Avatar";
import { LogOutIcon } from "./icons";

type Props = {
  name: string;
  email?: string | null;
  onSignOut: () => void;
};

/**
 * The signed-in user at the right end of the top bar: the avatar opens a native
 * popover (light dismiss, Escape and the top layer come from the browser): name, email, Sign out.
 */
export function UserMenu({ name, email, onSignOut }: Props) {
  const panelId = useId();
  return (
    <>
      <button
        type="button"
        popoverTarget={panelId}
        aria-label={`Account: ${name}`}
        className="flex items-center rounded-full transition-shadow duration-150 hover:ring-2 hover:ring-pen/20"
      >
        <Avatar name={name} />
      </button>
      <div
        id={panelId}
        popover="auto"
        className={cx(
          "fixed inset-auto top-13 right-4 m-0 w-56 overflow-visible rounded-lg border border-hairline bg-paper p-0 text-ink shadow-overlay",
          // The caret under the avatar, as IntelliJ's account popup: a rotated square whose top-left
          // corner pokes above the panel and paints over its top border.
          "before:absolute before:-top-1.5 before:right-2 before:size-3 before:rotate-45 before:border-t before:border-l before:border-hairline before:bg-paper",
        )}
      >
        <div className="px-4 py-3">
          <p className="truncate text-sm font-semibold">{name}</p>
          {email && <p className="truncate text-xs text-ink-tertiary">{email}</p>}
        </div>
        <div className="border-t border-hairline p-1">
          <button
            type="button"
            onClick={onSignOut}
            className="flex w-full items-center gap-2 rounded px-3 py-2 text-sm text-ink-secondary transition-colors duration-150 hover:bg-desk hover:text-ink"
          >
            <LogOutIcon size={16} />
            Sign out
          </button>
        </div>
      </div>
    </>
  );
}
