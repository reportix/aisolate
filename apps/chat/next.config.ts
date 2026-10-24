import { fileURLToPath } from "node:url";
import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // The image runs `node server.js` from the standalone output; the workspace root is two levels up.
  output: "standalone",
  // No image optimisation: the apps serve no remote images, and sharp (libvips, LGPL) stays out of the image.
  images: { unoptimized: true },
  outputFileTracingRoot: fileURLToPath(new URL("../..", import.meta.url)),
  async headers() {
    return [
      {
        source: "/:path*",
        headers: [
          // Answers are model text: no image in one reaches another host. Frames from this origin
          // only, which stops clickjacking. Sign-in renews by refresh token, never in a frame.
          { key: "Content-Security-Policy", value: "img-src 'self' data:; frame-ancestors 'self'" },
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "Referrer-Policy", value: "no-referrer" },
        ],
      },
    ];
  },
};

export default nextConfig;
