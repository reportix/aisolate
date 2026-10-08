import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";

export default defineConfig({
  // Vitest does not read tsconfig `paths`; mirror the `@/*` alias for the components a test renders.
  resolve: { alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) } },
  test: { environment: "node", include: ["src/**/*.test.ts"] },
});
