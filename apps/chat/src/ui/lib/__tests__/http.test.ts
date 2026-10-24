import { describe, expect, it } from "vitest";
import { ApiError, Unauthorized, Unreachable, expectOk, problem } from "../http";

describe("problem", () => {
  it("givenAProblem_whenRead_thenItsDetail", async () => {
    // Arrange
    const res = new Response(JSON.stringify({ status: 403, detail: "not your airlock" }), {
      status: 403,
      headers: { "Content-Type": "application/problem+json" },
    });
    // Act
    const err = await problem(res);
    // Assert
    expect(err.status).toBe(403);
    expect(err.message).toBe("not your airlock");
  });

  it("givenABodyThatIsNotJson_whenRead_thenTheStatus", async () => {
    // Arrange / Act
    const err = await problem(new Response("nope", { status: 502 }));
    // Assert
    expect(err.message).toBe("HTTP 502");
  });
});

describe("expectOk", () => {
  it("givenA401OrA2xx_whenExpectOk_thenUnauthorizedOrTheResponse", async () => {
    // Arrange
    const ok = new Response("{}", { status: 200 });
    // Act / Assert
    await expect(expectOk(new Response(null, { status: 401 }))).rejects.toBeInstanceOf(
      Unauthorized,
    );
    expect(await expectOk(ok)).toBe(ok);
  });

  it.each([502, 503, 504])("givenGateway%i_whenExpectOk_thenUnreachable", async (status) => {
    // Arrange / Act
    const act = expectOk(new Response(null, { status }));
    // Assert
    await expect(act).rejects.toBeInstanceOf(Unreachable);
    await expect(act).rejects.toThrow("AIsolate is not answering");
  });

  it("givenAPlain500_whenExpectOk_thenTheProblemItCarriesNotUnreachable", async () => {
    // Arrange / Act
    const act = expectOk(new Response(null, { status: 500 }));
    // Assert
    await expect(act).rejects.toBeInstanceOf(ApiError);
    await expect(act).rejects.not.toBeInstanceOf(Unreachable);
    await expect(act).rejects.toMatchObject({ status: 500 });
  });
});
