import { initialOf } from "../lib/avatar";

/** The user's initial in a 28px circle: "N" for Nora. */
export function Avatar({ name }: { name: string | null | undefined }) {
  return (
    <span
      aria-hidden="true"
      className="inline-flex size-7 shrink-0 items-center justify-center rounded-full bg-pen-wash text-xs leading-none font-semibold text-pen ring-1 ring-pen/20 select-none"
    >
      {initialOf(name)}
    </span>
  );
}
