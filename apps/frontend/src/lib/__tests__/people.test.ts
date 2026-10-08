import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api } from "../api";
import {
  SEARCH_DELAY,
  addMembers,
  addUsers,
  chosenEmails,
  removeMember,
  removeUser,
  searchAsYouType,
  withRole,
} from "../people";
import type { Member, Person } from "../types";

// The sign-in needs a browser; these calls never reach it.
vi.mock("../auth", () => ({ forgetAndLogin: vi.fn(async () => {}) }));
vi.mock("../connection", () => ({ reportDown: vi.fn() }));

const elena: Person = { name: "Elena Varga", username: "elena", email: "elena@acme.example" };
const mark: Person = { name: "Mark Hollander", username: "mark", email: "mark@acme.example" };

describe("searchAsYouType", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it("givenTypingE_l_e_whenTheTextStandsStill_thenOneSearchForEleAndItsPeopleShown", async () => {
    // Arrange
    const search = vi.fn(async () => [elena]);
    const show = vi.fn();
    const field = searchAsYouType(search, show);

    // Act
    field.run("E");
    await vi.advanceTimersByTimeAsync(SEARCH_DELAY / 2);
    field.run("El");
    await vi.advanceTimersByTimeAsync(SEARCH_DELAY / 2);
    field.run("Ele ");
    await vi.advanceTimersByTimeAsync(SEARCH_DELAY);

    // Assert
    expect(search).toHaveBeenCalledTimes(1);
    expect(search).toHaveBeenCalledWith("Ele");
    expect(show).toHaveBeenLastCalledWith([elena]);
  });

  it("givenASlowAnswerOvertakenByANewerSearch_whenItArrives_thenOnlyTheNewerOneShown", async () => {
    // Arrange: "el" answers after "mar" was asked
    let answerEl: (people: Person[]) => void = () => {};
    const search = vi.fn((text: string) =>
      text === "el" ? new Promise<Person[]>((r) => (answerEl = r)) : Promise.resolve([mark]),
    );
    const show = vi.fn();
    const field = searchAsYouType(search, show);
    field.run("el");
    await vi.advanceTimersByTimeAsync(SEARCH_DELAY);

    // Act
    field.run("mar");
    await vi.advanceTimersByTimeAsync(SEARCH_DELAY);
    answerEl([elena]);
    await vi.runAllTimersAsync();

    // Assert
    expect(show.mock.calls).toEqual([[[mark]]]);
  });

  it("givenTheFieldCleared_whenRun_thenResultsClearedWithoutASearch", async () => {
    // Arrange
    const search = vi.fn(async () => [elena]);
    const show = vi.fn();

    // Act
    searchAsYouType(search, show).run("  ");
    await vi.runAllTimersAsync();

    // Assert
    expect(search).not.toHaveBeenCalled();
    expect(show).toHaveBeenCalledWith(null);
  });
});

describe("addUsers and removeUser", () => {
  it.each([
    [
      "APickedPerson",
      ["mark@acme.example"],
      ["elena@acme.example"],
      ["mark@acme.example", "elena@acme.example"],
    ],
    [
      "SomeoneAlreadyListedInAnotherCase",
      ["Elena@Acme.example"],
      ["elena@acme.example"],
      ["Elena@Acme.example"],
    ],
    [
      "TheSamePersonTwice",
      [],
      ["elena@acme.example", "elena@acme.example"],
      ["elena@acme.example"],
    ],
  ])("given%s_whenAdded_thenEachEmailOnceInOrder", (_, current, added, expected) => {
    // Arrange / Act
    const users = addUsers(current, added);

    // Assert
    expect(users).toEqual(expected);
  });

  it("givenAListedUser_whenRemovedInAnotherCase_thenGoneAndTheOthersKept", () => {
    // Arrange
    const current = ["mark@acme.example", "elena@acme.example"];

    // Act
    const users = removeUser(current, "ELENA@acme.example");

    // Assert
    expect(users).toEqual(["mark@acme.example"]);
  });
});

describe("a space's members", () => {
  const jasmine: Member = { email: "jasmine@acme.example", role: "developer" };
  const elena: Member = { email: "elena@acme.example", role: "user" };

  it.each<[string, (m: Member[]) => Member[], Member[]]>([
    [
      "NewPeopleAsUsers",
      (m) => addMembers(m, ["mark@acme.example"], "user"),
      [jasmine, elena, { email: "mark@acme.example", role: "user" }],
    ],
    // Add users never demotes or promotes: the row's role select does
    [
      "AMemberAddedAgainAsDeveloper",
      (m) => addMembers(m, ["ELENA@acme.example"], "developer"),
      [jasmine, elena],
    ],
    [
      "ARowMadeDeveloper",
      (m) => withRole(m, "Elena@Acme.example", "developer"),
      [jasmine, { ...elena, role: "developer" }],
    ],
    ["ARowRemovedInAnotherCase", (m) => removeMember(m, "JASMINE@acme.example"), [elena]],
  ])("given%s_whenSaved_thenTheOthersKeepTheirRoles", (_, change, expected) => {
    // Arrange
    const current = [jasmine, elena];

    // Act
    const members = change(current);

    // Assert
    expect(members).toEqual(expected);
  });
});

describe("chosenEmails", () => {
  it.each([
    ["ADirectory", true, ["elena@acme.example"]],
    ["NoDirectory", false, ["lucas@acme.example", "mark@acme.example"]],
  ])("given%s_whenAdding_thenTheTickedPeopleOrTheTypedEmails", (_, directory, expected) => {
    // Arrange: without a directory the page shows the e-mail field, one per line
    const typed = "lucas@acme.example\n mark@acme.example \n";

    // Act
    const emails = chosenEmails(directory, [elena], typed);

    // Assert
    expect(emails).toEqual(expected);
  });
});

describe("api.directory.search", () => {
  beforeEach(() => vi.stubEnv("NEXT_PUBLIC_AISOLATE_URL", "http://backend.test"));
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
  });

  it("givenTextWithASpace_whenSearched_thenOneEncodedQueryAndThePeople", async () => {
    // Arrange
    const fetch = vi.fn(async () => Response.json([elena]));
    vi.stubGlobal("fetch", fetch);

    // Act
    const people = await api.directory.search("token", "Ele V");

    // Assert
    expect(fetch.mock.calls[0]).toEqual([
      "http://backend.test/v1/directory/people?q=Ele%20V",
      expect.objectContaining({ method: "GET" }),
    ]);
    expect(people).toEqual([elena]);
  });
});
