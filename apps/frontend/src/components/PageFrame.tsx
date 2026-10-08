"use client";

import { Muted, UserMenu } from "@aisolate/ui";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useEffectEvent, type ReactNode } from "react";
import { logout } from "@/lib/auth";
import { env } from "@/lib/env";
import { NOT_APPLIED, setNotAppliedOn, useNotAppliedOn } from "@/lib/notice";
import type { Trail } from "@/lib/rail";
import { useSession } from "./Session";

/** The bar above every page and the canvas under it: the trail on the left, the user on the right. */
export function PageFrame({ trail, children }: { trail: Trail; children: ReactNode }) {
  const session = useSession();
  const pathname = usePathname();
  const notAppliedOn = useNotAppliedOn();
  // On a path change only: the save's own navigation lands on the notice, any other leaves it.
  const moved = useEffectEvent(() => {
    if (notAppliedOn !== pathname) setNotAppliedOn(null);
  });
  useEffect(() => moved(), [pathname]);
  return (
    <>
      <div className="shrink-0 border-b border-hairline">
        {/* Edge to edge as GitLab's: on a wide screen the trail stays left, the user right. */}
        <div className="flex h-12 w-full items-center justify-between gap-4 px-4">
          <Breadcrumbs {...trail} />
          <div className="hidden md:block">
            <UserMenu name={session.name} email={session.email} onSignOut={() => void logout()} />
          </div>
        </div>
      </div>
      <main className="flex-1">
        <div className="mx-auto w-full max-w-6xl px-4 py-6 sm:px-6 lg:px-10 lg:py-8">
          {notAppliedOn === pathname && (
            <Muted role="status" className="mb-6">
              {NOT_APPLIED}
            </Muted>
          )}
          {children}
        </div>
      </main>
      <footer className="px-4 py-3 sm:px-6 lg:px-10">
        <Muted className="text-xs">
          AIsolate {env.version} ·{" "}
          <a href={env.sourceUrl} className="underline hover:text-ink">
            Source
          </a>
        </Muted>
      </footer>
    </>
  );
}

function Breadcrumbs({ crumbs, heading }: Trail) {
  const last = crumbs.length - 1;
  const Current = heading ? "h1" : "span";
  return (
    <nav
      aria-label="Breadcrumb"
      className="flex min-w-0 items-center gap-2 text-sm text-ink-tertiary"
    >
      {crumbs.map((crumb, i) => (
        // Below sm only the last two crumbs show; the current one never truncates for an ancestor.
        <div
          key={`${crumb.label}-${i}`}
          className={i < last - 1 ? "hidden sm:contents" : "contents"}
        >
          {crumb.href ? (
            <Link href={crumb.href} className="truncate hover:text-ink hover:underline">
              {crumb.label}
            </Link>
          ) : (
            <Current
              aria-current="page"
              className="max-w-full shrink-0 truncate font-semibold text-ink"
            >
              {crumb.label}
            </Current>
          )}
          {i < last && (
            <span aria-hidden className="shrink-0">
              /
            </span>
          )}
        </div>
      ))}
    </nav>
  );
}
