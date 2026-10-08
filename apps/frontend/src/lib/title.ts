"use client";

import { useEffect } from "react";

/** The browser tab's title: "AIsolate | Models", the product first as GitLab's "Project · GitLab" is not. */
export const tabTitle = (page?: string) => (page ? `AIsolate | ${page}` : "AIsolate");

/** Sets the tab's title for as long as the page is shown; the layout's "AIsolate" comes back after. */
export function useTabTitle(page?: string) {
  useEffect(() => {
    document.title = tabTitle(page);
    return () => {
      document.title = tabTitle();
    };
  }, [page]);
}
