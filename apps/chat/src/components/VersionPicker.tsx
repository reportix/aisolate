"use client";
import type { AirlockVersion } from "@/lib/api";
import { Menu, MenuRow } from "./AirlockPicker";

type Props = {
  /** The versions of the airlock that answers, in AIsolate's order. */
  versions: AirlockVersion[];
  selected: string;
  onPick: (version: string) => void;
};

/**
 * A second pill beside the airlock's: which of its versions answers. Only once there is a choice to
 * make; a lone version is named in the airlock pill instead.
 */
export function VersionPicker({ versions, selected, onPick }: Props) {
  if (versions.length <= 1) return null;
  const current = versions.find((v) => v.version === selected);
  const label = current?.version ?? "Choose a version";
  return (
    <Menu label={label} name={current ? `Version ${label}` : label}>
      {versions.map((v) => (
        <MenuRow
          key={v.version}
          label={v.version}
          hint={v.description}
          selected={v === current}
          onClick={() => onPick(v.version)}
        />
      ))}
    </Menu>
  );
}
