import type { Metadata } from "next";
// Geist ships in the `geist` package (OFL 1.1), so the build fetches no font from outside the install.
import { GeistMono } from "geist/font/mono";
import { GeistSans } from "geist/font/sans";
import "./globals.css";

export const metadata: Metadata = {
  title: "AIsolate",
  description: "Define spaces and airlocks: governed chat over your databases.",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en" className={`${GeistSans.variable} ${GeistMono.variable} h-full`}>
      {/* min-h, not h: a fixed body lets tall pages overflow the UI row, cutting the rail short. */}
      <body className="flex min-h-full flex-col font-sans">{children}</body>
    </html>
  );
}
