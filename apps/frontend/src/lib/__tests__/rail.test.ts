import { describe, expect, it } from "vitest";
import { auditsOnly, railOf, trailOf } from "../rail";
import type { Overview } from "../useOverview";
import { airlockOf, spaceOf } from "./airlock.fixture";

const space = (id: string, name = id) => spaceOf({ id, name });

const airlock = (id: string, spaceId: string, name = id) =>
  airlockOf({ id, space_id: spaceId, name });

const SPACE = "/spaces/acme";
const AIRLOCK = `${SPACE}/airlocks/hr`;

describe("railOf", () => {
  it.each([
    ["/", "top", "dashboard"],
    ["/spaces", "top", "spaces"],
    ["/spaces/new", "top", "spaces"],
    [SPACE, "space", "space"],
    [`${SPACE}/users/add`, "space", "users"],
    [`${SPACE}/airlocks`, "space", "airlocks"],
    [`${SPACE}/airlocks/new`, "space", "airlocks"],
    [AIRLOCK, "airlock", "airlock"],
    // The airlock's own tabs light its row, as its page does.
    [`${AIRLOCK}/tools`, "airlock", "airlock"],
    [`${AIRLOCK}/users/add`, "airlock", "users"],
  ])("givenPath_%s_whenRailOf_thenContext_%s_andOnlyRow_%s_isLit", (path, context, litKey) => {
    // Arrange / Act
    const rail = railOf(path, null);

    // Assert
    expect(rail.context).toBe(context);
    expect(rail.rows.filter((r) => r.lit).map((r) => r.key)).toEqual([litKey]);
  });

  it.each([
    [
      "/",
      null,
      [
        ["dashboard", "/", "Dashboard", "home"],
        ["spaces", "/spaces", "Spaces", "spaces"],
      ],
    ],
    [
      SPACE,
      "Space",
      [
        ["space", SPACE, "acme", null],
        ["airlocks", `${SPACE}/airlocks`, "Airlocks", "airlocks"],
        ["users", `${SPACE}/users`, "Users", "users"],
        ["llms", `${SPACE}/llms`, "Models", "llm"],
        ["data-sources", `${SPACE}/data-sources`, "Data sources", "data-sources"],
      ],
    ],
    [
      AIRLOCK,
      "Airlock",
      [
        ["airlock", AIRLOCK, "hr", null],
        ["users", `${AIRLOCK}/users`, "Users", "users"],
        ["llms", `${AIRLOCK}/llms`, "Models", "llm"],
        ["data-sources", `${AIRLOCK}/data-sources`, "Data sources", "data-sources"],
      ],
    ],
  ])(
    "givenPath_%s_whenRailOf_thenLabel_%s_andTheRowsLinkToTheContextsPages",
    (path, label, rows) => {
      // Arrange / Act
      const rail = railOf(path, null);

      // Assert
      expect(rail.label).toBe(label);
      expect(rail.rows.map((r) => [r.key, r.href, r.label, r.icon])).toEqual(rows);
    },
  );

  it("givenOverview_whenRailOf_thenEntityRowCarriesTheName", () => {
    // Arrange: both spaces hold an airlock `hr`; only acme's names this rail
    const overview: Overview = {
      spaces: [space("acme", "Acme"), space("finance", "Finance")],
      airlocks: [airlock("hr", "finance", "Finance HR"), airlock("hr", "acme", "HR")],
    };

    // Act
    const inSpace = railOf(SPACE, overview).rows[0];
    const inAirlock = railOf(AIRLOCK, overview).rows[0];

    // Assert
    expect(inSpace.entity).toEqual({ id: "acme", name: "Acme" });
    expect(inSpace.label).toBe("Acme");
    expect(inAirlock.entity).toEqual({ id: "hr", name: "HR" });
    expect(inAirlock.label).toBe("HR");
  });
});

describe("trailOf", () => {
  const overview: Overview = {
    spaces: [space("acme", "Acme")],
    airlocks: [airlock("hr", "acme", "HR")],
  };
  const S = ["Spaces", "/spaces"];
  const R = ["Acme", SPACE];
  const A = ["Airlocks", `${SPACE}/airlocks`];
  const H = ["HR", AIRLOCK];
  const M = ["Monitoring", "/monitoring"];

  it.each([
    ["/", [["Dashboard"]], true],
    ["/spaces", [["Spaces"]], false],
    ["/spaces/new", [S, ["New space"]], false],
    [SPACE, [S, ["Acme"]], false],
    [`${SPACE}/airlocks`, [S, R, ["Airlocks"]], false],
    [`${SPACE}/airlocks/new`, [S, R, A, ["New airlock"]], false],
    [`${AIRLOCK}/users`, [S, R, A, H, ["Users"]], false],
    [`${AIRLOCK}/details`, [S, R, A, H, ["Details"]], false],
    [`${AIRLOCK}/tools`, [S, R, A, H, ["Tools"]], false],
    [`${SPACE}/users/add`, [S, R, ["Users", `${SPACE}/users`], ["Add users"]], false],
    [`${SPACE}/llms/new`, [S, R, ["Models", `${SPACE}/llms`], ["Add model"]], false],
    [
      `${AIRLOCK}/llms/granite/edit`,
      [S, R, A, H, ["Models", `${AIRLOCK}/llms`], ["Edit model"]],
      false,
    ],
    ["/llms", [["Models"]], false],
    ["/llms/new", [["Models", "/llms"], ["Add model"]], false],
    ["/llms/granite/edit", [["Models", "/llms"], ["Edit model"]], false],
    ["/data-sources", [["Data sources"]], false],
    ["/data-sources/new", [["Data sources", "/data-sources"], ["Add data source"]], false],
    [
      `${SPACE}/data-sources/new`,
      [S, R, ["Data sources", `${SPACE}/data-sources`], ["Add data source"]],
      false,
    ],
    [
      `${AIRLOCK}/data-sources/hr_db/edit`,
      [S, R, A, H, ["Data sources", `${AIRLOCK}/data-sources`], ["Edit data source"]],
      false,
    ],
    ["/monitoring", [M, ["Tracing"]], false],
    ["/monitoring/metrics", [M, ["Metrics"]], false],
    [
      "/monitoring/traces/946b9e0f36ba5d0ea3ce929d0e0e4736",
      [M, ["Tracing", "/monitoring"], ["Trace 946b9e0f…4736"]],
      false,
    ],
    ["/audit", [["Audit"]], false],
    ["/audit/1042", [["Audit", "/audit"], ["Event 1042"]], false],
  ])("givenPath_%s_whenTrailOf_thenItsCrumbs", (path, crumbs, heading) => {
    // Arrange / Act
    const trail = trailOf(path, overview);

    // Assert
    expect(trail.crumbs.map((c) => (c.href ? [c.label, c.href] : [c.label]))).toEqual(crumbs);
    expect(trail.heading).toBe(heading);
  });

  it("givenNoOverview_whenTrailOf_thenTheCrumbsCarryTheIds", () => {
    // Arrange / Act: the overview has not arrived yet
    const trail = trailOf(`${AIRLOCK}/users`, null);

    // Assert
    expect(trail.crumbs.map((c) => c.label)).toEqual(["Spaces", "acme", "Airlocks", "hr", "Users"]);
  });
});

describe("the top rail by role", () => {
  const ADMIN = { admin: true, auditor: true, developer: false, monitoring: true };
  const DEVELOPER = { admin: false, auditor: false, developer: true, monitoring: true };
  const AUDITOR = { admin: false, auditor: true, developer: false, monitoring: false };
  const USER = { admin: false, auditor: false, developer: false, monitoring: false };
  const ALL = ["dashboard", "spaces", "llms", "data-sources", "monitoring", "audit"];

  it.each([
    ["Admin", ADMIN, ALL],
    ["Developer", DEVELOPER, ALL],
    ["AuditorAndDeveloper", { ...DEVELOPER, auditor: true }, ALL],
    ["AuditorOnly", AUDITOR, ["audit"]],
    ["UserOnly", USER, ["dashboard", "spaces"]],
  ])("given%s_whenTheTopRailIsShown_thenOnlyTheRowsTheyMayUse", (_, viewer, keys) => {
    // Arrange / Act
    const rail = railOf("/", null, viewer);

    // Assert
    expect(rail.rows.map((r) => r.key)).toEqual(keys);
  });

  it.each([
    ["Admin", ADMIN, false],
    ["Developer", DEVELOPER, false],
    ["AuditorAndDeveloper", { ...DEVELOPER, auditor: true }, false],
    ["AuditorOnly", AUDITOR, true],
    ["UserOnly", USER, false],
  ])("given%s_whenSignedIn_thenLandsOnTheAuditOnlyAsAnAuditorAlone", (_, viewer, expected) => {
    // Arrange / Act
    const only = auditsOnly(viewer);

    // Assert
    expect(only).toBe(expected);
  });

  it.each([
    ["/audit", "audit"],
    ["/audit/1042", "audit"],
    ["/llms/granite/edit", "llms"],
    ["/data-sources/new", "data-sources"],
    ["/monitoring", "monitoring"],
  ])("givenPath_%s_whenRailOf_thenOnly_%s_isLit", (path, key) => {
    // Arrange / Act
    const rail = railOf(path, null, ADMIN);

    // Assert
    expect(rail.context).toBe("top");
    expect(rail.rows.filter((r) => r.lit).map((r) => r.key)).toEqual([key]);
  });
});
