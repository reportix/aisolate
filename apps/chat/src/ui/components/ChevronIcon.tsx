import { cx } from "../cx";

/** The AIsolate UI sidebar's chevron (Lucide chevron-right), turned down while open. */
export function ChevronIcon({ open, size = 14 }: { open: boolean; size?: number }) {
  return (
    <svg
      viewBox="0 0 24 24"
      width={size}
      height={size}
      fill="none"
      stroke="currentColor"
      strokeWidth={1.75}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      className={cx("shrink-0 transition-transform", open && "rotate-90")}
    >
      <path d="m9 18 6-6-6-6" />
    </svg>
  );
}
