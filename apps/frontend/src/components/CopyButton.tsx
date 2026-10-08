"use client";

import { Button, CopyIcon } from "@aisolate/ui";
import { useEffect, useRef, useState } from "react";

/** Copies `text`, its title on hover; the label says "Copied" or "Copy failed" for 1.5 s. */
export function CopyButton({ text, label = "Copy" }: { text: string; label?: string }) {
  const [said, setSaid] = useState<string | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout>>(undefined);

  useEffect(() => () => clearTimeout(timer.current), []);

  const copy = async () => {
    let result = "Copied";
    try {
      await navigator.clipboard.writeText(text);
    } catch {
      result = "Copy failed";
    }
    setSaid(result);
    clearTimeout(timer.current);
    timer.current = setTimeout(() => setSaid(null), 1500);
  };

  return (
    <Button variant="secondary" title={text} onClick={() => void copy()}>
      <CopyIcon size={14} />
      {said ?? label}
    </Button>
  );
}
