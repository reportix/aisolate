import { describe, expect, it } from "vitest";
import { validateDataSources, validateLlms, validateName } from "../validate";
import type { DataSourceInput, Llm } from "../types";

/** A row whose credentials a stored Secret holds, so a blank user and password keep it. */
const stored: DataSourceInput = {
  id: "acme_hr",
  url: "jdbc:postgresql://acme-db:5432/acme_hr",
  secret_ref: "hr-acme-ds-acme-hr-x7k2qa",
};

/** A new row: nothing stored yet. */
const typed: DataSourceInput = {
  id: "hr-documents",
  url: "jdbc:postgresql://acme-db:5432/acme_hr",
  secret_ref: "",
  username: "aisolate_context_preparator",
  password: "secret",
};

const granite: Llm = {
  id: "granite",
  provider: "ollama",
  url: "http://ollama:11434",
  model: "granite4.1:8b",
};

const nomic: Llm = { ...granite, id: "nomic", model: "nomic-embed-text" };

describe("validateName", () => {
  it.each<[string, string[]]>([
    ["Acme Financial Group", []],
    ["  ", ["name: required"]],
  ])("givenName_%j_whenValidateName_thenErrors_%j", (name, expected) => {
    // Arrange: the table's name

    // Act
    const errors = validateName(name);

    // Assert
    expect(errors).toEqual(expected);
  });
});

describe("validateLlms", () => {
  it.each<[Llm[], string[]]>([
    // Row by row: any model, no type; an id once per level.
    [[granite, nomic], []],
    [[{ ...granite, id: " " }], ["llms[0].id: required"]],
    [[{ ...granite, id: "Granite 4" }], ["llms[0].id: lowercase letters, digits, _ or -"]],
    [[granite, { ...nomic, id: "granite" }], ["llms[1].id: granite is listed twice"]],
    [
      [{ id: "granite", provider: "", url: " ", model: "" }],
      ["llms[0].provider: required", "llms[0].url: required", "llms[0].model: required"],
    ],
  ])("givenRows_%#_whenValidateLlms_thenErrors", (rows, expected) => {
    // Arrange / Act
    const errors = validateLlms(rows);

    // Assert
    expect(errors).toEqual(expected);
  });
});

describe("validateDataSources", () => {
  it.each<[DataSourceInput[], string[]]>([
    // Row by row; a stored Secret keeps until a credential is typed.
    [[stored, typed], []],
    [[{ ...stored, id: " " }], ["data_sources[0].id: required"]],
    [[{ ...stored, id: "Acme HR" }], ["data_sources[0].id: lowercase letters, digits, _ or -"]],
    [[stored, { ...typed, id: "acme_hr" }], ["data_sources[1].id: acme_hr is listed twice"]],
    [[{ ...typed, username: "", password: "" }], ["data_sources[0].password: required with url"]],
    [[{ ...typed, username: " " }], ["data_sources[0].username: required with password"]],
    [[{ ...stored, username: "hr_reader" }], ["data_sources[0].password: required with url"]],
    [
      [{ id: "", url: "", secret_ref: "" }],
      ["data_sources[0].id: required", "data_sources[0].url: required"],
    ],
  ])("givenRows_%#_whenValidateDataSources_thenErrors", (rows, expected) => {
    // Arrange / Act
    const errors = validateDataSources(rows);

    // Assert
    expect(errors).toEqual(expected);
  });
});
