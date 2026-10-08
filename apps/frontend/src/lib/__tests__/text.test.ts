import { describe, expect, it } from "vitest";
import { leadOf, number, shortId } from "../text";

describe("leadOf", () => {
  it.each([
    [
      "a colon before the entity list",
      "Read the asking user's own records of one entity: hr.compensation (employee_id, iban). Use it for me.",
      "Read the asking user's own records of one entity",
      true,
    ],
    [
      "a first sentence",
      "Totals over the rows the user may read. Groups smaller than three are not returned.",
      "Totals over the rows the user may read",
      true,
    ],
    [
      "one sentence with its period",
      "Tells the model today's date.",
      "Tells the model today's date",
      false,
    ],
    ["a dotted name, not a sentence end", "Reads hr.leave", "Reads hr.leave", false],
  ])("givenDescription_%s_whenLeadOf_thenLeadAndMore", (_, text, lead, more) => {
    // Arrange / Act
    const result = leadOf(text);

    // Assert
    expect(result).toEqual({ lead, more });
  });
});

describe("shortId", () => {
  it.each([
    ["a uuid", "ff802241-08d7-4163-a06c-56354862897b", "ff802241…897b"],
    ["a trace id", "946b9e0f36ba5d0ea3ce929d0e0e4736", "946b9e0f…4736"],
    ["seventeen characters", "0123456789abcdefg", "01234567…defg"],
    ["sixteen characters", "0123456789abcdef", "0123456789abcdef"],
    ["a short name", "acme-chat", "acme-chat"],
  ])("givenAnId_%s_whenShortened_thenItsEndsOrItWhole", (_, id, short) => {
    // Arrange / Act
    const result = shortId(id);

    // Assert
    expect(result).toBe(short);
  });
});

describe("number", () => {
  it.each([
    [0, "0"],
    [1840, "1,840"],
    [1234567, "1,234,567"],
  ])("givenTheCount_%d_whenFormatted_thenItsThousandsSeparated", (n, text) => {
    // Arrange / Act
    const result = number(n);

    // Assert
    expect(result).toBe(text);
  });
});
