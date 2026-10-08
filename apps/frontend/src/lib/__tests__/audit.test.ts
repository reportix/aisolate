import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api } from "../api";
import {
  chainLine,
  type DataError,
  dataErrorMessage,
  auditFiltersOf,
  auditSearch,
  eventsQuery,
  outcomeLabel,
  traceLink,
} from "../audit";
import { broken, intact, summary } from "./audit.fixture";

vi.mock("../auth", () => ({ forgetAndLogin: vi.fn(async () => {}) }));
vi.mock("../connection", () => ({ reportDown: vi.fn() }));

const NOW = Date.parse("2026-10-19T15:00:00Z");

describe("eventsQuery", () => {
  it.each<[string, Parameters<typeof eventsQuery>, string]>([
    ["AllTimeAndNoSearch", [{ period: "all" }, NOW], ""],
    ["ABlankSearch", [{ q: " " }, NOW], ""],
    ["ATypedSearch", [{ q: " elena@example.com ", period: "all" }, NOW], "?q=elena%40example.com"],
    ["APeriod", [{ period: "10m" }, NOW], "?from=2026-10-19T14%3A50%3A00.000Z"],
    [
      "ASearchAPeriodAndACursor",
      [{ q: "salary", period: "24h" }, NOW, "1041"],
      "?q=salary&from=2026-10-18T15%3A00%3A00.000Z&cursor=1041",
    ],
  ])("given%s_whenQueried_thenOnlyWhatIsSet", (_, args, query) => {
    // Arrange / Act
    const q = eventsQuery(...args);

    // Assert
    expect(q).toBe(query);
  });
});

describe("auditFiltersOf and auditSearch", () => {
  it.each<[string, string, ReturnType<typeof auditFiltersOf>, string]>([
    ["NoQuery", "", { period: "all" }, ""],
    [
      "ATraceViewersLink",
      "?trace_id=4bf92f3577b34da6a3ce929d0e0e4736",
      { q: "4bf92f3577b34da6a3ce929d0e0e4736", period: "all" },
      "?q=4bf92f3577b34da6a3ce929d0e0e4736",
    ],
    [
      "ASearchAndAPeriod",
      "?q=salary&period=1h&x=1",
      { q: "salary", period: "1h" },
      "?q=salary&period=1h",
    ],
    ["AnUnknownPeriod", "?period=2y", { period: "all" }, ""],
    ["AnObjectsOwnName_constructor", "?period=constructor", { period: "all" }, ""],
    ["AnObjectsOwnName_toString", "?period=toString", { period: "all" }, ""],
  ])("givenTheAddress_%s_whenRead_thenItsFiltersAndTheAddressBack", (_, search, filters, back) => {
    // Arrange / Act
    const read = auditFiltersOf(search);

    // Assert
    expect(read).toEqual(filters);
    expect(auditSearch(read)).toBe(back);
  });
});

describe("api.audit.events", () => {
  beforeEach(() => vi.stubEnv("NEXT_PUBLIC_AISOLATE_URL", "http://backend.test"));
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
  });

  it("givenTwoPages_whenTheSecondIsLoaded_thenItAsksWithTheFirstsCursorAndTheSameFilters", async () => {
    // Arrange: the query strings are eventsQuery's own cases; here only that the wrapper uses it
    const pages = [
      { events: [summary], next_cursor: "1041" },
      { events: [{ ...summary, id: 1041 }], next_cursor: null },
    ];
    const fetch = vi.fn(async () => Response.json(pages.shift()));
    vi.stubGlobal("fetch", fetch);
    const filters = { q: "acme", period: "all" as const };

    // Act
    const first = await api.audit.events("token", filters, NOW);
    const second = await api.audit.events("token", filters, NOW, first.next_cursor);

    // Assert
    const urls = fetch.mock.calls.map((c) => (c as unknown as [string])[0]);
    const path = "http://backend.test/v1/audit/events";
    expect(urls).toEqual([
      `${path}${eventsQuery(filters, NOW)}`,
      `${path}${eventsQuery(filters, NOW, "1041")}`,
    ]);
    expect([...first.events, ...second.events].map((e) => e.id)).toEqual([1042, 1041]);
    expect(second.next_cursor).toBeNull();
  });
});

describe("audit words", () => {
  it.each([
    [undefined, "t1", null],
    ["http://jaeger/trace/{trace_id}", null, null],
    ["http://jaeger/trace/{trace_id}", "4bf9", "http://jaeger/trace/4bf9"],
  ])("givenTemplate_%s_andTrace_%s_whenLinked_thenTheTracesAddressOrNone", (t, id, link) => {
    // Arrange / Act
    const url = traceLink(t, id);

    // Assert
    expect(url).toBe(link);
  });

  it.each([
    [intact, "Intact: 1,042 events, 1 to 1042"],
    [{ ...intact, events: 1, first_id: 3, last_id: 3 }, "Intact: 1 event, 3 to 3"],
    [{ ...intact, events: 0, first_id: null, last_id: null, head: null }, "Intact: no events yet"],
    [broken, "Broken at event 517: its content no longer gives its hash"],
    [
      { ...broken, broken: { id: 9, reason: "removed" as const } },
      "Broken at event 9: an event before it is gone",
    ],
  ])("givenAChain_whenSaid_thenOkOrWhereItBreaks %#", (chain, line) => {
    // Arrange / Act
    const said = chainLine(chain);

    // Assert
    expect(said).toBe(line);
  });

  it.each([
    [{ outcome: "answered" as const, reason: null }, "Answered"],
    [{ outcome: "refused" as const, reason: "client" }, "Refused · client not admitted"],
    [{ outcome: "error" as const, reason: "deadline" }, "Error · out of time"],
  ])("givenAnOutcome_whenLabelled_thenWithItsReason %#", (e, label) => {
    // Arrange / Act
    const said = outcomeLabel(e);

    // Assert
    expect(said).toBe(label);
  });
});

describe("dataErrorMessage", () => {
  it.each([
    [
      "login_refused",
      "hr",
      "Data source hr: the database refused the login, at 10:43. Check its password on the Data sources page.",
    ],
    [
      "unreachable",
      "hr",
      "Data source hr: the database could not be reached, at 10:43. Check its URL on the Data sources page.",
    ],
    [
      "login_refused",
      null,
      "A data source: the database refused the login, at 10:43. Check its password on the Data sources page.",
    ],
  ] as const)("givenError_%s_onDataSource_%s_whenShown_thenItSays_%s", (error, source, said) => {
    // Arrange
    const dataError: DataError = { data_source: source, error, ts: "2026-10-19T10:43:00Z" };

    // Act
    const message = dataErrorMessage(dataError, "10:43");

    // Assert
    expect(message).toBe(said);
  });
});
