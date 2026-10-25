import { createElement, type FunctionComponent } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import type { RowFormState } from "@/lib/scope";
import type { LlmInput } from "@/lib/types";
import { LlmForm } from "../LlmForm";
import { EditPanel as Generic } from "../parts/EditPanel";

// createElement cannot infer a generic component's type parameter: pin it to the model's row.
const EditPanel = Generic as FunctionComponent<{
  noun: string;
  state: RowFormState<LlmInput>;
  Form: typeof LlmForm;
}>;

const GPT: LlmInput = {
  id: "gpt",
  provider: "openai",
  url: "https://api.openai.com/v1",
  model: "gpt-4o-mini",
  secret_ref: "aisolate-llm-gpt-a1b2c3",
};

const stateOf = (banner: string | null): RowFormState<LlmInput> => ({
  rows: [GPT],
  index: 0,
  banner,
  errors: [],
  busy: false,
  submit: () => undefined,
  cancel: () => undefined,
});

describe("EditPanel", () => {
  it("givenASaveRefusedOutright_whenRendered_thenTheBannerShowsInThePanel", () => {
    // Arrange
    const state = stateOf("Could not save");

    // Act
    const html = renderToStaticMarkup(
      createElement(EditPanel, { noun: "model", state, Form: LlmForm }),
    );

    // Assert
    expect(html).toContain("Could not save");
  });
});
