"use client";

import { useParams } from "next/navigation";
import { DataSourceForm } from "@/components/DataSourceForm";
import { useScopeForm } from "@/lib/scope";
import { RowFormPage } from "./RowFormPage";

/**
 * One data source of a space or an airlock: a new row at data-sources/new, the row `dsId` at its
 * edit page. A save sends the whole owner back with the row added or changed.
 */
export default function DataSourceFormPage() {
  const { dsId } = useParams<{ dsId?: string }>();
  const state = useScopeForm("data-sources", "data_sources", dsId, {
    id: "",
    url: "",
    secret_ref: "",
  });

  return (
    <RowFormPage
      noun="data source"
      id={dsId}
      description={
        state.airlockId
          ? `A database only ${state.name} reads, by id; it replaces the space's or the platform's with the same id.`
          : "A database the code of the space's airlocks reads, by id; it replaces the platform's with the same id."
      }
      state={state}
      Form={DataSourceForm}
    />
  );
}
