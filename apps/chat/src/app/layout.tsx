import type { Metadata } from "next";
// The AIsolate UI's Geist, from the `geist` package (OFL 1.1): no font is fetched from outside the install.
import { GeistMono } from "geist/font/mono";
import { GeistSans } from "geist/font/sans";
import "./globals.css";

export const metadata: Metadata = {
  title: "AIsolate chat",
  description: "Ask a question. Answers come only from the data you may see.",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en" className={`${GeistSans.variable} ${GeistMono.variable} h-full`}>
      <body className="flex min-h-full flex-col font-sans">{children}</body>
    </html>
  );
}
