"use client";

import type { ComponentType, ReactNode } from "react";
import { FormPage } from "@/components/FormPage";
import type { RowFormProps } from "@/components/FormProblems";
import type { RowFormState } from "@/lib/scope";

type Props<R> = {
  /** What a row is, "model" or "data source": the title, the primary and the not-found say it. */
  noun: string;
  /** The edit page's row; none on the add page. */
  id: string | undefined;
  description: ReactNode;
  /** The rows and their save, from the hook of whoever owns them. */
  state: RowFormState<R>;
  /** The form of one row: LlmForm, DataSourceForm. */
  Form: ComponentType<RowFormProps<R>>;
};

/**
 * The add page of a list of models or data sources, or a row's edit page, whoever owns the list:
 * the form once the rows load, or the banner of a failed load or save, or of a row not among them.
 */
export function RowFormPage<R extends { id: string }>({
  noun,
  id,
  description,
  state,
  Form,
}: Props<R>) {
  const { rows, index, banner, errors, busy, submit, cancel } = state;
  return (
    <FormPage
      title={id ? `Edit ${noun}` : `Add ${noun}`}
      description={description}
      banner={banner ?? (rows && index < 0 ? `No ${noun} named ${id}` : null)}
    >
      {rows && index >= 0 && (
        <Form
          rows={rows}
          index={index}
          edit={Boolean(id)}
          label={id ? `Save ${noun}` : `Add ${noun}`}
          errors={errors}
          busy={busy}
          onSubmit={submit}
          onCancel={cancel}
        />
      )}
    </FormPage>
  );
}
