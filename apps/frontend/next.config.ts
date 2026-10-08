import { fileURLToPath } from "node:url";
import type { NextConfig } from "next";
import pkg from "./package.json" with { type: "json" };

const nextConfig: NextConfig = {
  // The shared UI package ships TypeScript source; Next compiles it with the app.
  transpilePackages: ["@aisolate/ui"],
  // The image runs `node server.js` from the standalone output; the workspace root is two levels up.
  output: "standalone",
  // No image optimisation: sharp (libvips, LGPL) stays out of the image.
  // The footer's version (AGPL section 13): the release build's AISOLATE_VERSION, else package.json's.
  env: { NEXT_PUBLIC_AISOLATE_VERSION: process.env.AISOLATE_VERSION || pkg.version },
  images: { unoptimized: true },
  outputFileTracingRoot: fileURLToPath(new URL("../..", import.meta.url)),
  async headers() {
    return [
      {
        source: "/:path*",
        headers: [
          // Nothing frames the UI (clickjacking): renewal uses the refresh token, never an iframe.
          { key: "Content-Security-Policy", value: "frame-ancestors 'none'" },
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "Referrer-Policy", value: "no-referrer" },
        ],
      },
    ];
  },
};

export default nextConfig;
