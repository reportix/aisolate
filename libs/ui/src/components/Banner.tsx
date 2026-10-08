import { AlertIcon } from "./icons";

type Props = { message: string; actionLabel?: string; onAction?: () => void };

/** Error banner. The only place red is allowed: errors are not verdicts. */
export function Banner({ message, actionLabel, onAction }: Props) {
  return (
    <div
      role="alert"
      className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-error-border bg-error-wash px-4 py-3 text-sm text-error"
    >
      <span className="flex items-center gap-2">
        <AlertIcon size={16} className="shrink-0" />
        {message}
      </span>
      {onAction && actionLabel && (
        <button
          type="button"
          onClick={onAction}
          className="inline-flex h-8 items-center rounded border border-error-border bg-paper px-3 text-sm font-medium text-error hover:bg-error-wash"
        >
          {actionLabel}
        </button>
      )}
    </div>
  );
}
