import { describe, expect, it } from "vitest";
import { notAppliedPath } from "../notice";

describe("notAppliedPath", () => {
  it.each<[boolean, string | undefined, string | null]>([
    [true, "/spaces/acme", null],
    [true, undefined, null],
    [false, "/spaces/acme", "/spaces/acme"],
    [false, undefined, "/spaces/acme/llms/new"],
  ])("givenApplied_%s_andNext_%s_whenSaved_thenNoticeOn_%s", (applied, next, expected) => {
    // Arrange
    const pathname = "/spaces/acme/llms/new";

    // Act
    const path = notAppliedPath(applied, next, pathname);

    // Assert
    expect(path).toBe(expected);
  });
});
