"use client";

import { fieldClass } from "@aisolate/ui";
import { useState, type InputHTMLAttributes } from "react";

/** Adds `raw` to `list`: trimmed, refused with its reason when `check` says so; a repeat is a no-op. */
export function addItem(
  list: string[],
  raw: string,
  check: (item: string) => string | null = roleProblem,
): { list: string[]; problem: string | null } {
  const item = raw.trim();
  if (!item) return { list, problem: null };
  const problem = check(item);
  if (problem) return { list, problem };
  return { list: list.includes(item) ? list : [...list, item], problem: null };
}

/** The backend's rule for a role name: no whitespace inside, and `*` is no role. */
export function roleProblem(item: string): string | null {
  if (item === "*") return "* is no role: list each role; only callers take * for any";
  if (/\s/.test(item)) return `${item}: a role has no whitespace`;
  return null;
}

type Props = {
  value: string[];
  onChange: (next: string[]) => void;
  /** The field's `control(...)` props (id, aria, blur); the draft input spreads them. */
  control: InputHTMLAttributes<HTMLInputElement>;
  check?: (item: string) => string | null;
  placeholder?: string;
};

/** A list typed as chips: Enter or a comma adds, the x removes. Text left in the box is added on blur. */
export function RoleList({ value, onChange, control, check, placeholder }: Props) {
  const [draft, setDraft] = useState("");
  const [problem, setProblem] = useState<string | null>(null);

  const commit = (raw: string) => {
    const r = addItem(value, raw, check);
    setProblem(r.problem);
    if (!r.problem) {
      setDraft("");
      if (r.list !== value) onChange(r.list);
    }
  };

  return (
    <div className="grid gap-2">
      {value.length > 0 && (
        <ul className="flex flex-wrap gap-1">
          {value.map((v) => (
            <li
              key={v}
              className="flex items-center gap-1 rounded border border-control bg-desk px-2 py-0.5 text-sm"
            >
              {v}
              <button
                type="button"
                aria-label={`Remove ${v}`}
                className="text-ink-secondary hover:text-ink"
                onClick={() => onChange(value.filter((x) => x !== v))}
              >
                ×
              </button>
            </li>
          ))}
        </ul>
      )}
      <input
        {...control}
        className={fieldClass}
        value={draft}
        placeholder={placeholder}
        aria-invalid={problem ? true : control["aria-invalid"]}
        onChange={(e) => setDraft(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter" || e.key === ",") {
            e.preventDefault();
            commit(draft);
          }
        }}
        onBlur={(e) => {
          control.onBlur?.(e);
          commit(draft);
        }}
      />
      {problem && <p className="text-sm text-error">{problem}</p>}
    </div>
  );
}
