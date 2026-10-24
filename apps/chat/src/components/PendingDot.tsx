/** A pulsing dot for "still running"; stays still when the user prefers reduced motion. */
export function PendingDot() {
  return (
    <span
      aria-hidden="true"
      className="inline-block h-2 w-2 animate-pulse rounded-full bg-purple"
    />
  );
}
