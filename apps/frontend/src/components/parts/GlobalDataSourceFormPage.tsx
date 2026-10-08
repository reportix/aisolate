"use client";

import { useParams } from "next/navigation";
import { DataSourceForm } from "@/components/DataSourceForm";
import { api } from "@/lib/api";
import { useGlobalForm } from "@/lib/globals";
import type { DataSourceInput } from "@/lib/types";
import { RowFormPage } from "./RowFormPage";

/** One of AIsolate's data sources: a new one at /data-sources/new, the row `dsId` at its edit page. */
export default function GlobalDataSourceFormPage() {
  const { dsId } = useParams<{ dsId?: string }>();
  const state = useGlobalForm<DataSourceInput>(
    api.dataSources,
    "data-sources",
    "data_sources",
    dsId,
    { id: "", url: "", secret_ref: "" },
  );

  return (
    <RowFormPage
      noun="data source"
      id={dsId}
      description="A database the code of every space's airlocks may read, by id."
      state={state}
      Form={DataSourceForm}
    />
  );
}
