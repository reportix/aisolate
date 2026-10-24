import { describe, expect, it } from "vitest";
import { ApiError, Unauthorized, Unreachable, expectOk, problem } from "../http";

describe("problem", () => {
  it("givenAnInvalidForm_whenRead_thenItsFieldErrors", async () => {
    // Arrange
    const res = new Response(
      JSON.stringify({
        status: 400,
        detail: "invalid form",
        errors: ["name: required", "model: required"],
      }),
      { status: 400, headers: { "Content-Type": "application/problem+json" } },
    );
    // Act
    const err = await problem(res);
    // Assert
    expect(err.status).toBe(400);
    expect(err.message).toBe("invalid form");
    expect(err.errors).toEqual(["name: required", "model: required"]);
  });

  it("givenAProblemWithAReason_whenParsed_thenApiErrorCarriesIt", async () => {
    // Arrange
    const res = new Response(
      JSON.stringify({ detail: "sign in again to reveal", reason: "stale_login" }),
      { status: 403, headers: { "Content-Type": "application/problem+json" } },
    );
    // Act
    const err = await problem(res);
    // Assert
    expect(err.reason).toBe("stale_login");
    expect(err.message).toBe("sign in again to reveal");
  });

  it("givenAProblemWithoutAReason_whenParsed_thenReasonIsUndefined", async () => {
    // Arrange
    const res = new Response(JSON.stringify({ detail: "invalid form" }), { status: 400 });
    // Act
    const err = await problem(res);
    // Assert
    expect(err.reason).toBeUndefined();
  });

  it("givenABodyThatIsNotJson_whenRead_thenTheStatus", async () => {
    // Arrange / Act
    const err = await problem(new Response("nope", { status: 502 }));
    // Assert
    expect(err.message).toBe("HTTP 502");
    expect(err.errors).toEqual([]);
  });
});

describe("expectOk", () => {
  // A 400 stays a plain ApiError, never Unreachable, so it is never retried.
  it.each<[number, unknown]>([
    [200, "ok"],
    [401, Unauthorized],
    [400, ApiError],
    [502, Unreachable],
    [504, Unreachable],
  ])("givenStatus%i_whenExpectOk_thenPassedOrThatFailure", async (status, expected) => {
    // Arrange
    const res = new Response(null, { status });
    // Act
    const outcome = await expectOk(res).then(
      () => "ok",
      (e: Error) => e.constructor,
    );
    // Assert
    expect(outcome).toBe(expected);
  });

  it("givenTheBackendsOwn502Problem_whenExpectOk_thenThatProblemNotUnreachable", async () => {
    // Arrange: the backend answered; a dependency of it did not
    const res = Response.json(
      { status: 502, detail: "a dependency did not answer" },
      { status: 502, headers: { "Content-Type": "application/problem+json" } },
    );

    // Act
    const e = (await expectOk(res).catch((x: unknown) => x)) as ApiError;

    // Assert
    expect(e).not.toBeInstanceOf(Unreachable);
    expect(e.status).toBe(502);
    expect(e.message).toBe("a dependency did not answer");
  });
});
