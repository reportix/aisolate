import type {
  HTMLAttributes,
  MouseEvent,
  ReactNode,
  TdHTMLAttributes,
  ThHTMLAttributes,
} from "react";
import { cx } from "../cx";

/** The frame every Section and the framed people list share: a hairline box, card corners. */
export const tableFrame = "rounded-lg border border-hairline bg-paper";

/**
 * A data table for a Section's frame: the header row on a line, a quiet line between rows and none
 * under the last, which the frame closes; dense, tabular figures. It scrolls sideways inside the frame.
 */
export function Table({ className, children, ...rest }: HTMLAttributes<HTMLTableElement>) {
  return (
    <div className="overflow-x-auto">
      <table
        className={cx(
          "w-full text-sm font-medium text-ink [&_tbody_tr:last-child]:border-b-0 [&_thead_tr]:border-b [&_thead_tr]:border-hairline",
          className,
        )}
        {...rest}
      >
        {children}
      </table>
    </div>
  );
}

export function Th({ className, ...rest }: ThHTMLAttributes<HTMLTableCellElement>) {
  return (
    <th
      scope="col"
      className={cx("px-4 py-2.5 text-left text-[13px] font-medium text-ink-secondary", className)}
      {...rest}
    />
  );
}

export function Td({ className, ...rest }: TdHTMLAttributes<HTMLTableCellElement>) {
  return <td className={cx("px-4 py-3 align-middle whitespace-nowrap", className)} {...rest} />;
}

type TrProps = HTMLAttributes<HTMLTableRowElement> & {
  /** The whole row opens its first link and lights on hover, as GitLab's lists; that link takes `rowLinkClass`. */
  link?: boolean;
};

export function Tr({ className, link, ...rest }: TrProps) {
  const open = (e: MouseEvent<HTMLTableRowElement>) => {
    // A link or button in the row acts on its own; a drag that selected text is no click.
    if ((e.target as Element).closest("a, button, input, select, textarea")) return;
    if (window.getSelection()?.toString()) return;
    e.currentTarget.querySelector("a")?.click();
  };
  return (
    <tr
      className={cx(
        "border-b border-hairline",
        link &&
          "group/row cursor-pointer hover:bg-focus/8 hover:outline hover:-outline-offset-1 hover:outline-focus/40",
        className,
      )}
      onClick={link ? open : undefined}
      {...rest}
    />
  );
}

/** The link that names a clickable row: underlined while the row is hovered. */
export const rowLinkClass = "underline-offset-2 group-hover/row:underline";

/** An empty table's one quiet row, as GitLab's "There are no variables yet.": the heads stay above it. */
export function EmptyRow({ cols, children }: { cols: number; children: ReactNode }) {
  return (
    <tr>
      <td colSpan={cols} className="px-4 py-3 text-sm font-normal text-ink-tertiary">
        {children}
      </td>
    </tr>
  );
}
