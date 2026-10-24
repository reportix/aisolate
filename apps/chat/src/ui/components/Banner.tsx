type Props = { message: string; actionLabel?: string; onAction?: () => void };

/** Error banner. The only place red is allowed: errors are not verdicts. */
export function Banner({ message, actionLabel, onAction }: Props) {
  return (
    <div
      role="alert"
      className="flex flex-wrap items-center justify-between gap-2 rounded-2xl border border-error-border bg-error-wash px-4 py-3 text-sm text-error"
    >
      <span>
        <span aria-hidden="true">✗ </span>
        {message}
      </span>
      {onAction && actionLabel && (
        <button
          type="button"
          onClick={onAction}
          className="rounded-lg border border-error-border bg-paper px-3 py-1 text-xs font-medium text-error hover:bg-error-wash"
        >
          {actionLabel}
        </button>
      )}
    </div>
  );
}
