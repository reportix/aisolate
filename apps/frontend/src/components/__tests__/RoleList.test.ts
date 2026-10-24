import { describe, expect, it } from "vitest";
import { addItem } from "../RoleList";

describe("RoleList addItem", () => {
  it("givenPaddedRole_whenAdded_thenTrimmedAndAppended", () => {
    // Arrange
    const list = ["a"];

    // Act
    const r = addItem(list, "  hr-staff ");

    // Assert
    expect(r).toEqual({ list: ["a", "hr-staff"], problem: null });
  });

  it.each([
    ["*", "* is no role"],
    ["two words", "no whitespace"],
  ])("givenRole%s_whenAdded_thenRefusedWithMessage", (raw, message) => {
    // Arrange
    const list = ["a"];

    // Act
    const r = addItem(list, raw);

    // Assert
    expect(r.list).toBe(list);
    expect(r.problem).toContain(message);
  });

  it("givenListedRoleOrBlank_whenAdded_thenListUnchanged", () => {
    // Arrange
    const list = ["a"];

    // Act
    const same = addItem(list, "a");
    const blank = addItem(list, "  ");

    // Assert
    expect(same).toEqual({ list: ["a"], problem: null });
    expect(blank.list).toBe(list);
  });
});
