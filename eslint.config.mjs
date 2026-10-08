import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";
import eslintConfigPrettier from "eslint-config-prettier/flat";

// One config for the AIsolate UI and the shared package; each workspace runs `eslint` in its own dir.
const eslintConfig = defineConfig([
  ...nextVitals,
  ...nextTs,
  eslintConfigPrettier,
  globalIgnores(["**/.next/**", "**/out/**", "**/build/**", "**/next-env.d.ts", "**/node_modules/**"]),
]);

export default eslintConfig;
