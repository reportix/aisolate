import { cx } from "../cx";

/** The user's initial on a lavender circle: "N" for Nora. */
export function Avatar({ name, className }: { name: string; className?: string }) {
  return (
    <span
      aria-hidden="true"
      className={cx(
        "inline-flex size-7 shrink-0 select-none items-center justify-center rounded-full bg-mark-wash text-xs font-semibold leading-none text-mark",
        className,
      )}
    >
      {Array.from(name.trim())[0]?.toUpperCase() ?? "?"}
    </span>
  );
}
