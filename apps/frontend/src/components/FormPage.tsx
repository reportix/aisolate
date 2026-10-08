import { TableSkeleton } from "@aisolate/ui";
import type { ReactNode } from "react";
import { PageHeader } from "./PageHeader";
import { SaveBanner } from "./SaveBanner";

type Props = {
  title: string;
  description: ReactNode;
  actions?: ReactNode;
  /** A failed load or save, or the row the page cannot find. */
  banner: string | null;
  /** The form, once what it edits has loaded; a skeleton stands in until then, unless a banner does. */
  children: ReactNode;
};

/** A page that edits one part of a space or an airlock: its header, a banner, then the form. */
export function FormPage({ title, description, actions, banner, children }: Props) {
  return (
    <div className="grid gap-6">
      <PageHeader title={title} description={description} actions={actions} />
      {banner && <SaveBanner message={banner} />}
      {children || (!banner && <TableSkeleton rows={2} cols={2} />)}
    </div>
  );
}
