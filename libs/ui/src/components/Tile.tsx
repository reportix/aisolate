import { cx } from "../cx";
import { initialOf } from "../lib/avatar";
import { tileHue } from "../lib/tile";

type Props = {
  id: string;
  name: string | null | undefined;
  size?: "sm" | "md";
  className?: string;
};

const SIZE = { sm: "size-6 rounded text-xs", md: "size-12 rounded-lg text-xl" };

/** The entity's initial on a near-gray wash whose faint hue comes from its id: a ground, never an accent. */
export function Tile({ id, name, size = "md", className }: Props) {
  const hue = tileHue(id);
  return (
    <span
      aria-hidden="true"
      className={cx(
        "inline-flex shrink-0 items-center justify-center font-semibold leading-none select-none",
        SIZE[size],
        className,
      )}
      // GitLab's identicon ring: inset, so the tile keeps its size; the initial's hue, faint.
      style={{
        backgroundColor: `oklch(95% 0.01 ${hue})`,
        color: `oklch(40% 0.03 ${hue})`,
        boxShadow: `inset 0 0 0 1px oklch(40% 0.03 ${hue} / 0.16)`,
      }}
    >
      {initialOf(name)}
    </span>
  );
}
