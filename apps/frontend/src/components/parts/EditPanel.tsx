"use client";

import { tableFrame } from "@aisolate/ui";
import type { ComponentType } from "react";
import type { RowFormProps } from "@/components/FormProblems";
import { SaveBanner } from "@/components/SaveBanner";
import type { RowFormState } from "@/lib/scope";

type Props<R> = {
  /** What a row is, "model" or "data source": the title and the primary say it. */
  noun: string;
  /** The row's form state, from the list's hook: its rows, the row's place, the save. */
  state: RowFormState<R>;
  /** The form of one row: LlmForm, DataSourceForm. */
  Form: ComponentType<RowFormProps<R>>;
};

/** A row's form above the list, in the frame View opens in: Save puts the row back, Cancel closes it. */
export function EditPanel<R extends { id: string }>({ noun, state, Form }: Props<R>) {
  const { rows, index, banner, errors, busy, submit, cancel } = state;
  return (
    <section aria-label={`Edit ${noun}`} className={`${tableFrame} grid gap-4 p-5`}>
      <h2 className="text-base font-semibold tracking-snug">Edit {noun}</h2>
      {banner && <SaveBanner message={banner} />}
      {rows && index >= 0 && (
        <Form
          rows={rows}
          index={index}
          edit
          label={`Save ${noun}`}
          errors={errors}
          busy={busy}
          onSubmit={submit}
          onCancel={cancel}
        />
      )}
    </section>
  );
}
