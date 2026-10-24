import { cx } from "../cx";

/** Any bordered panel; padding is the caller's. */
export function cardClass(extra?: string) {
  return cx("rounded-2xl border border-hairline bg-paper", extra);
}
