"use client";

import {
  CheckIcon,
  CpuIcon,
  FileSearchIcon,
  HistoryIcon,
  MaximizeIcon,
  MinusIcon,
  PlusIcon,
  cx,
} from "@aisolate/ui";
import { useLayoutEffect, useRef, useState, type ReactNode } from "react";
import { ZOOM, fitZoom, stepZoom, type DeployHint, type GraphNode } from "@/lib/graph";

const ICON = {
  "file-search": FileSearchIcon,
  history: HistoryIcon,
  cpu: CpuIcon,
  check: CheckIcon,
};

/** The parts of the deploy hint a developer types, set apart in ink. */
const TYPED = /(aisolate\.yaml|name: [\w-]+|aisolate deploy)/;

/** A thin gray dashed arrow, its dashes moving the way the exchange flows. */
function Wire({ wide }: { wide?: boolean }) {
  return (
    <span aria-hidden="true" className={cx("graph-wire", wide ? "mx-1.5 w-[52px]" : "mx-1 w-9")} />
  );
}

/**
 * One phase of the exchange, a gray box with a slate edge over the dots, its name above it: the steps that run
 * before the model or after it, or a dashed box that says none does.
 */
function Phase({ label, nodes, none }: { label: string; nodes: GraphNode[]; none: string }) {
  return (
    <section
      aria-label={label}
      className={cx(
        "relative rounded-xl border border-(--phase-edge) bg-(--phase) px-5 py-5",
        !nodes.length && "border-dashed",
      )}
    >
      <h3 className="absolute -top-6 left-0 text-xs leading-4 font-semibold text-ink-secondary">
        {label}
      </h3>
      {nodes.length ? (
        <ol className="flex items-center">
          {nodes.map((node, i) => (
            <li key={node.key} className="flex items-center">
              {i > 0 && <Wire />}
              <Node node={node} />
            </li>
          ))}
        </ol>
      ) : (
        <p className="flex h-12 items-center text-[12.5px] whitespace-nowrap text-ink-tertiary">
          {none}
        </p>
      )}
    </section>
  );
}

/** The model that answers, the centrepiece: one solid square in the model's purple, its id under the name. */
function ModelCard({ node }: { node: GraphNode }) {
  const [id] = node.details;
  return (
    <div className="flex size-[120px] flex-col items-center justify-center gap-2 rounded-2xl bg-step-llm px-3 text-center text-white shadow-[inset_0_0_0_2px_#a99cf0,0_1px_2px_rgba(31,33,37,0.12),0_4px_10px_-4px_rgba(91,71,200,0.3)]">
      <span className="flex size-10 items-center justify-center rounded-[10px] bg-white/16 shadow-[inset_0_0_0_1px_rgba(255,255,255,0.28)]">
        <CpuIcon size={20} />
      </span>
      <span className="w-full min-w-0 leading-tight">
        <span className="block text-[15px] font-semibold tracking-[-0.01em]">{node.title}</span>
        <span className="mt-0.5 block truncate text-xs text-white/85" title={id.value}>
          {id.value}
        </span>
      </span>
    </div>
  );
}

/**
 * A white card on the phase's gray: the icon on a neutral tile, the kind of step and a quieter one-line title; the node widens for a longer title up to 320px,
 * past that it is cut, whole on hover.
 */
function Node({ node }: { node: GraphNode }) {
  const Icon = ICON[node.icon];
  return (
    <div className="relative flex h-12 w-max max-w-80 shrink-0 items-center gap-2.5 rounded-lg bg-paper pr-3.5 pl-2 shadow-xs">
      <span className="flex size-8 shrink-0 items-center justify-center rounded-md bg-(--canvas) text-ink">
        <Icon size={18} />
      </span>
      <span className="min-w-0 leading-tight">
        <span className="block text-xs font-semibold text-ink">{node.kind}</span>
        <span title={node.title} className="block truncate text-xs text-ink-secondary">
          {node.title}
        </span>
      </span>
    </div>
  );
}

/**
 * The airlock on a dotted canvas in the order its code runs an exchange: the pre-model box, the
 * model, the post-model box. Without code, the sentence that brings it.
 */
export function AirlockGraph({ nodes, hint }: { nodes: GraphNode[]; hint: DeployHint }) {
  const canvas = useRef<HTMLDivElement>(null);
  const chain = useRef<HTMLOListElement>(null);
  const [width, setWidth] = useState<number>();
  const [fit, setFit] = useState(100);
  const [zoom, setZoom] = useState(100);
  // Off until the first zoom, so the fit on load shows at once instead of shrinking into place.
  const [animate, setAnimate] = useState(false);
  const zoomTo = (next: number) => {
    setAnimate(true);
    setZoom(next);
  };
  const scale = zoom / 100;
  const at = nodes.findIndex((n) => n.kind === "Model");
  const [pre, model, post] = [nodes.slice(0, at), nodes[at], nodes.slice(at + 1)];

  // Before the first paint: a chain wider than the canvas opens scaled to fit it (px-2 is 16px).
  useLayoutEffect(() => {
    if (!canvas.current || !chain.current) return;
    const natural = chain.current.offsetWidth;
    const fitted = fitZoom(natural, canvas.current.clientWidth - 16);
    setWidth(natural);
    setFit(fitted);
    setZoom(fitted);
  }, []);

  return (
    <div
      className="relative min-w-0 rounded-lg border border-hairline bg-(--canvas)"
      style={{
        // An editor-canvas look, colour-picked: a ground with 1px dots on whole pixels of a 15px grid; the phases a cool gray with a slate edge, to draw the eye.
        ["--canvas" as string]: "#f8f8f8",
        ["--phase" as string]: "#e8eaed",
        ["--phase-edge" as string]: "#a7aeb8",
        backgroundImage: "radial-gradient(circle, #c8c8c8 0.5px, transparent 0.5px)",
        backgroundSize: "15px 15px",
        backgroundPosition: "0 0",
      }}
    >
      {/* m-auto centres the chain, and unlike justify-center leaves a wider one scrollable. Its
          box takes the scaled width (the scale grows from the left edge), so a fitted chain
          centres and a zoomed one scrolls in full. Centring puts the phases and the wires on the
          model's middle, the phases 16px inside its top and bottom. */}
      {/* Focusable so a zoomed chain scrolls by keyboard in Safari too, which skips scrollers. */}
      <div
        ref={canvas}
        tabIndex={0}
        role="region"
        aria-label="Graph"
        className="flex min-h-[480px] overflow-x-auto px-2 py-16"
      >
        {nodes.length === 0 ? (
          <div className="m-auto max-w-3xl text-center text-sm">
            <p className="font-semibold">{hint.lead}</p>
            <p className="mt-1 text-ink-secondary">
              {hint.rest.split(TYPED).map((part, i) =>
                i % 2 ? (
                  <span key={i} className="text-ink">
                    {part}
                  </span>
                ) : (
                  part
                ),
              )}
            </p>
          </div>
        ) : (
          <div
            className={cx("m-auto", animate && "transition-[width] duration-150")}
            style={{ width: width && width * scale }}
          >
            <ol
              ref={chain}
              className={cx(
                "flex w-max origin-left items-center",
                animate && "transition-transform duration-150",
              )}
              style={{ transform: `scale(${scale})` }}
            >
              <li>
                <Phase label="Pre-model" nodes={pre} none="Nothing runs before the model" />
              </li>
              <li className="flex">
                <Wire wide />
              </li>
              <li>{model && <ModelCard node={model} />}</li>
              <li className="flex">
                <Wire wide />
              </li>
              <li>
                <Phase label="Post-model" nodes={post} none="Nothing runs after the model" />
              </li>
            </ol>
          </div>
        )}
      </div>

      <div className="absolute right-4 bottom-4 flex h-9 items-center rounded border border-hairline bg-paper px-1 text-ink-secondary">
        <ZoomButton
          label="Zoom out"
          disabled={zoom <= ZOOM.min}
          onClick={() => zoomTo(stepZoom(zoom, -1))}
        >
          <MinusIcon size={16} />
        </ZoomButton>
        <span aria-live="polite" className="w-12 text-center text-[13px] font-semibold text-ink">
          {zoom}%
        </span>
        <ZoomButton
          label="Zoom in"
          disabled={zoom >= ZOOM.max}
          onClick={() => zoomTo(stepZoom(zoom, 1))}
        >
          <PlusIcon size={16} />
        </ZoomButton>
        <span aria-hidden="true" className="mx-1 h-5 w-px bg-hairline" />
        <ZoomButton label="Fit to the canvas" onClick={() => zoomTo(fit)}>
          <MaximizeIcon size={16} />
        </ZoomButton>
      </div>
    </div>
  );
}

function ZoomButton({
  label,
  disabled,
  onClick,
  children,
}: {
  label: string;
  disabled?: boolean;
  onClick: () => void;
  children: ReactNode;
}) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      disabled={disabled}
      onClick={onClick}
      className="flex size-7 items-center justify-center rounded transition-colors duration-150 hover:bg-desk hover:text-ink disabled:opacity-40"
    >
      {children}
    </button>
  );
}
