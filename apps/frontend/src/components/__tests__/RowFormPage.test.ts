import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import type { RowFormState } from "@/lib/scope";
import type { RowFormProps } from "../FormProblems";
import { RowFormPage } from "../parts/RowFormPage";

type Row = { id: string; url: string };

const GRANITE: Row = { id: "granite", url: "http://ollama:11434" };
const BLANK: Row = { id: "", url: "" };

const state = (patch: Partial<RowFormState<Row>> = {}): RowFormState<Row> => ({
  rows: [GRANITE],
  index: 0,
  banner: null,
  errors: [],
  busy: false,
  submit: () => undefined,
  cancel: () => undefined,
  ...patch,
});

/** The page's markup, and the props its form was given when it showed one. */
function page(noun: string, id: string | undefined, shown: RowFormState<Row>) {
  let given: RowFormProps<Row> | null = null;
  const Form = (props: RowFormProps<Row>) => {
    given = props;
    return createElement("form", { "aria-label": props.label });
  };
  const html = renderToStaticMarkup(
    createElement(RowFormPage<Row>, { noun, id, description: "One row.", state: shown, Form }),
  );
  return { html, form: given as RowFormProps<Row> | null };
}

const title = (html: string) => html.match(/<h1[^>]*>([^<]*)/)?.[1] ?? null;
const banner = (html: string) => html.match(/role="alert"[\s\S]*?<\/svg>([^<]*)/)?.[1] ?? null;

describe("RowFormPage", () => {
  it.each([["model"], ["data source"]])(
    "givenAnIdNotAmongTheRows_whenShown_thenNo%sNamedItAndNoForm",
    (noun) => {
      // Arrange
      const shown = state({ index: -1 });

      // Act
      const { html, form } = page(noun, "gpt", shown);

      // Assert
      expect(banner(html)).toBe(`No ${noun} named gpt`);
      expect(form).toBeNull();
    },
  );

  it.each([
    ["model", undefined, [GRANITE, BLANK], 1, "Add model", "Add model", false],
    ["model", "granite", [GRANITE], 0, "Edit model", "Save model", true],
    ["data source", undefined, [GRANITE, BLANK], 1, "Add data source", "Add data source", false],
    ["data source", "granite", [GRANITE], 0, "Edit data source", "Save data source", true],
  ])(
    "givenA%sAt%s_whenShown_thenTheTitleAndTheButtonSayIt",
    (noun, id, rows, index, heading, label, edit) => {
      // Arrange
      const shown = state({ rows, index });

      // Act
      const { html, form } = page(noun, id, shown);

      // Assert
      expect(title(html)).toBe(heading);
      expect(banner(html)).toBeNull();
      expect(form).toMatchObject({ rows, index, label, edit });
    },
  );

  it("givenABannerFromTheLoadOrTheSave_whenShown_thenItRatherThanTheNotFound", () => {
    // Arrange
    const shown = state({ rows: null, index: -1, banner: "Could not load the space" });

    // Act
    const { html, form } = page("model", "gpt", shown);

    // Assert
    expect(banner(html)).toBe("Could not load the space");
    expect(form).toBeNull();
  });

  it("givenTheFormSubmitted_whenItSendsItsRows_thenTheStateSavesThem", () => {
    // Arrange
    const submit = vi.fn();
    const cancel = vi.fn();
    const typed = [{ ...GRANITE, url: "http://ollama:11435" }];
    const { form } = page("model", "granite", state({ submit, cancel, errors: ["url: bad"] }));

    // Act
    form?.onSubmit(typed);
    form?.onCancel();

    // Assert
    expect(form).toMatchObject({ errors: ["url: bad"], busy: false });
    expect(submit).toHaveBeenCalledExactlyOnceWith(typed);
    expect(cancel).toHaveBeenCalledOnce();
  });
});
