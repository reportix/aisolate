"use client";

import { useId } from "react";
import { Avatar } from "./Avatar";

type Props = {
  name: string;
  email?: string | null;
  onOpenSettings: () => void;
  onSignOut: () => void;
  /** Avatar only, for the collapsed sidebar rail. */
  compact?: boolean;
};

/**
 * The signed-in user at the foot of the sidebar: opens a native popover upward (light dismiss,
 * Escape and the top layer come from the browser) with name, email, Settings and Sign out.
 */
export function UserMenu({ name, email, onOpenSettings, onSignOut, compact = false }: Props) {
  const panelId = useId();
  return (
    <>
      <button
        type="button"
        popoverTarget={panelId}
        aria-label={`Account: ${name}`}
        className="flex w-full min-w-0 items-center gap-2.5 rounded-xl px-2 py-2 text-left text-sm text-ink transition-colors hover:bg-sidebar-hover"
      >
        <Avatar name={name} />
        {!compact && <span className="min-w-0 truncate leading-tight">{name}</span>}
      </button>
      <div
        id={panelId}
        popover="auto"
        className="fixed inset-auto bottom-16 left-3 m-0 w-60 rounded-2xl border border-hairline bg-paper p-0 text-ink shadow-[var(--shadow-overlay)]"
      >
        <div className="px-4 py-3">
          <p className="truncate text-sm font-semibold">{name}</p>
          {email && <p className="truncate text-xs text-ink-tertiary">{email}</p>}
        </div>
        <div className="grid gap-0.5 border-t border-hairline p-1">
          <button
            type="button"
            onClick={onOpenSettings}
            className="flex w-full items-center gap-2 rounded-xl px-3 py-2 text-left text-sm text-ink-secondary transition-colors duration-150 hover:bg-desk hover:text-ink"
          >
            <SettingsIcon />
            Settings
          </button>
          <button
            type="button"
            onClick={onSignOut}
            className="flex w-full items-center gap-2 rounded-xl px-3 py-2 text-left text-sm text-ink-secondary transition-colors duration-150 hover:bg-desk hover:text-ink"
          >
            <LogOutIcon />
            Sign out
          </button>
        </div>
      </div>
    </>
  );
}

function LogOutIcon() {
  return (
    <svg
      viewBox="0 0 24 24"
      width={16}
      height={16}
      fill="none"
      stroke="currentColor"
      strokeWidth={1.75}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
      <polyline points="16 17 21 12 16 7" />
      <line x1="21" x2="9" y1="12" y2="12" />
    </svg>
  );
}

/** Lucide's settings gear (MIT), the same stroke as the sign-out icon. */
function SettingsIcon() {
  return (
    <svg
      viewBox="0 0 24 24"
      width={16}
      height={16}
      fill="none"
      stroke="currentColor"
      strokeWidth={1.75}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      <path d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z" />
      <circle cx="12" cy="12" r="3" />
    </svg>
  );
}
