"use client";

import { useParams } from "next/navigation";
import { LlmForm } from "@/components/LlmForm";
import { useScopeForm } from "@/lib/scope";
import { RowFormPage } from "./RowFormPage";

/**
 * One LLM of a space or an airlock: a new row at llms/new, the row `llmId` at its edit page. A save
 * sends the whole owner back with the row added or changed.
 */
export default function LlmFormPage() {
  const { llmId } = useParams<{ llmId?: string }>();
  const state = useScopeForm("llms", "llms", llmId, { id: "", provider: "", url: "", model: "" });

  return (
    <RowFormPage
      noun="model"
      id={llmId}
      description={
        state.airlockId
          ? `A model only ${state.name} names, by id; it replaces the space's or the platform's with the same id.`
          : "A model the airlocks of the space name, by id; it replaces the platform's with the same id."
      }
      state={state}
      Form={LlmForm}
    />
  );
}
