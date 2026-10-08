import { describe, expect, it } from "vitest";
import { endpointUrl } from "../airlock";

describe("endpointUrl", () => {
  it.each([
    ["a whole URL", "https://ui.example/v1/x/chat", "https://ui.example/v1/x/chat"],
    ["a path on the API's host", "/v1/x/chat", "https://api.example/v1/x/chat"],
  ])("givenEndpoint_%s_whenEndpointUrl_thenTheFullUrl", (_, endpoint, expected) => {
    // Arrange
    const api = "https://api.example";

    // Act
    const url = endpointUrl(endpoint, api);

    // Assert
    expect(url).toBe(expected);
  });
});
