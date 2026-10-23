"use client";

import {
  ApiError,
  Button,
  CopyIcon,
  EyeIcon,
  EyeOffIcon,
  LockIcon,
  tableFrame,
} from "@aisolate/ui";
import { useEffect, useRef, useState } from "react";
import { api, onApiError } from "@/lib/api";
import { getAccessToken, reauthenticate } from "@/lib/auth";
import { useSession } from "./Session";

type Props = {
  title: string;
  /** The row's plain fields, read-only above the table. */
  fields: { label: string; value: string }[];
  /** The Secret's name; null for a row that keeps no credentials. */
  secretRef: string | null;
  /** The Secret's keys as the reveal answers them, with the words the table shows for them. */
  keys: { name: string; label: string }[];
  onClose: () => void;
};

const MASK = "••••••••";
const SHOWN_MS = 30_000;
const SAID_MS = 1500;
// Set before the one re-login, so an identity provider that ignores it cannot loop us. A timestamp, not
// a bare flag: a redirect that fails, or an admin who waits past the window, must get a second chance.
const KEY = "aisolate.reveal.reauth";
const REAUTH_WINDOW_MS = 300_000;
const HINT = "Only an admin can reveal it";

const entryClass = "rounded border border-hairline bg-desk px-3 py-2 text-sm break-all";
const iconClass =
  "inline-flex size-6 items-center justify-center rounded text-ink-tertiary transition-colors duration-150 hover:text-ink disabled:opacity-50";

/** Whether the one re-login of this visit was asked for less than five minutes ago. */
export function askedRecently() {
  const at = Number(sessionStorage.getItem(KEY));
  return Number.isFinite(at) && at > 0 && Date.now() - at < REAUTH_WINDOW_MS;
}

// Outside the component: the compiler's purity lint reads a clock call in a handler as render-time.
const markAsked = () => sessionStorage.setItem(KEY, String(Date.now()));

/**
 * One row of the list, read-only: its fields, then its Secret's keys masked, each
 * with an eye and a copy. The first eye or copy fetches the values once (one recorded reveal); they
 * live in this state alone and are wiped after 30 s or on Close.
 */
export function SecretView({ title, fields, secretRef, keys, onClose }: Props) {
  const { admin } = useSession();
  const [values, setValues] = useState<Record<string, string> | null>(null);
  const [shown, setShown] = useState<ReadonlySet<string>>(new Set());
  const [message, setMessage] = useState<string | null>(null);
  const [said, setSaid] = useState<{ key: string; text: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const panel = useRef<HTMLElement>(null);
  const timer = useRef<ReturnType<typeof setTimeout>>(undefined);
  const saidTimer = useRef<ReturnType<typeof setTimeout>>(undefined);

  // Opened from a row's menu, the panel takes the focus (which scrolls it into view) and hands it
  // back to that menu's button on Close; not when another row's View replaced it, where the focus
  // already sits on that row's button. Close says so through a ref: by cleanup time the panel is
  // out of the document and the focus reads as the body's.
  const closing = useRef(false);
  // A ref, so strict mode's second mount (after the first focused the panel) keeps the button.
  const back = useRef<Element | null>(null);
  useEffect(() => {
    back.current ??= document.activeElement;
    panel.current?.focus();
    return () => {
      clearTimeout(timer.current);
      clearTimeout(saidTimer.current);
      if (closing.current && back.current instanceof HTMLElement) back.current.focus();
    };
  }, []);

  const wipe = () => {
    clearTimeout(timer.current);
    setValues(null);
    setShown(new Set());
  };

  /** The values, fetched on the first ask; null when the backend refused (the message says why). */
  const ensure = async (): Promise<Record<string, string> | null> => {
    if (values) return values;
    if (!secretRef) return null;
    setBusy(true);
    setMessage(null);
    try {
      const answer = await api.credentials.reveal(await getAccessToken(), secretRef);
      setValues(answer.values);
      sessionStorage.removeItem(KEY);
      clearTimeout(timer.current);
      timer.current = setTimeout(wipe, SHOWN_MS);
      return answer.values;
    } catch (e) {
      if (e instanceof ApiError && e.reason === "stale_login") {
        if (askedRecently()) {
          setMessage("Your identity provider did not ask you to sign in again");
        } else {
          markAsked();
          void reauthenticate();
        }
      } else if (e instanceof ApiError && (e.status === 404 || e.status === 409)) {
        setMessage(e.message);
      } else {
        onApiError(e, "Could not reveal", { banner: setMessage });
      }
      return null;
    } finally {
      setBusy(false);
    }
  };

  const toggle = async (name: string) => {
    if (shown.has(name)) {
      setShown((prev) => new Set([...prev].filter((n) => n !== name)));
    } else if (await ensure()) {
      setShown((prev) => new Set(prev).add(name));
    }
  };

  const copy = async (name: string) => {
    let refused = false;
    const text = ensure().then((v) => {
      // Rejecting, not "": a refused reveal must leave what the clipboard held.
      if (!v) {
        refused = true;
        throw new Error("refused");
      }
      return v[name] ?? "";
    });
    let outcome = "Copied";
    try {
      // The write starts inside the click: WebKit allows the clipboard only within the user's gesture,
      // and on the first copy the value is still on its way from the backend.
      if (typeof ClipboardItem === "function") {
        const blob = text.then((t) => new Blob([t], { type: "text/plain" }));
        // Observed here too: a write that throws before it takes the blob would leave the refusal unhandled.
        blob.catch(() => undefined);
        await navigator.clipboard.write([new ClipboardItem({ "text/plain": blob })]);
      } else {
        await navigator.clipboard.writeText(await text);
      }
    } catch {
      outcome = "Copy failed";
    }
    if (refused) return;
    clearTimeout(saidTimer.current);
    setSaid({ key: name, text: outcome });
    saidTimer.current = setTimeout(() => setSaid(null), SAID_MS);
  };

  return (
    <section
      ref={panel}
      tabIndex={-1}
      aria-label={title}
      className={`${tableFrame} grid gap-4 p-5 outline-none`}
    >
      <h2 className="text-base font-semibold tracking-snug">{title}</h2>
      <dl className="grid max-w-xl gap-3">
        {fields.map((f) => (
          <div key={f.label} className="grid gap-1">
            <dt className="text-sm font-medium">{f.label}</dt>
            <dd className={entryClass}>{f.value}</dd>
          </div>
        ))}
        {/* The Secret's keys as entries under the fields, masked; "none" for a row without one. */}
        {keys.map((k) => {
          const visible = shown.has(k.name) && values;
          return (
            <div key={k.name} className="grid gap-1">
              <dt className="text-sm font-medium">{k.label}</dt>
              <dd className={`${entryClass} flex items-center gap-2`}>
                {/* The value takes the room, so the eye and the copy sit at the right edge. */}
                <span className="min-w-0 grow">
                  {!secretRef ? (
                    <span className="text-ink-tertiary">none</span>
                  ) : visible ? (
                    <code className="break-all">{values[k.name]}</code>
                  ) : (
                    <>
                      <span aria-hidden="true" className="tracking-widest">
                        {MASK}
                      </span>
                      <span className="sr-only">Hidden</span>
                    </>
                  )}
                </span>
                {secretRef &&
                  (admin ? (
                    <>
                      <button
                        type="button"
                        aria-label={`${visible ? "Hide" : "Show"} ${k.label}`}
                        disabled={busy}
                        onClick={() => void toggle(k.name)}
                        className={iconClass}
                      >
                        {visible ? <EyeOffIcon size={16} /> : <EyeIcon size={16} />}
                      </button>
                      <button
                        type="button"
                        aria-label={`Copy ${k.label}`}
                        disabled={busy}
                        onClick={() => void copy(k.name)}
                        className={iconClass}
                      >
                        <CopyIcon size={16} />
                      </button>
                      {/* Always present, so the outcome is announced when it appears. */}
                      <span role="status" className="text-xs text-ink-tertiary">
                        {said?.key === k.name ? said.text : ""}
                      </span>
                    </>
                  ) : (
                    <span className="inline-flex text-ink-tertiary" title={HINT}>
                      <LockIcon size={16} />
                      <span className="sr-only">{HINT}</span>
                    </span>
                  ))}
              </dd>
            </div>
          );
        })}
      </dl>
      {message && (
        <p role="alert" className="text-sm text-error">
          {message}
        </p>
      )}
      <div className="flex justify-end">
        <Button
          variant="secondary"
          disabled={busy}
          onClick={() => {
            closing.current = true;
            wipe();
            onClose();
          }}
        >
          Close
        </Button>
      </div>
    </section>
  );
}
