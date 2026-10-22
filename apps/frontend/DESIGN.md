---
name: AIsolate UI
description: "A ledger UI on neutral stationery: a light gray rail and ground, the page on one white rounded panel, every entity a row, one quiet hue per state."
colors:
  paper: "#ffffff"
  desk: "#f5f5f5"
  panel: "#ebebeb"
  hairline: "#e5e5e5"
  hairline-strong: "#dddddd"
  control: "#858585"
  ink: "#222222"
  ink-secondary: "#555555"
  ink-tertiary: "#666666"
  ink-inverse: "#ffffff"
  pen: "#1f2125"
  pen-hover: "#2e3036"
  pen-press: "#16171a"
  pen-wash: "rgba(31, 33, 37, 0.1)"
  focus: "#3a74e0"
  error: "#b91c1c"
  error-wash: "#fef2f2"
  error-border: "#fecaca"
  ok: "#15803d"
  warn: "#b45309"
  tint-lavender: "#f4f3f8"
  tint-sand: "#f8f6f2"
  tint-mint: "#f0f5f3"
  step-context: "#0f766e"
  step-context-wash: "#e7f4f2"
  step-memory: "#1d4ed8"
  step-memory-wash: "#e8effd"
  step-llm: "#5b47c8"
  step-llm-wash: "#efecfc"
  step-verifier: "#b45309"
  step-verifier-wash: "#fbf1e6"
typography:
  headline:
    fontFamily: "Geist, system-ui, sans-serif"
    fontSize: "1.25rem"
    fontWeight: 600
    lineHeight: 1.75rem
    letterSpacing: "-0.02em"
  figure:
    fontFamily: "Geist, system-ui, sans-serif"
    fontSize: "1.5rem"
    fontWeight: 500
    lineHeight: 2rem
    letterSpacing: "-0.03em"
    fontFeature: "tnum"
  section-title:
    fontFamily: "Geist, system-ui, sans-serif"
    fontSize: "1rem"
    fontWeight: 600
    lineHeight: 1.5rem
    letterSpacing: "-0.01em"
  title:
    fontFamily: "Geist, system-ui, sans-serif"
    fontSize: "0.875rem"
    fontWeight: 500
    lineHeight: 1.25rem
  body:
    fontFamily: "Geist, system-ui, sans-serif"
    fontSize: "0.875rem"
    fontWeight: 400
    lineHeight: 1.25rem
  label:
    fontFamily: "Geist, system-ui, sans-serif"
    fontSize: "0.75rem"
    fontWeight: 400
    lineHeight: 1rem
  column-head:
    fontFamily: "Geist, system-ui, sans-serif"
    fontSize: "0.8125rem"
    fontWeight: 500
    lineHeight: 1rem
  tile-initial:
    fontFamily: "Geist, system-ui, sans-serif"
    fontSize: "1rem"
    fontWeight: 600
    lineHeight: 1
  mono:
    fontFamily: "Geist Mono, ui-monospace, Courier New, monospace"
    fontSize: "0.75rem"
    fontWeight: 400
    lineHeight: 1rem
rounded:
  sm: "4px"
  lg: "8px"
  full: "9999px"
spacing:
  xs: "4px"
  sm: "8px"
  md: "12px"
  lg: "16px"
  xl: "20px"
  2xl: "24px"
  rail: "256px"
  rail-collapsed: "56px"
  bar: "48px"
  canvas-max: "72rem"
components:
  button-primary:
    backgroundColor: "{colors.pen}"
    textColor: "{colors.ink-inverse}"
    typography: "{typography.title}"
    rounded: "{rounded.sm}"
    padding: "0 12px"
  button-primary-hover:
    backgroundColor: "{colors.pen-hover}"
  button-primary-pressed:
    backgroundColor: "{colors.pen-press}"
  button-secondary:
    backgroundColor: "{colors.paper}"
    textColor: "{colors.ink}"
    typography: "{typography.title}"
    rounded: "{rounded.sm}"
    padding: "0 12px"
  button-secondary-hover:
    backgroundColor: "{colors.desk}"
  card:
    backgroundColor: "{colors.paper}"
    rounded: "{rounded.lg}"
  row:
    backgroundColor: "{colors.paper}"
    textColor: "{colors.ink}"
    typography: "{typography.title}"
    padding: "12px 16px"
  tile:
    typography: "{typography.tile-initial}"
    rounded: "{rounded.lg}"
    size: "40px"
  tile-sm:
    typography: "{typography.label}"
    rounded: "{rounded.sm}"
    size: "24px"
  field:
    backgroundColor: "{colors.paper}"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.sm}"
    padding: "8px 12px"
  chip-mono:
    backgroundColor: "{colors.paper}"
    textColor: "{colors.ink}"
    typography: "{typography.mono}"
    rounded: "{rounded.full}"
    padding: "2px 8px"
  nav-row:
    textColor: "{colors.ink-secondary}"
    typography: "{typography.body}"
    rounded: "{rounded.sm}"
    padding: "8px 12px"
  nav-row-active:
    backgroundColor: "{colors.pen-wash}"
    textColor: "{colors.pen}"
    typography: "{typography.title}"
    rounded: "{rounded.sm}"
    padding: "8px 12px"
  ledger-cell:
    backgroundColor: "{colors.paper}"
    padding: "12px 16px"
  tab:
    textColor: "{colors.ink-secondary}"
    typography: "{typography.body}"
    padding: "8px 0"
  tab-active:
    textColor: "{colors.ink}"
    typography: "{typography.title}"
  graph-node:
    backgroundColor: "{colors.paper}"
    textColor: "{colors.ink}"
    typography: "{typography.title}"
    rounded: "{rounded.lg}"
    padding: "8px 12px"
    size: "192px"
---

# Design System: AIsolate UI

## Overview

**Creative North Star: "The Ledger"**

The AIsolate UI reads like a ledger. Every state is visible at a glance and every state has one quiet hue; the operator scans, finds what waits, and acts. The shell is a light neutral (`desk`) ground carrying the rail directly, with the page, its top bar included, on one white (`paper`) panel inset 8px from the top, right and bottom, 8px radius and a hairline border. The palette is neutral with a near-black button, the type a modern grotesk with tight heading tracking, and every control has 4px corners. Nothing is marketed: no hero, no decorative gradient; the first thing under the title is a row of two figure cards.

Every list of entities is laid out as GitLab's CRUD component lays one out: the page's header holds only its title and the description under it; each table's action (Add users, New space, New airlock, Add model, Add data source) sits right-aligned just above it, outside any border, 12px over the frame (a hairline box with the card's 8px corners) that holds only the table: the column heads and a quiet line between rows, the first column "Name" (a link), then what it is for and where it lives. A second section on a page (each part of an airlock's Details) opens with a plain subheading and its one line outside the frame, its action (Copy) right-aligned on the subheading's line, then its frame; no section shows a count. A definition list is framed the same way, so no rows float without a border. Every page sits under one paper top bar that carries its breadcrumb trail and the user's avatar; every page but the dashboard then opens with its header, an entity's with its tile. The page never opens on a table of machine strings.

Density lives where the work is (rows, definition lists, the ledger strip) and air where the eye rests (page headers, the collapsed rail). Type is Geist on a fixed rem scale with one display-ish step (the 24px ledger figure), headings tightened; Geist Mono is kept for code blocks alone (a trace step's JSON). Numbers everywhere are tabular. The browser's own surfaces (selection, caret, scrollbars, focus ring) carry the palette so the world does not stop at the component edge.

Motion is a grammar of state, not delight: colors settle in 150 ms; the one signature motion is the rail folding its labels into icons in 200 ms ease-out.

**Key Characteristics:**

- Rail on the desk ground (256px, collapses to 56px); the page, top bar included, is one white panel inset from the gray ground, capped at 72rem.
- The rail follows the route, one context per level: Dashboard, Spaces, Models and Data sources (AIsolate's own, for admins and developers), Monitoring (for admins and developers, when the install has a trace store) and Audit (for admins, developers and auditors) at the top, an auditor alone seeing Audit only and landing on it after sign-in; inside a space its own row, then Airlocks, Users, Models and Data sources; inside an airlock its own row, then Users, Models and Data sources.
- Every list, add and edit is a page of its own with its own breadcrumb; nothing opens in a dialog.
- 1px hairlines divide everything; cards and panels are `rounded-lg` (8px), chips and avatars `rounded-full`, controls `rounded` (4px); no pill buttons. Every button is 32px tall.
- Entities are table rows in one hairline-framed Section under the page's header, its action right-aligned above the frame, never cards in a grid; the name cell is the link.
- Near-gray id-derived tile washes (oklch 95% lightness, 0.01 chroma) carry an entity's or a user's initial as a ground, never as an accent.
- Two state hues, ok and error, error on a pale wash; the pen, a near-black with a faint blue cast, is the one accent and marks what is primary or current; the focus ring is the only blue.
- Nothing is set in uppercase, letter-spaced open, italic or dashed; headings only tighten (negative tracking), and hierarchy is weight, size and ink.
- Geist for the interface, ids and URLs included; Geist Mono only in code blocks; tabular figures throughout.
- Inline Lucide-style stroke icons at 1.75 stroke width; no rasters, no glyph fonts.

## Colors

A neutral scheme: ink `#222`, a near-black `#1F2125` with a faint blue cast as the one pen, a light neutral `#F5F5F5` as the canvas under white paper; no tinted grounds, `ink-secondary` `#555` and `ink-tertiary` `#666` for supporting and label text. The two ledger stats carry their own identity tint (lavender for Spaces, sand for Airlocks), each a gray with a breath of hue, never a state hue. On the graph canvas each kind of step has its own hue (see Step hues). Hairlines are neutral (`#E5E5E5`, `#DDD` where a line must hold its own), and a field's border is the darker `#858585`, so an editable field shows at 3:1; two state hues (ok, error) and a computed tile wash per entity complete it. Every text colour clears 4.5:1 on both paper and desk.

### Primary

- **Pen** (`pen`): the one primary action per page (New space, New airlock, Add model, Add data source, a form's save), the open tab's bar, link hover in tables, the selected rail row's text, the caret, and the light avatar; white on it reads 16.1:1. Lightens to **Pen Hover** (`#2E3036`, 13.2:1) on hover and darkens to **Pen Press** (`#16171A`, 17.9:1) while pressed.
- **Pen Wash** (`pen-wash`): the 10% tint behind the lit rail row and the light avatar (`#E0E0E0` on desk, pen text on it 12.2:1); the pen as a ground never gets stronger than this.
- **Step hues** (`step-context` `#0F766E`, `step-memory` `#1D4ED8`, `step-llm` `#5B47C8`, `step-verifier` `#B45309`, each with a `-wash` ground): on the graph alone, a node's kind label in the hue and its tile in the wash with the icon in the hue; teal for a context preparator, blue for a memory, purple for the model, amber for a verifier. Each clears 4.5:1 on paper and on its wash. They name a kind of step, never a state.
- **Focus** (`focus`): a lighter blue `#3A74E0`, for the keyboard focus ring and the text selection alone: 4.42:1 on paper, 4.05:1 on desk, 3.65:1 against the pen.

### Neutral

- **Paper** (`paper`): every card, row list, table, panel, popover and field.
- **Desk** (`desk`): the canvas ground, secondary-button hover, the neutral chip.
- **Panel** (`panel`): skeleton bars. The darkest light gray in use.
- **Control** (`control`, `#858585`): the border of every input, select and textarea, the one 1px border that must show where something can be typed: 3.69:1 on paper, 3.38:1 on desk, 3.10:1 on panel. Chips borrow it, so a chip's edge clears 3:1 too.
- **Hairline** (`hairline`, `#E5E5E5`): all other 1px borders and dividers; **Hairline Strong** (`hairline-strong`, `#DDD`) only on the scrollbar thumb, the secondary button's border, and the graph's nodes, connectors and dots.
- **Ink** (`ink`, `#222`: 15.9:1 on paper, 14.6:1 on desk), **Ink Secondary** (`ink-secondary`, `#555`: 7.5:1, 6.8:1), **Ink Tertiary** (`ink-tertiary`, `#666`: 5.7:1, 5.3:1): title, supporting, and label text respectively.
- **Rail** (on the `desk` ground, no edge of its own): the page panel's border is the only divider. The active nav row is `pen-wash` with `pen` text; hover is `panel` with `ink` text. The mobile bar is `paper` with a hairline bottom edge.

### Tile wash

- **Tile** (computed, `oklch(95% 0.01 H)` ground with `oklch(40% 0.03 H)` ink, all but gray so the colour never competes with the name beside it): the initial of a space or airlock, or of a user's e-mail, where `H` is `tileHue(id)`, a stable 0–359 hash of the id. The same entity always gets the same wash; the wash is a ground behind one letter and is never used for text, borders or accents elsewhere.

### State pairs

- **Ok** (`ok`): the check icon when AIsolate is answering again, and an audit chain that holds.
- **Warn** (`warn`, `#B45309`): an unsourced figure, the model's own number: its dotted underline, its `!` and its badge in the audit list. The chat's amber; never an error.
- **Error** (`error` on `error-wash`, `error-border`): the error Banner, field errors, the form footer's problem count and the alert icon when AIsolate is unreachable.

### Named Rules

**The Errors Are Not Verdicts Rule.** Red appears on the error Banner, field errors, the form footer's problem count and the unreachable-backend alert, and in the audit on what proves something wrong: a mismatched figure, quote or table and a broken chain. Nothing else is red.

## Typography

**Display Font:** none; the headline is Geist at 20px.
**Body Font:** Geist (with system-ui, sans-serif), a free neo-grotesk: the variable font of the `geist` package (SIL OFL 1.1), self-hosted through `next/font/local`, so no font is fetched from outside the install; 400 / 500 / 600 in use.
**Label/Mono Font:** Geist Mono (with ui-monospace, Courier New, monospace), from the same package, 400 / 500.

**Character:** A neutral grotesk kept small, with tight tracking on headings (-0.03em on the figure, -0.02em on the headline, -0.01em on section titles; body text untouched) and generous line height, and one face for everything a row shows, ids and URLs as much as names: a second face at a smaller size read as noise beside them. Geist Mono sets only a code block. The chat and the Keycloak sign-in page use the same face (the theme ships `Geist-Variable.woff2` with its OFL notice).

### Hierarchy

- **Headline** (600, 20px / 28px, tracking -0.02em): the page title; one per page. Also the unreachable-backend dialog title.
- **Figure** (500, 24px / 32px, tracking -0.03em, tabular): the ledger cell value. The only step above the headline, and it is a number.
- **Section title** (600, 16px, tracking -0.01em): a Section's plain subheading over its frame, the step between the headline and the rows; never a count.
- **Title** (500, 14px): row names, button labels, the open tab, the active nav row; a graph node's title is the same at 13px.
- **Body** (400, 14px): everything else, including nav rows at rest, table cells and header descriptions. Descriptions cap at `max-w-2xl`.
- **Label** (400, 12px): row descriptions and meta, timestamps, a field's help tooltip, chips.
- **Column head** (500, 13px, secondary ink, sentence case): table `<th>` only, on paper between hairlines, lighter than the 500 ink cells under it; the first column is "Name" for a space or an airlock, never the entity the page already names, and otherwise the thing the row is: E-mail, Id, Tool, Entity.
- **Mono** (400, 12px): code blocks alone, a trace step's JSON. Ids, URLs, endpoints, models, handlers, tools and e-mails are body text.
- **Tile initial** (600, 16px in the 40px tile, 12px in the 24px tile): one letter, on the tile wash.

### Named Rules

**The One Face Rule.** A machine string (id, URL, tool, handler, user e-mail, rule, model, data source, context preparator) is set in Geist at the size of the text around it, like a name. Geist Mono is for code blocks alone.

**The Tabular Figures Rule.** `font-variant-numeric: tabular-nums` is on `body`; counts in row meta and ledger values align without further work.

**The Sentence Case Rule.** No text is ever uppercase, letter-spaced open or italic: not column heads, not labels, not chips, not tabs. A label is a word, not a stamp. The only tracking is the negative tightening of the headings above.

## Layout

A two-column shell: a sticky, full-height rail on the left (256px expanded, 56px collapsed, persisted in localStorage) and a flexible canvas. The canvas centers a column of `max-w-6xl` (72rem) with 16px horizontal padding at small widths, 24px from `sm` (640px), 40px from `lg` (1024px); vertical padding 24px, 32px from `lg`. The rail and mobile bar are both 56px tall at the top.

Above every page a 48px paper top bar, edge to edge with a hairline under it, carries the breadcrumb trail on the left and the user's avatar on the right, each 16px in from the panel's edge as GitLab's, whatever the screen's width; only the canvas under it is capped (`max-w-6xl`) (see Breadcrumbs). Pages are a vertical `grid gap-6` (24px between blocks). Entity pages open with an identity header: on one line the entity's 40px tile, the 20px title, and the actions flush right; the entity's own description sits under the title in 14px secondary ink. The tile is top-aligned, 4px down, beside the title and description; the dates line under them never pulls it lower. The dashboard alone has no title below the bar: the trail's last crumb is its `<h1>`. A list page (Spaces, AIsolate's Models and Data sources) and the section pages of a space or an airlock (Users, Models, Data sources) open with a `PageHeader` that holds only the 20px title and the description under it in 14px secondary ink; the list's action (New space, Add users, Add model, Add data source) is never beside the page title but right-aligned directly above the frame it changes, outside it, as GitLab's CRUD lists do. An entity's own actions (Edit, Copy endpoint) stay on its identity header, since no table owns them. A create page (new space, new airlock) and an add or edit page (Add users, Add model, Add data source) open the same way with their title and one line of context; an in-place edit of a label and description sits under the existing entity header and adds no title of its own. An airlock's header carries Copy endpoint and Edit; the address itself is the first row of its Details. A space's and an airlock's create page asks Id, then Label, then Description: the Id first, required, never derived from the label, with an info button saying it cannot change once saved; the Label (optional) is what every list, crumb, header, rail row and the chat show, and without one the backend stores the id as the name, so the id is shown instead; their in-place edit shows the Id read-only on the desk ground and the Label blank when the name is the id. No header carries an id line.

A `Section` is a frame with what names it above: its optional plain subheading (`title`, 16px 600) on a 32px line with the section's action right-aligned on it (`action`), an optional 14px secondary line under it (`description`), then 12px down one hairline box with 8px corners on paper holding only a `Table` (its column heads first) or a hairline-divided definition list. Where the page's title already names the list (Spaces, Users, Models, Data sources) the section has no subheading: the action alone stands right-aligned over the frame, at the shared 32px button height; nothing at all when the reader may not act. No counts. Nothing above the frame is inside a border. While its data loads a skeleton grid of the rows to come stands in their place, inside the same frame. An empty table keeps its column heads and shows one quiet row under them (see Empty state). Sections stack at the page's 24px gap. The dashboard is exactly: the ledger of two cells and the "Airlocks" section.

The rhythm is 16px / 12px for definition rows, inside the frame; 16px / 12px for table cells and 16px / 10px for table heads, inside the table's frame; 16px / 12px for ledger cells; 20px between a form's fields. Row parts sit 16px apart; the ledger is a stacked column below `sm`, two columns from `sm`, cards 16px apart. Every form is the `max-w-2xl` column alone. Definition rows split into a 180px label column and the value from `sm`.

Below `md` (768px) the rail disappears; the current context's rows become a wrapping row of links on the white bar under the wordmark and the user's avatar (see Navigation), and the content stacks. Tables scroll horizontally inside their frame rather than wrapping, and the graph's chain scrolls inside its canvas.

## Elevation & Depth

Flat by default; depth is hairlines and tonal layering (desk under paper, panel under a skeleton bar). One shadow is in use: **overlay** (`0 4px 12px rgba(0,0,0,0.08)`) on the four floating surfaces, the user-menu popover, a field's help tooltip, a graph node's details and the unreachable-backend dialog. The tabs are lines, not raised pills. Keyboard focus is a 1.5px focus-blue outline offset 1px plus a 3px focus-blue glow at 20% (`0 0 0 3px rgba(58,116,224,0.2)`), the same everywhere including the rail and the tabs. It comes from `theme.css`'s unlayered `:focus-visible` rule. Fields are the one exception: a focused input, select or textarea draws its own border in focus blue, doubled by a 1px inset shadow, with no outline and no glow around it.

### Named Rules

**The Flat Ledger Rule.** Cards, rows, tables, panels and the ledger strip cast no shadow. A shadow means the element floats over the page (popover, blocking dialog), never that it is important or selected.

## Shapes

Three radii, each bound to a class of thing: cards, panels, banners, popovers, every table's frame, the page panel, the graph canvas, the graph's nodes, the chat's answer tables and the 40px tile are `rounded-lg` (8px); chips, avatars and the graph's ports are `rounded-full`; every control is `rounded` (4px): buttons, fields, the banner's action, nav rows and the mobile nav links, the rail's toggle, the popover's sign-out row, the graph's zoom bar and its buttons, and with them the 24px tile, a node's 32px tile and skeleton bars. Nothing is a pill but a chip. Borders are always 1px solid, hairline but for a field's control border; nothing is dashed, and the one surface that carries a pattern is the graph canvas's dot grid. A section's frame is a box: one hairline border with 8px corners round its rows only, quiet lines between them; its subheading and action stand above it, unboxed. Tabs draw a 2px bottom border on a 1px hairline track. Icons are 24-unit Lucide-style strokes at 1.75 with round caps, drawn inline at 18px in the rail, 16px in the mobile nav links, ledger labels, row chevrons, the user menu, the banner, the graph's node tiles, zoom buttons and a row's icon buttons, 14px in labelled buttons and a field's info button, 12px inside breadcrumbs and the dates line, 24px only in the unreachable-backend dialog.

## Components

### Buttons

- **Shape:** one for every button: 32px tall, 12px sides, 14px 500 type, 4px corners, a 1px border, `inline-flex` with a 6px gap for a 14px icon; an icon-only button is a 32px square. Colors transition in 150 ms; disabled is 50% opacity with pointer events off. There are no smaller sizes: a section's action, a row's pencil and trash, the banner's action and Copy are the height of Save space.
- **Primary:** the near-black pen with white text and a pen border; hover a step lighter to pen hover, pressed a step darker to pen press. At most one per page (New space, New airlock, Add model, Add data source, Add users, a form's save or Add).
- **Secondary:** paper with a hairline-strong (`#DDD`) border, ink text; hover to desk. Every button that is not the page's one primary is this, border always visible: Edit, Copy endpoint, Copy, a form's Cancel, a model's or a data source's pencil and trash, a user row's Remove.
- **Labels:** a section's action names what it changes (Add users, Add model, Add data source) and sits right-aligned above that section's frame (on its subheading's line when it has one), the same 32px as every other button; a bare Edit is only the in-place edit of an entity's label and description under its header. Descriptions say what a list means in plain words ("Everyone listed, by their sign-in e-mail, may ask every airlock of this space."), never the token or protocol behind it.
- **No ghost:** nothing that is a button reads as a bare word; the zoom bar's icons are the graph's own toolbar.

### Lists

- **List:** a `Section` holding a `Table`: the section draws the action (and a subheading where the page needs one) above the frame and the frame, the table only its rows and scrolls sideways inside it. Every list of entities (spaces, airlocks, users, models, data sources, tools) is this pair; there is no other list. Empty and loading states sit inside the same frame.
- **Links:** the Name cell is the `<a>`, hovering to pen, and so is the Space cell on the dashboard's Airlocks table, which carries the space's 24px tile as the Name cell carries the airlock's. Rows do not hover: the link does.
- **Airlocks table** (a space's Airlocks section, the dashboard's list): Name (the link), Description (Space on the dashboard), Model (the model that answers, its id and model name, `granite · granite4.1:8b`; in tertiary ink "No code yet" before a deploy, "none" when its id names no model it sees). Empty: its heads over "There are no airlocks yet."
- **Users and data sources:** a Users table is GitLab's members list, Add users right-aligned above its frame: Name (the person's 24px tile and name, from the company's directory), E-mail, on an airlock Source in GitLab's words ("Direct" in plain text, or "Inherited from" and the space's name as a sans chip that links to the space's Users page: one table, the airlock's direct rows first), on a space Role in GitLab's words ("Developer" or "User", plain text; for the admin a 32px select in the row, which saves at once), and a direct row ends in Remove (secondary, one click, no dialog); an inherited row has none. An airlock's table has no Role: its users only ask. A person the directory no longer knows keeps the row with a "Not in the directory" tag (12px secondary ink on a control-bordered chip) in the Name cell. With no directory connected there is no Name column: the tile goes beside the e-mail. A Data sources table is Id, URL and Credentials ("Kept as a Secret" or none); a Models table is Id, Provider by its word (Ollama, OpenAI-compatible) and Model. AIsolate's own pages list theirs alone; a space's and an airlock's add a Source column (see Own and inherited rows). An own row ends in a pencil and a trash; an inherited row has none.
- **Tools table** (an airlock's Tools tab), by name: Tool and Data source, then Description, the description's one-line lead (its first sentence, up to a colon that opens the details). When more follows, the lead is a native disclosure with a 14px tertiary chevron that turns down, and the full text the model reads opens under it in 14px secondary ink.
- **Dates:** never in a table. Only an entity page (space, airlock) shows them, via `PageHeader`'s `dates`: one 12px tertiary line under the description, 16px between its two parts, a 12px calendar icon with "Created by Ivy Novak · 12 days ago" and a 12px refresh icon with "Updated 3 days ago"; each part's `title` holds the exact stamp. The creator is the person's name, as the backend stored it from the token at creation (`created_by_name`), else the username; never an e-mail, and with none the line reads "Created 12 days ago".
- **Loading:** a skeleton grid, one bar per cell of the rows and columns to come.

### Tile

- **Style:** the entity's initial, 600 weight, centered on a pale wash computed from its id (`oklch(95% 0.01 H)` ground, `oklch(40% 0.03 H)` letter). 40px with 8px corners in page headers; 24px with 4px corners in rows, the rail, the mobile nav links and beside every user's e-mail. `aria-hidden`; it decorates the name, it does not replace it.

### Chips

- **Chip** (`Chip`, `chipClass`): paper ground, a control border (`#858585`, 3.69:1 on paper), 12px ink text (15.9:1), full radius, in Geist, for the handlers of an airlock's Details as for a name, such as the space, the airlock or AIsolate an "Inherited from" names, where the chip is a link hovering to pen.

### Cards / Containers

- **Corner Style:** 8px; the controls inside are 4px.
- **Background:** paper with hairline border.
- **Shadow Strategy:** none (see The Flat Ledger Rule).
- **Internal Padding:** the caller's.
- **Ledger:** a `<dl>` of two `Stat` paper cards, 16px apart (stacked below `sm`, two across from `sm`). Each card is a label row over the figure, no line between them: a 16px / 12px label row (a 32px white circle with a hairline border and the 16px icon in secondary ink, then the 14px ink label) and the 24px 500 tabular figure. Each carries its section's identity tint (`tone`: Spaces lavender, Airlocks sand) as a barely visible wash, a vertical gradient from the label row, gone by 60%. Identity hues never mean state.
- **Definition:** a `<dl>` of hairline-divided rows inside a Section's frame, 16px from its sides; 180px label column at 12px tertiary ink, value in 14px. An airlock's Details are definitions. Details opens with the Endpoint section: the full address in 14px, Copy on the subheading's line.
- **Table:** a full-width 14px table inside a Section's frame (`tableFrame`: a hairline border, 8px corners, paper, drawn by the `Section`; the kit's `Table` only scrolls sideways inside it); the column heads first in the frame on a hairline, `<th>` in 13px 500 secondary ink on paper, sentence case; on the dashboard every cell is 14px 500 ink, the name no heavier than the rest; a hairline between rows and none under the last, which the frame closes; cells 16px from the frame's sides; cells `whitespace-nowrap` except a description that must wrap; machine strings in the cell's own Geist. The entity tables are the Spaces list (Name, Description, Airlocks, Users), the airlock tables of the dashboard and of a space page (Name, Space or Description, Model), the Users tables, the Models tables and the Data sources tables.
- **Chat answer tables:** the chat sets a Markdown table of an answer in the same family: an 8px-radius card with a hairline, the header row in 13px 500 secondary ink, sentence case and never uppercase, cells 16px / 10px with a hairline between rows, values in mono.
- **Empty state:** as GitLab's "There are no variables yet.": an empty table keeps its column heads, then one quiet row across all its columns (`EmptyRow`: 14px 400 tertiary ink, the cells' 16px / 12px padding), never a centred box. "There are no spaces yet.", "There are no airlocks yet.", "There are no users yet.", "There are no models yet.", "There are no data sources yet." (an airlock's Details: "This airlock reads no data sources."). A definition list with nothing to define is the same quiet line alone in its frame. The action over the frame and the page's description say what to do. No icon, no dashed border, no pattern.
- **Banner:** error card (error wash, error border, error text), 16px / 12px, alert icon at 16px, optional secondary action.
- **Notice:** a save stored but not yet in effect (the backend's `AIsolate-Cluster: not applied`): one 14px tertiary line (`Muted`, `role="status"`) at the top of the page the save lands on, gone once the user moves on: "Saved, not in effect yet: AIsolate tries again within five minutes. Until then the previous settings apply." Never red: the save went through.
- **Unreachable dialog:** a desk overlay covering the shell with one paper card (`max-w-md`, 32px / 40px, overlay shadow, centered), a 24px ok or error icon, a 20px title, a sentence, primary and secondary buttons, and a 12px tabular countdown.

### Inputs / Fields

- **Style:** paper ground, control border (`#858585`, 3.69:1 on paper, so the field shows without a placeholder), 4px radius, 8px 12px padding, 14px text.
- **Select:** drawn with `appearance: none` and the kit's own 16px chevron in secondary ink (`theme.css`, every select), inset 12px from the right border as the text is from the left, vertically centred; the text stops 36px from the right, so it never runs under the chevron. Open, it is GitLab's listbox, not the OS menu: where the browser has the customizable select (`appearance: base-select`), a white popover 4px under the field with a hairline border, 8px corners and the overlay shadow, options with 4px corners tinted on hover, the chosen one checked in focus blue; elsewhere the native menu, light because the page declares `color-scheme: light`.
- **Focus:** the field's own border turns focus blue and doubles (a 1px inset shadow in focus blue); no outline, no glow, no dark border underneath. Checkboxes and radios keep the shared focus ring.
- **Error:** the backend's messages in 12px error text under the field, prefix stripped.
- **Form page:** every add or edit is a page of its own (see Pages): `PageHeader`'s `title` (20px 600) and one line of context in 14px secondary ink, then the form; an in-place edit of a label and description sits under the existing entity header and adds no title of its own.
- **Form column:** every form is GitLab's: one `max-w-2xl` column, left-aligned in the canvas, no card round it (`FormColumn`). Fields stack 20px apart, each its label (and help button) over its control, its errors under it; every input, select and textarea fills the column, so every field in a form is the same width. The actions sit under the last field at the column's left edge.
- **Field:** label 14px 500 ink; an optional field appends "(optional)" in tertiary ink, a required field carries no marker: a description is optional, and a data source's user and password turn optional while a stored Secret keeps them ("Leave blank to keep it."), and a model's API key is always optional. No grey help line under a control: where the label alone is not clear, a 14px info icon in tertiary ink follows the label, a 24px button named "About <label>"; hovering it, focusing it or tapping it shows the help in a small tooltip under it (paper, hairline, 8px corners, overlay shadow, 12px ink, at most 28rem, so most help reads on one line), and Escape, blur or leaving closes it; the pointer can cross onto it. The tooltip describes the control (`aria-describedby` names `<id>-error <id>-help`), so a screen reader hears it on the field too. Help only where it adds something: a model's Id ("What aisolate.yaml's llm and the code's aisolate.llm(…) name. It cannot change once saved."), its URL ("The address of the model server, for example http://ollama:11434."; for an OpenAI-compatible one "The server's base URL with its version path, for example https://api.openai.com/v1."), its API key ("Kept as a Secret, never shown again. Leave blank for a server that needs none.", or "Leave blank to keep it." while a stored Secret keeps one), the Model ("The model's name as the provider lists it, for example granite4.1:8b."), a data source's Id, URL and credentials, Add users' search and e-mails; none where the label says it all (Provider, Name, Description). An error (12px error text, 12px alert icon) still appears under the control. No placeholders: an example lives in the help. Every control is the column's full width, whatever its value; a description is a 3-row textarea, as GitLab's project description. A machine string (an id, a URL, model, users) is typed in Geist like any field; the Provider select shows each provider by its word (Ollama, OpenAI-compatible), its value the id the backend reads (`ollama`, `openai`). The API key field (a password input, never filled back) shows for an OpenAI-compatible model alone; switching to Ollama drops a typed key, and a refused save drops it as it drops a typed password. An id fixed once created (a model's or a data source's) is read-only, on a desk ground. A model has no type: no form asks for a chat or an embedding model.
- **Validation:** on blur and on submit, before any request: required fields (a space's or an airlock's id; a model's id, provider, URL and model; a data source's id and URL), each model (an id "lowercase letters, digits, _ or -", once per level: "a model granite already exists") and each data source: an id ("lowercase letters, digits, _ or -", once per owner), a user with a password, a password with a URL unless a stored Secret keeps the credentials; messages in the backend's words, server `field: message` errors mapped onto the same fields (a row's by its index, `data_sources[1].url`, `llms[0].model`; AIsolate's pages get the field alone, `url`, and map it onto the row). A submit with problems sends nothing and focuses the first invalid field.
- **Actions:** `[Save …]` (primary) then Cancel (secondary, bordered, the same height) sit left-aligned under the last field, in the same column, the problem count in error text after them. Cancel and a save that succeeds return to the page the form came from. While saving, the primary reads "Saving…" and is disabled; a save that comes back refused blanks the password typed into the form. Every save names what its form was read at (`If-Match`); one refused because someone saved in between shows the Banner "Someone else changed this; reload to see their changes." with a Reload action, instead of a field error.

### Own and inherited rows

- **Source column:** a space's and an airlock's Models and Data sources pages are one table each under the page's header, its action (Add model, Add data source) alone over the frame, as an airlock's Users page is: the level's own rows first, then on an airlock the space's, then AIsolate's, with a Source column in GitLab's words: "Direct" for its own, else "Inherited from" and the definer's name as a sans chip linking to its page of that list (the space's); the install's own rows read "Inherited from the platform", plain text, no chip (the top-level Models or Data sources), as GitLab's inherited variables show their group. An airlock's Details lists its data sources the same way: its own, the space's and AIsolate's.
- **Replacing:** the same id nearer the airlock replaces the farther one whole, so the table lists each id once, the nearest; that row says so after its Source in 12px tertiary ink ("replaces the platform's", "replaces Acme's"). Each page's one line under its title says it in words: "Its own replaces one with the same id."
- **Users:** an airlock's Users page is one table too: its direct rows, then the space's, as GitLab lists members together with Source "Direct" or "Inherited from <space>"; never "Own" or "From the space".
- **Empty:** the quiet row of an empty table ("There are no models yet.", "There are no data sources yet."), the action still over the frame.
- **Callers:** not part of the UI yet. The API keeps an airlock's callers (`*`, any client, by default); no page shows or edits them, and no link or crumb leads to them.
- **Resolved values:** an airlock's Details shows what resolves, and each value says where from in 12px tertiary ink after it (`granite · granite4.1:8b` then "Inherited from the platform"); a data source's origin is its Source column ("Direct", "Inherited from Resources", "Inherited from Acme", "Inherited from the platform"). An airlock shows the model that answers alone, never the rest of its catalog; an id that names no model it sees reads the backend's sentence in tertiary ink ("no model named granite in this airlock's space or globally"). The space and the airlock are named as such, never as tiers of one another; the level above every space is "AIsolate".

### Tabs

- **Style:** a row of links, 24px apart, on a 1px hairline track (`Tabs`, a `nav` labelled "Airlock"). A tab is 14px with 8px above and below; at rest secondary ink, hovering to ink; the open one is ink at 500 over a 2px pen bar laid across the track, `aria-current="page"`. No counts, no icons, no pill or ground.
- **Routes:** a tab is a page, not state, so the address, the back button and a reload keep it: an airlock's Overview, Details, Tools, Users, Models and Data sources. The airlock's layout loads it once; Overview, Details and Tools share that load, and a section's add or edit page keeps its tab open.
- **Links:** the kit stays free of Next, so the app passes `linkAs={Link}`; a plain anchor would reload the page and lose the in-memory sign-in.
- **Editing:** Edit on an airlock hides the tabs, so the form's save is the page's one primary action.

### Graph canvas

- **Canvas:** the airlock's Overview tab, the one surface with a pattern: a paper ground with a dot grid (a 1px hairline-strong dot every 28px), hairline border, 8px radius, at least 480px tall. The chain sits in the canvas's middle with `m-auto`; one wider than the canvas opens scaled down to fit it (never above 100%, never below 50%), and a chain that still overflows scrolls inside it instead of being cut. Nowhere else carries a dot grid.
- **Chain:** the exchange left to right in the order the code runs it: context preparators, conversation memories, the model that answers (the airlock's main model alone; the other models its code names are not drawn), answer verifiers. Details' Model section names the main model by its model name alone, a Name row (`granite4.1:8b`, "Inherited from the platform"), never its id beside it. Details' Post-model section is a table of the project's verifiers (Verifier, What it does), its header alone when the project has none: no "none" row. No stage labels (Pre-model, Post-model): each node's kind label says what the step is. Nodes are joined by 26px connectors, a 2px hairline-strong line with an 8px port at each node's edge.
- **Node:** at least 192px wide, wider for a longer title up to 320px, paper, hairline-strong border, 8px radius, 12px by 8px padding: a 32px tile in its step's wash with the icon in its hue (file, history, cpu and check at 16px), then its kind in 12px 500 in the hue ("Context preparator", "Memory", "Model", "Verifier") over one title in 13px 500 on one line. A handler's title is its `@Description`, a short title of two to four words ("Read HR documents", "Remember the conversation"), or its humanized class name; the Model node's is always "Answer the question". Nothing else shows on the node: hovering it or focusing it by keyboard opens its details under it, a paper popover with the overlay shadow, 12px secondary ink with values in ink: a handler's Class, and Source when it names its data sources (`data_sources=` in Python, `@DataSources` in Java); the model's Id and Model, or as its Id the backend's sentence when its id names no model the airlock sees. A title past 320px is cut with an ellipsis, whole on hover. Every node takes the tallest node's height.
- **Zoom:** bottom right of the canvas, a 36px paper bar with a hairline and 4px corners: minus, the percentage, plus, then fit (back to the zoom the chain opened at). Steps of 10% from 50% to 200% on the 10% grid (a fitted 79% steps to 80% or 70%), the end buttons disabled, the chain scaled with `transform` from its left edge in 150 ms inside a box of its scaled width, so every zoom scrolls the whole chain; the fit on load is not animated. There is no pan.
- **Before a deploy:** no nodes: one centred sentence, "No code yet." in 600, then where the project names this airlock, in 14px secondary ink with `aisolate.yaml`, `name: <id>` and `aisolate deploy` in ink.

### Audit

- **List** (`/audit`): `PageHeader` "Audit" with "Export as" (secondary, a 14px chevron after it) in its actions, a menu of PDF, JSON and XBRL in the user menu's panel (no descriptions, no dialog; a download of the trail, the one export the UI has, not a release): the pick downloads what the Search and the Period find, every page of it, and the button reads "Exporting…" until the file is there; then Monitoring's one row of two fields above the frame: Search (384px, "User, trace, client, space, airlock or question", any of them, case ignored, on Enter) and Period (Monitoring's choices, All time the default, at once); the address keeps them, and `/audit?trace_id=…` from a trace viewer opens with that id searched, then the framed table, newest first: Time (UTC to the minute, `2026-10-18 09:11`, the second on hover; the link), Space and Airlock (ids, as the filters take them and as an auditor sees them), User and Client (a client id longer than 16 characters cut to its ends, `ff802241…897b`, whole on hover), Outcome (its reason on hover, "Refused · user not listed"), Figures (one sans chip per count that is not zero: sourced and computed on the control border, unsourced in warn, mismatch in error) and Tokens (in / out, `1,840 / 212`); nine columns in the canvas, so its cells sit 12px apart, the frame's 16px at its sides. "Load more" (secondary) under the frame while the backend has another page. Empty: the heads over "There are no audit events for these filters."
- **Event** (`/audit/<id>`, crumb "Audit / Event 1042"): `PageHeader` "Event 1042" with one line (`acme/hr · time · outcome`) and View trace (secondary, a new tab) when `NEXT_PUBLIC_TRACE_URL` names a trace viewer (`{trace_id}` replaced). Then framed Sections: Question; Answer, rendered from its segments with the chat's marks; Tools (the call with its arguments, data source, rows); Details (outcome, time, airlock, user, model and its calls, tokens, duration, verdicts as chips, and Tamper check: a 16px check in ok, "Unchanged since it was recorded", or an alert in error, "Broken at event 517: …"). Under them a closed native disclosure, "Technical details" with a 16px tertiary chevron that turns down: what an auditor checks the record by, never what a reader needs: Chain ("Intact: 1,042 events, 1 to 1042"), Record (its number, when received), Hash, Previous hash ("none: the first record of hr" for the zero hash), Revision, Client, Trace, Evidence, and each tool's rows hash whole.
- **Provenance marks** (the kit's `GroundedAnswer`, `FigureMark`, `QuoteMark`, `TableMark`, the chat's): a figure is underlined, dotted in control for sourced and computed, dotted in warn with a `!` for unsourced, wavy in error with a `✕` for a mismatch; hovering, focusing or tapping it opens an overlay card naming where it came from: Sourced · entity · column · row key and its evidence, or Computed with its operation and evidence; the audit keeps no cell value, so the card names none. A quote is the passage's words in quotation marks and a small marker naming the passage; a table is the answer-table card with a quiet mark on each cell and "From <call>" under it. The prose shows as typed, its line breaks kept.

### Monitoring

- **Tracing** (`/monitoring`, crumbs "Monitoring / Tracing"): `PageHeader` "Monitoring" with "What happened inside each question: every step, how long it took and what it used.", then the tabs (Tracing, and Metrics when the install has it; a tab per view the backend names), then one row of fields: Space and Airlock selects (names, "All spaces", "All airlocks"), Period (Last 10 minutes, Last hour, Last 24 hours, the default, Last 7 days, Last 30 days, All time) and Question, a search field whose placeholder says "Search the questions" (Enter applies; a select at once); the address keeps them. Then the framed table, newest first: Time (the reader's clock today; the day before), Question (the link, 500, at most two lines, the user's e-mail under it in 12px tertiary), Airlock (`hr · v1`), Outcome (a chip: the state's ink on a 10% wash of it, Answered ok, Don't know warn, Refused and Error error, the word always in it), Duration and Tokens (in plus out) right-aligned, Steps in 13px secondary ink ("4 model calls · 3 tools", "admission only" for a refusal). "Load more" under the frame. Empty: "There are no traces for these filters."
- **A trace** (`/monitoring/traces/<trace id>`, crumbs "Monitoring / Tracing / Trace 43e032ff…974f"): "Trace" and the id cut to its ends with a copy icon as the 20px title; one 14px tertiary line (user · space name / airlock · "19 Oct 2026, 10:43:33"); a row of small hairline facts (12px tertiary label over a 14px 600 value: Outcome in its state's ink, Duration, Tokens in · out, Model `granite · granite4.1:8b`). No question or answer above the steps: as MLflow shows a root span's, they are the exchange step's Inputs and Outputs. Then "Steps" with a two-button segmented switch, Steps | Timeline, right-aligned on its line (the on one on panel).
- **Steps:** one frame split 44 / 56: the step tree left (rows 14px, 18px further in per level, a 22px tile with the look's icon on a wash of its hue, the name, the duration right-aligned in 12px secondary ink, a 96 × 6px track with the step's bar where it lies in the trace), the selected row on a focus-blue wash with a 2px focus-blue inset at its left edge; right, the selected step's details: its tile and name at 16px 600, on the exchange, which opens selected, Inputs (`{"question": …}`) and Outputs (`{"answer", "outcome", "reason" when refused, "figures"}`) as pretty JSON on desk, each with Copy, then one 12px tertiary line that they come from the audit record; a framed definition list (Kind, what its attributes say in words, Started +, Took) and for a tool call "Arguments, from the audit record" over the arguments as pretty JSON in mono on desk. A tool call opens selected. The transport between the pods (HTTP, the tool server's endpoint) and a framework's wrapper are left out of the tree; their steps move up. Looks: model calls and embeddings violet, tool calls and governed queries aqua, SQL and a handler that opened a data source yellow (the data-viz reference palette's slots 7, 3 and 4, validated together), every other step neutral with a dot. Text stays in ink; a failed step's duration and Error row are in error ink.
- **Timeline:** the same rows, a 300px name column (the duration after the name in 12px tertiary) and a lane with the bar on a time axis labelled at 0, ¼, ½, ¾ and the whole, hairline gridlines at each.
- **No steps:** a trace the store no longer holds, or a store that did not answer, keeps its header; the Steps frame holds one tertiary sentence ("The trace store no longer holds this trace's steps: it keeps traces for a while only. The audit record is kept.", "The trace store did not answer. Try again in a moment.") over the exchange's Inputs and Outputs.
- **Metrics** (`/monitoring/metrics`, crumbs "Monitoring / Metrics"): the same header and tabs, the Space, Airlock and Period fields (no all time: Prometheus keeps no such span), then the dashboard's five panels as framed Sections, two across from `lg`, each frame its own height: Exchanges per minute by airlock, Tokens per minute by airlock and p95 exchange duration as line charts; Refusals by reason and Tool calls by tool as horizontal bars. Charts are hand-drawn SVG and follow the data-viz method: one axis, round ticks from zero in 10px tertiary ink, hairline gridlines, 2px lines in the reference palette's categorical slots (`--series-1` to `--series-8`, validated), a line's slot the airlock's place among every airlock the reader sees so a filter never repaints the rest, the legend under a chart of two or more lines (a 12px swatch beside each name, the text in ink), a crosshair with an 8px ringed dot per line and a tooltip (paper, hairline, overlay shadow) naming each value under the pointer, and the values as a table for screen readers. Bars are one hue (slot 1), 12px thick with a 4px rounded end, the largest first, what each counts on the left (a tool, a reason in words, the airlock after it in 12px tertiary) and the count at its tip. An empty chart is one tertiary line ("Nothing in this period.", "No refusals in this period.").
- **From the audit:** an event's View trace opens this page when the install has a trace store, else the configured viewer in a new tab.

### Navigation

- **Rail (three contexts):** desk ground, no edge, ink-secondary text at rest; rows keep the nav-row tokens (14px, 4px radius, 8px 12px padding), hovering to panel with ink text. The rail shows the context of the route, one level at a time, never a tree. **Top** (`/`, `/spaces`, `/spaces/new`, `/llms`, `/data-sources`, `/audit`): Dashboard (home icon), Spaces, then for admins and for a developer (who sees a space) AIsolate's Models (cpu icon) and Data sources (database icon), Monitoring (activity icon) when the backend says the install has a view of it, tracing or metrics (`GET /v1/monitoring`), and for admins, `aisolate-auditor` (the ID token's realm roles) and a developer, Audit (scroll icon); an auditor who is neither admin nor developer has nothing else to use, so their rail is Audit alone and `/` sends them to it; 18px icon with 12px gap, each lit anywhere under its path (`/spaces`, `/llms`, `/data-sources`, `/audit`). **Space** (a space's pages): the context title "Space" (14px 600 ink, 8px more above it than the rows have, as GitLab's "Project" title over its sidebar), the space's own row (its 24px tile and name at 500 in ink, 8px gap, `title` holding the whole name), then Airlocks, Users, Models and Data sources 16px further in (the space's airlocks on a page of their own, with New airlock), each with its 18px icon. **Airlock** (an airlock's pages): the label "Airlock", the airlock's own row, then Users, Models and Data sources. The row whose page is open is lit, pen-wash with pen 500 text (`aria-current="page"`); a section's add and edit pages light the section (Users on Add users), and the entity's own row is lit when no section is (the space page, New airlock, an airlock's Overview, Details and Tools). Entity rows carry the name from the overview, the id until it arrives (`useOverview`, the 30 s cache the list pages read; every form calls `reloadOverview()` after a save). Collapsed (56px), the same rows show their icons alone (the tile for the entity) with the label as `title`; the context label fades but keeps its line, so the rows do not move. No colored bar, no left edge. The wordmark is the logo's own name, after the mark; collapse toggle top right.
- **Brand mark** (`AIsolateLogo`, the designer's logo as the website serves it, `public/logo/aisolate-black.png` (788 × 72), a plain `<img>` with the alt "AIsolate" inside its Homepage link, which carries the label "Homepage"): 14px tall and 153px wide, at the top of the rail and in the mobile bar, vertically centred. Folded, its box narrows to 40px and crops the image to the mark (its first 36px), so the rail shows the mark alone; the expand toggle lies over it and shows on hover or keyboard focus. The favicon (`app/icon.svg`) keeps the geometric "AI⬡" mark, centred in a square; the header shows the designer's wordmark.
- **Collapse motion:** width, gap and padding transition over 200 ms ease-out; labels fold via `max-width` and opacity in the same curve.
- **User menu:** the light avatar at the right end of the top bar, 16px from the panel's edge (the same avatar in the mobile header); opens a native `popover` card (14rem, overlay shadow) anchored under it at the right edge with the user's name at 600, the email under it in 12px tertiary ink, and a Sign out row, nothing else.
- **Mobile:** a white bar with a hairline bottom edge, the mark and wordmark and compact avatar, then the current context's rows as a wrapping row of 4px-radius links (6px 12px, 16px icon): the top rows as the rail's; in a space its own row (tile and name, truncated with a `title`), Users, Models and Data sources; in an airlock its row, then Users, Models and Data sources. The lit link is pen-wash with pen text. Without them the new pages would be out of reach on a phone.
- **Tab title:** the product, then the page (`AIsolate | Spaces`, `AIsolate | HR contracts`): each page's `PageHeader` sets it from its title, the dashboard and a trace, which have none, set their own (`useTabTitle`); a title still loading leaves `AIsolate`, so the UI's tabs read apart from the website's.
- **Breadcrumbs:** as GitLab's, in the 48px top bar, 16px from the panel's left edge: 14px tertiary ink (`#666`, 5.7:1 on paper; GitLab's `#626168` is a step darker) with "/" separators 8px either side; links hover to ink and underline, the current crumb semibold (600) in ink and, on the dashboard, the page's `<h1>`. Below `sm` only the last two crumbs show; the current crumb never truncates. A deep trail reads `Spaces / Acme / Airlocks / Resources / v1` or `Spaces / Acme / Airlocks / Resources / Users / Add users`, AIsolate's `Models / Edit model`; the "Airlocks" crumb lands on the space's page, where its Airlocks section is.

### Pages

Every list, add and edit is a page of its own with its own breadcrumb, never a dialog. A section page opens with its header (title and description only), the Edit, Add or Set action right-aligned above its section's frame; that action goes to a sibling page that opens with a title and one line of context, then a short form, then Save and Cancel; Cancel and a save that succeeds return to the section page. A person who may not change things sees the same pages without the Edit, Add and New actions, the role select and the trash: what one may change is the space's `caller_role` (the admin all of it, a developer its airlocks), so a developer sees a space's own Users, Models and Data sources read-only, and New space and AIsolate's Models and Data sources are the admin's to change and every developer's to read. A user of a space only asks: the Spaces list and the dashboard show them none, the quiet empty row, no error.

- **Spaces:** `/spaces` lists them (New space); `/spaces/new` asks a name and a description; the backend makes the id from the name. `/spaces/<space>` is the space's header (Edit changes the name and description in place and shows the Space URL read-only, as GitLab's group ID) over its Airlocks section (New airlock).
- **A space's parts:** `/spaces/<space>/users` lists its users, each own row with Remove; `/users/add` is Add users: a search field that asks the company's directory as one types (250 ms after the last keystroke), the people it finds as rows of a framed list (checkbox, tile, name, e-mail; those already users ticked and disabled with "Already a user" or "From the space"), the ticked ones named under it, on a space a Role select (User, or Developer), then Add (primary) and Cancel. With no directory connected the page is one E-mails textarea, one per line, under the line "No directory connected: type sign-in e-mails." AIsolate never creates a person: they live in the identity provider. `/llms` lists the models it sees (its own and AIsolate's), `/llms/new` and `/llms/<id>/edit` take one of its own (Id, Provider, URL, Model, and API key for an OpenAI-compatible one); `/data-sources` lists its data sources and AIsolate's, `/data-sources/new` and `/data-sources/<id>/edit` take one row; the trash removes an own row at once, with no dialog, and a model's and a data source's are the only deletes in the AIsolate UI. A refused remove ("acme/hr and acme/sales would have no model granite; define it on their airlock first") reads in the banner, its field prefix dropped.
- **Airlocks:** `/spaces/<space>/airlocks/new` asks a name and a description, the id made from the name; a new airlock starts with no users of its own. `/airlocks/<airlock>` is its header (Copy endpoint, Edit in place) over its tabs: Overview (the graph), Details, Tools, Users, Models and Data sources.
- **An airlock's parts:** `/users` shows its own users and those it inherits from the space in one table; `/users/add` adds its own users as the space's page does. `/llms`, `/llms/new`, `/llms/<id>/edit`, `/data-sources`, `/data-sources/new` and `/data-sources/<id>/edit` work as the space's, the space's rows inherited before AIsolate's.
- **An airlock's code:** `/airlocks/<airlock>`, `/details` and `/tools` show what it runs, read-only; Edit under its header changes only the name and description. Its code, stages, tools and prompt are never edited in the UI: `aisolate deploy` brings them. An airlock whose newest exchange that called a tool could not read a data source does not look fine: a banner under its header says which, why, when and what to check ("Data source hr: the database refused the login, at 10:43. Check its password on the Data sources page.", or "…could not be reached… Check its URL…"), read from the audit (`GET /v1/audit/data-error`); the next exchange that reads the data clears it.
- **AIsolate's models and data sources:** `/llms` lists the models every space sees (Id, Provider, Model; Add model, the pencil and the trash for the admin), `/llms/new` and `/llms/<id>/edit` take one (its Id read-only once saved); `/data-sources`, `/data-sources/new` and `/data-sources/<id>/edit` the same for data sources, as a space's. A developer of any space reads them. A refused delete ("acme/hr would have no model granite; define it on its space first") reads in the banner.
- **Titles:** "Add users", "Add model", "Edit model", "Add data source" and "Edit data source", and the last crumb the same.

## Do's and Don'ts

### Do:

- **Do** open every page but the dashboard with `PageHeader`: entity pages carry the tile, the 20px title, the description and the actions; a list or section page carries its title and one line of what it holds, its actions above its sections' frames; a create, add or edit page its title and one line of context. The dashboard renders none: the top bar's trail comes from the route (`trailOf(pathname)`), and its last crumb is its `<h1>`.
- **Do** list entities as a `Section` + `Table`, the Section's frame round the table alone and its action right-aligned above it, outside the border, no count: Name first as the link, every cell 14px 500 ink, column heads 13px 500 secondary.
- **Do** build every form on `FormColumn`: one `max-w-2xl` column, every control its full width, a 3-row description, Save then Cancel under the last field.
- **Do** set every id, URL, endpoint, tool, handler, user e-mail, rule, model, data source and context preparator in Geist at the size of the text around it; Geist Mono only in a code block.
- **Do** derive a tile's wash from `tileHue(id)`; never pick a tile color by hand.
- **Do** divide with 1px hairlines and `divide-y`; a section's frame is its only box, and nothing nests cards.
- **Do** give every button the one 32px height and a visible border.
- **Do** keep the pen to one primary action per page, the open tab, the lit rail row and link hover.
- **Do** keep motion to color settles at 150 ms, the graph's zoom at 150 ms and the rail fold at 200 ms ease-out.
- **Do** set every column head, label, chip and tab in sentence case.
- **Do** keep to the AIsolate UI's words: AIsolate (the level above every space), space, airlock, Users, Models, Data sources, Overview, Details, Tools, and an airlock's code, which `aisolate deploy` brings. A model has no type: never "chat model" or "embedding model". A value the one shown takes from further up reads "from the space" or "from <airlock name>", never a word for passing down; code is "code", never its packaging; a data source is never a source of knowledge; never "file", "MCP", "scope" or "tool server".
- **Do** list an airlock's tools by name, as its code publishes them, and call its stages Pre-model ("Context preparator", "Conversation memory"), Model and Post-model ("Answer verifier"); a context preparator is never a "knowledge base"; the tool server and its URL are configuration, never a page, a field or a nav item.
- **Do** list a level's own models and data sources first and what it inherits after, read-only, in one table with a Source column (see Own and inherited rows).
- **Do** give every list, add and edit its own page and breadcrumb, and every tab its own route.
- **Do** keep the dot grid on the graph canvas; it is the one pattern in the AIsolate UI.

### Don't:

- **Don't** add KPI cards beyond the dashboard's three ledger cards.
- **Don't** open a page on a table of machine strings; the tables are the Spaces list, the dashboard's airlock list, a space's Airlocks section, AIsolate's Models and Data sources and the section pages' Users, Models and Data sources.
- **Don't** shadow a card, row list, table or panel; only the popover and the unreachable-backend dialog cast a shadow.
- **Don't** put a colored bar or left edge on the active nav row; the pen-wash ground and pen text are the whole signal.
- **Don't** use red outside the error Banner, field errors, the form's problem count and the unreachable-backend alert.
- **Don't** use a tile wash as text, border or accent; it is a ground behind one letter.
- **Don't** ship raster images or icon fonts; icons are inline SVG strokes at 1.75.
- **Don't** show a user's realm roles anywhere: the one role the UI shows is a space's (Developer, User), in its Users table. A space's users are Users, never "Members".
- **Don't** put a subtitle or an id line under an entity page's title; the description is the entity's own, a create page's one context line is the only other line, and an id shows only in an airlock's endpoint.
- **Don't** write marketing copy or a hero inside the AIsolate UI.
- **Don't** set anything uppercase, letter-spaced open, italic or dashed; a heading's negative tracking is the only exception to plain spacing.
- **Don't** add "All …" links beside a section heading; the rail is the way to the full list.
- **Don't** decorate an empty state with an icon, a dashed border or a dot grid; the graph canvas's dot grid belongs to the graph alone.
- **Don't** put a link inside a link row; the row is the link.
- **Don't** fold a part of an entity behind a disclosure or a closed card; an airlock's handlers, model, tools and data sources each sit on a tab, a space's parts and an airlock's users, models and data sources on a page. A tool's full description under its lead is the one disclosure: text about a row, not a part.
- **Don't** add an Import or Apply-to-cluster action, or an export of configuration; every save applies at once. The Audit list's Export as downloads the trail, it releases nothing.
- **Don't** open a dialog for an add or an edit, or put a confirmation behind the trash; the page is the dialog, and a model's or a data source's row is the only thing the UI removes.
- **Don't** edit an airlock's code, stages or tools in the AIsolate UI; they are `aisolate deploy`'s to bring.
