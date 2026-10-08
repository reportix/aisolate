import { describe, expect, it } from "vitest";
import { AUDITOR, isAdmin, mayAudit, mayChange } from "../roles";
import type { Space } from "../types";

describe("isAdmin", () => {
  it.each<[string, string[], boolean]>([
    ["AnAdmin", ["employee", "aisolate-admin"], true],
    // The realm's developer role is gone: a space names its developers.
    ["TheDroppedDeveloperRole", ["aisolate-developer", "default-roles-acme"], false],
    ["AnotherAisolateRole", ["aisolate-reader"], false],
  ])("given%s_whenChecked_thenAdminOnlyForTheAdminRole", (_, roles, expected) => {
    // Arrange / Act
    const admin = isAdmin(roles);

    // Assert
    expect(admin).toBe(expected);
  });
});

describe("mayAudit", () => {
  it.each<[string, string[], boolean]>([
    ["AnAdmin", ["aisolate-admin"], true],
    ["AnAuditor", [AUDITOR], true],
    ["AUserWithoutAnAisolateRole", ["default-roles-acme"], false],
  ])("given%s_whenChecked_thenEveryAuditOnlyForAdminsAndAuditors", (_, roles, expected) => {
    // Arrange / Act
    const may = mayAudit(roles);

    // Assert
    expect(may).toBe(expected);
  });
});

describe("mayChange", () => {
  it.each<[Space["caller_role"], boolean, boolean]>([
    ["admin", false, true],
    ["admin", true, true],
    // A developer reads the space's own users, LLM and data sources; its airlocks are theirs
    ["developer", false, false],
    ["developer", true, true],
    [null, true, false],
  ])(
    "givenCallerRole_%s_inAirlock_%s_whenChecked_thenMayChange_%s",
    (role, inAirlock, expected) => {
      // Arrange
      const space = { caller_role: role } as Space;

      // Act
      const may = mayChange(space, inAirlock);

      // Assert
      expect(may).toBe(expected);
    },
  );
});
