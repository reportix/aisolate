"use client";

import { useParams } from "next/navigation";
import { LlmForm } from "@/components/LlmForm";
import { api } from "@/lib/api";
import { useGlobalForm } from "@/lib/globals";
import { RowFormPage } from "./RowFormPage";

/** One of AIsolate's LLMs: a new one at /llms/new, the LLM `llmId` at its edit page. */
export default function GlobalLlmFormPage() {
  const { llmId } = useParams<{ llmId?: string }>();
  const state = useGlobalForm(api.llms, "llms", "llms", llmId, {
    id: "",
    provider: "",
    url: "",
    model: "",
  });

  return (
    <RowFormPage
      noun="model"
      id={llmId}
      description="A model every space's airlocks may name by id."
      state={state}
      Form={LlmForm}
    />
  );
}
