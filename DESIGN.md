---
name: 151 Viewer
description: A tabbed reference manual for every document on the phone; offline, private, open at the right section.
colors:
  ink: "#1C1B18"
  ink-muted: "#5C574C"
  board: "#DAD2BF"
  acetate: "#F5F1E6"
  acetate-leaf: "#E8F5F1E6"
  acetate-raised: "#FBF9F3"
  acetate-sunk: "#EAE4D5"
  row-selected: "#1A1C1B18"
  seam: "#CEC6B2"
  seam-strong: "#8E8676"
  vermilion: "#C4321B"
  vermilion-wash: "#F8E4DC"
  on-hue-dark: "#1C1B18"
  on-hue-light: "#FBF8EF"
  hue-all: "#DAD2BF"
  hue-pdf-oxide: "#DB5A1F"
  hue-word-ultramarine: "#2B4AAE"
  hue-excel-meadow: "#3B8A37"
  hue-ppt-rose: "#BF3E67"
  hue-text-violet: "#6A3FBD"
  hue-archive-chrome-yellow: "#EBB21D"
  hue-other-slate: "#676A6F"
  hue-audio-moss: "#6C7D1C"
  night-ink: "#EEE9DC"
  night-ink-muted: "#ADA697"
  night-board: "#131311"
  night-acetate: "#1F1E1B"
  night-acetate-leaf: "#EB1F1E1B"
  night-acetate-raised: "#292824"
  night-acetate-sunk: "#181715"
  night-seam: "#36342F"
  night-seam-strong: "#6E695E"
  night-vermilion: "#FF7A5E"
  night-vermilion-wash: "#3A1E17"
  night-hue-all: "#2C2A26"
typography:
  display:
    fontFamily: "Archivo Condensed, Archivo, system-ui, sans-serif"
    fontSize: "27sp"
    fontWeight: 700
    letterSpacing: "0.02em"
  headline:
    fontFamily: "Archivo Condensed, Archivo, system-ui, sans-serif"
    fontSize: "25sp"
    fontWeight: 700
    lineHeight: "29sp"
    letterSpacing: "0.005em"
  title-large:
    fontFamily: "Archivo, system-ui, sans-serif"
    fontSize: "22sp"
    fontWeight: 700
  title:
    fontFamily: "Archivo, system-ui, sans-serif"
    fontSize: "17sp"
    fontWeight: 600
  title-small:
    fontFamily: "Archivo, system-ui, sans-serif"
    fontSize: "15sp"
    fontWeight: 600
  body:
    fontFamily: "Archivo, system-ui, sans-serif"
    fontSize: "16sp"
    fontWeight: 500
  prose:
    fontFamily: "EB Garamond, Georgia, serif"
    fontSize: "18sp"
    fontWeight: 400
    lineHeight: "25sp"
  label:
    fontFamily: "Archivo, system-ui, sans-serif"
    fontSize: "15sp"
    fontWeight: 600
    letterSpacing: "0.01em"
  tab:
    fontFamily: "Archivo Condensed, Archivo, system-ui, sans-serif"
    fontSize: "14sp"
    fontWeight: 700
    letterSpacing: "0.04em"
  meta:
    fontFamily: "JetBrains Mono, Roboto Mono, ui-monospace, monospace"
    fontSize: "12sp"
    fontWeight: 400
  badge:
    fontFamily: "JetBrains Mono, Roboto Mono, ui-monospace, monospace"
    fontSize: "11sp"
    fontWeight: 500
rounded:
  cut: "4dp"
  leaf: "6dp"
spacing:
  hair: "3dp"
  xs: "4dp"
  sm: "8dp"
  md: "12dp"
  lg: "16dp"
  slip: "22dp"
  xl: "24dp"
  xxl: "32dp"
components:
  button-primary:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.acetate}"
    typography: "{typography.label}"
    rounded: "{rounded.cut}"
    padding: "0 22dp"
    height: "48dp"
  button-outlined:
    backgroundColor: "transparent"
    textColor: "{colors.ink}"
    typography: "{typography.label}"
    rounded: "{rounded.cut}"
    height: "48dp"
  button-text:
    backgroundColor: "transparent"
    textColor: "{colors.ink}"
    typography: "{typography.label}"
    rounded: "{rounded.cut}"
  button-irreversible:
    backgroundColor: "transparent"
    textColor: "{colors.vermilion}"
    typography: "{typography.label}"
    rounded: "{rounded.cut}"
  fab-open-file:
    backgroundColor: "{colors.hue-archive-chrome-yellow}"
    textColor: "{colors.on-hue-dark}"
    typography: "{typography.label}"
    rounded: "{rounded.leaf}"
  section-tab:
    backgroundColor: "{colors.hue-pdf-oxide}"
    textColor: "{colors.on-hue-light}"
    typography: "{typography.tab}"
    padding: "0 10dp 0 8dp"
    height: "54dp"
  section-board:
    backgroundColor: "{colors.hue-pdf-oxide}"
    textColor: "{colors.on-hue-dark}"
    typography: "{typography.meta}"
    padding: "0 8dp"
  acetate-leaf:
    backgroundColor: "{colors.acetate-leaf}"
    textColor: "{colors.ink}"
  doc-row:
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    padding: "9dp 12dp"
    height: "64dp"
  section-badge:
    backgroundColor: "{colors.hue-pdf-oxide}"
    textColor: "{colors.on-hue-dark}"
    typography: "{typography.badge}"
    rounded: "{rounded.cut}"
    size: "44dp"
  search-field:
    backgroundColor: "{colors.acetate-raised}"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.cut}"
    height: "48dp"
  slip:
    backgroundColor: "{colors.acetate}"
    textColor: "{colors.ink}"
    rounded: "{rounded.leaf}"
    padding: "24dp 22dp 22dp"
  slip-errata:
    backgroundColor: "{colors.vermilion-wash}"
    textColor: "{colors.ink}"
    rounded: "{rounded.leaf}"
    padding: "24dp 22dp 22dp"
  toolbar:
    backgroundColor: "{colors.acetate}"
    textColor: "{colors.ink}"
    typography: "{typography.display}"
  viewer-section-band:
    backgroundColor: "{colors.hue-pdf-oxide}"
    height: "4dp"
  snackbar:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.acetate}"
    typography: "{typography.body}"
    rounded: "{rounded.cut}"
---

# Design System: 151 Viewer

## Overview

**Creative North Star: "The Tabbed Reference Manual"**

The library is a reference manual lying open at the right section. Every document type is a full-hue index tab printed on card stock; the chosen tab rises, and its section becomes the board, a flat field of that hue. On the board rests a single sheet of milk acetate (about 91% opaque, so the section colour breathes through) carrying the files as ruled lines. The viewer screen carries the same section as a 4dp band under its toolbar, so a PDF opened from WhatsApp is still visibly "in the oxide section". When a file cannot be shown, a paper slip lies on the board and says why and what to do next.

The world is paper, card, acetate and ink: flat, cut, ruled. Depth comes from stacking opaque and translucent sheets, never from soft light. Colour is reserved for meaning: neutrals carry the chrome, the eleven section hues identify document types, and vermilion appears only when something has actually gone wrong or cannot be undone. Three typefaces divide the labour exactly: **Archivo speaks** (names, commands, titles), **Garamond explains** (every sentence the app says to you), **Mono measures** (counts, sizes, dates, paths, MIME types).

The document is the hero. Inside the WebView the chrome follows this system, but rendered Markdown deliberately stays Obsidian-faithful (its own palette, callouts and 3px blockquote rules), because many people read their notes there daily.

**Key Characteristics:**
- Full-hue section tabs; the chosen section floods the board.
- One milk-acetate leaf on the board, ruled with 1dp seams.
- Archivo speaks, Garamond explains, Mono measures.
- Cut corners (4dp) and leaf corners (6dp); 1dp seams; no soft shadows.
- Vermilion is the errata colour: errors and irreversible actions only.
- Night turns the board to ink and lowers every hue (72% hue + 28% night board).
- Motion is a two-step 90ms hinge with no easing curve.

## Colors

A warm card-and-ink neutral world, eleven saturated section hues that each mean one document type, and one alarm colour held in reserve.

### Primary
- **Ink** (`ink`): the action colour. Primary buttons, the tab indicator under «Documenti | Recenti», outlined-button strokes, the search field stroke, snackbars, progress spinners. Material's `colorPrimary` is ink, not a brand hue: there is no Material You and no dynamic colour.

### Secondary
- **The Section Hues** (`hue-*`): Oxide for PDF, Ultramarine for Word, Meadow for Excel, Rose for PowerPoint, Moss for audio, Violet for «Testo» (plain text, Markdown, web pages and code), Chrome Yellow for archives, Slate for «Altro» (mail, ebooks, comics, drawings and everything else). Tab order: Tutti, PDF, Word, Excel, PowerPoint, Audio, Testo, Archivi, Altro; spreadsheets from any suite sit with Excel, text documents with Word. `hue-all` («Tutti») is the board neutral itself. A hue appears on the section tab, the row badge, the board, the viewer band and the slip badge, and nowhere else.
- **Chrome Yellow** (`hue-archive-chrome-yellow`) doubles as the app's one standing accent: the «Apri un file» FAB, Material `colorPrimaryContainer`/`colorTertiary`, the snackbar action text, and text selection highlight (`#66EBB21D`).

### Tertiary
- **Vermilion** (`vermilion`) with **Vermilion Wash** (`vermilion-wash`): the errata slip (wash fill, 1dp vermilion stroke, vermilion badge with acetate text), the crash screen headline, the «Elimina definitivamente» dialog button, and the web `.error` box.

### Neutral
- **Board** (`board`): window background, the «Tutti» board, the surface the viewer content and slips lie on, the system splash.
- **Acetate** (`acetate`): toolbars, the top tab row, the library tools strip, slips. Material `colorSurface`.
- **Acetate Leaf** (`acetate-leaf`, ≈91% alpha; night ≈92%): only the leaf panel that rests on a section board.
- **Acetate Raised** (`acetate-raised`): search fields, dialogs, popup menus, the in-viewer search bar, web chrome buttons.
- **Acetate Sunk** (`acetate-sunk`): Material `surfaceVariant` / secondary container.
- **Ink Muted** (`ink-muted`): Meta lines, hints, unselected tab text, empty-state prose.
- **Seam** (`seam`) and **Seam Strong** (`seam-strong`): every 1dp rule and border; strong is the Material outline.
- **On-Hue Dark / Light** (`on-hue-dark`, `on-hue-light`): text on a hue, chosen per hue at runtime by highest contrast (`Sections.onHue`), never hand-picked.

Night (`night-*`): the board becomes ink (`night-board`), acetate becomes smoked (`night-acetate`), ink becomes warm paper. The section hues are lowered in `values-night/colors.xml` to 72% hue + 28% night board (e.g. PDF `#A3461B`, Word `#243B82`, Archive `#AF851A`); those values live in the sidecar's `colorMeta.*.night`.

### Named Rules
**The Section Hue Rule.** Each document type owns exactly one hue, the same on tab, badge, board, viewer band and slip badge. `Sections.kt` is the single native source; web chrome repeats it as `--h-*` in `fonts.css`. Never use a section hue decoratively or for a second meaning.

**The Errata Rule.** Vermilion means "this went wrong" or "this cannot be undone". A format the app simply does not open, photos/videos sent to the Gallery, and the storage invite get the plain acetate slip; only a real read error (`errata = true`) gets the vermilion slip.

**The Lowered Night Rule.** At night the leaf carries the screen, not the board: every hue is mixed 72/28 with the night board. Never ship a full-strength daytime hue on a night board.

## Typography

**Display Font:** Archivo Condensed Bold (with Archivo, system-ui)
**Body Font:** Archivo 500/600/700 (with system-ui)
**Prose Font:** EB Garamond Regular (with Georgia)
**Label/Mono Font:** JetBrains Mono 400/500 (with Roboto Mono)

**Character:** A sturdy grotesque that labels and commands, a book serif that talks like a person, a monospace that reads like a ruler. All four are OFL and bundled (`tools/fetch-fonts.sh`); all sizes are in sp so they follow the system font scale.

### Hierarchy
- **Display** (Archivo Condensed 700, 27sp, +0.02em, all caps): the library toolbar title «151 VIEWER» only.
- **Headline** (Archivo Condensed 700, 25sp/29sp): slip titles («Non riesco a leggere il file»), dialog titles, the invite title, the crash title, the APK app name. Balanced line breaks.
- **Title Large** (Archivo 700, 22sp): Material TitleLarge slot.
- **Title** (Archivo 600, 17sp): the viewer toolbar's file name.
- **Title Small** (Archivo 600, 15sp): Material TitleSmall slot, small section heads such as «Permessi richiesti».
- **Body** (Archivo 500, 16sp): file names in rows, search input, menu and list-dialog items, snackbar text, values in data sheets.
- **Prose** (EB Garamond 400, 18sp/25sp): every explanatory sentence: slip bodies, dialog messages, empty states, the storage-access reason, the APK footnote.
- **Label** (Archivo 600, 15sp, +0.01em, sentence case): buttons, FAB, top tabs.
- **Tab** (Archivo Condensed 700, 14sp, +0.04em, caps set in code): section tab names; the count follows in Mono at 0.92 scale («PDF  5»).
- **Meta** (JetBrains Mono 400, 12sp): the board's count line («5 file · data di modifica»), row metadata (path · date · size), toolbar subtitles (MIME · size), trash expiry lines, field labels in data sheets, stack traces.
- **Badge** (JetBrains Mono 500, 11sp; 13sp on the slip badge): the extension inside a section badge, max 4 letters, uppercase.

### Named Rules
**The Three Voices Rule.** Archivo speaks, Garamond explains, Mono measures. A number, size, date, path or MIME type is Mono; a full sentence addressed to the user is Garamond; a name or command is Archivo. List dialogs (file menu, recents) are commands, so their items are Archivo, not Garamond.

**The No Theme FontFamily Rule.** Never set `android:fontFamily` in the theme: it acts as a view attribute and overrides every text appearance. Fonts are carried by the `TextAppearance.Viewer.*` styles.

## Layout

Phone, one hand, portrait first. The library is a vertical stack: acetate toolbar, top tabs «Documenti | Recenti» (48dp, 1dp seam below), an acetate tools strip (search field inset 12dp, then the horizontally scrolling section tab row 12dp below it), then the board filling the rest. The board has 8dp side padding; its Meta count line sits at 9dp top and bottom padding; the leaf fills the remainder, open at the bottom edge. «Recenti» reuses the same sheet grammar on the neutral board (slip-backed list, 8dp sides, 96dp bottom clearance for the FAB).

The tab row has 8dp outer padding, 3dp between tabs, a 28dp horizontal fading edge, and always reveals the chosen tab plus its whole neighbours so no tab is cut mid-word.

Rows are 64dp minimum, 12dp horizontal and 9dp vertical padding, a 44dp badge, 14dp gap to the text, 3dp between name and Meta line. Names ellipsize in the middle; paths ellipsize at the start.

Slips centre on the board with 16dp sides and 24dp top/bottom; inside, 22dp sides, 24dp top, 22dp bottom. The viewer stacks toolbar, 4dp section band, optional search bar (acetate raised, 48dp), then content on the board. The FAB sits 20dp from the bottom-right corner. Spacing steps that recur: 3, 4, 8, 12, 16, 22, 24, 32dp. Touch targets are 48dp minimum (search buttons 44×48dp).

## Elevation & Depth

Flat by construction. Depth is stacking: board under leaf, leaf under row, slip on board. The translucent leaf lets the section hue show through so the stack reads without any shadow. Every elevation the platform would add is removed: FAB elevation and pressed/hovered translationZ are 0, tabs have elevation 0 and no state-list animator. Separation is drawn with 1dp seams instead.

### Named Rules
**The Flat Stack Rule.** No soft shadows on app chrome. A surface sits above another because it is a different sheet (acetate on board, slip on board) or has a 1dp seam or ink stroke, never because it casts light. The FAB is flat chrome yellow with a 1dp ink stroke.

**The Leaf Lines Rule.** Rows on the leaf are ruled with a 1dp seam that starts at 70dp (after the badge) and runs to the right edge; the badge column stays unruled, as on a printed index.

## Shapes

Small, paper-cut corners. **Cut** (4dp): buttons, search field, badges, snackbars, popup menus, the leaf's top corners. **Leaf** (6dp): slips, dialogs, the FAB. **Tab** (6dp top, 0dp bottom): section tabs, so they fuse into the board below. The leaf has 4dp top corners and an open, square base: it continues off the bottom of the screen. Borders are always 1dp (seam for sheets, ink for interactive outlines, vermilion for errata); the focused search field steps to 2dp ink. The punch hole is a 10dp acetate circle with a 1dp dark rim: the only round form in the chrome, and it marks the selected tab.

## Components

### Buttons
Ink on paper, firm and plain.
- **Shape:** cut corners (4dp), 48dp minimum height.
- **Primary:** ink fill, acetate text and icon, Label type, 22dp side padding. Used for the main next step on a slip («Apri con…»), «Concedi l'accesso».
- **Outlined:** transparent, 1dp ink stroke, ink text. The secondary step on a slip («Mostra come testo»).
- **Text / Dialog:** ink text, no fill. Dialog buttons are all text buttons.
- **Irreversible:** the dialog's positive button turns vermilion only when the delete is permanent («Elimina definitivamente»); a trash-able delete stays ink.

### Section Tabs (Chips)
- **Style:** full section hue fill, no stroke, on-hue text chosen by contrast, Tab type with the count in Mono, 54dp tall, 6dp top corners, square bottom.
- **State:** single-select, selection required. The chosen tab sits at 0 offset and shows the punch hole; the others drop 6dp behind. Ripple is a dark (`#29000000`) or light (`#33FFFFFF`) wash depending on the on-hue ink.
- **Motion (The Hinge):** on selection the tabs move and the board changes hue in two steps over 90ms (half at once, then the rest; the board colour switches at the midpoint), no easing curve, like a sheet turning on its rings. With Android's «Rimuovi animazioni» the change is instant.

### Board and Leaf
- **Board:** a flat field of the chosen section hue (neutral for «Tutti»); its Meta count line takes the on-hue ink.
- **Leaf:** `acetate-leaf` fill, 4dp top corners, open base, holding the RecyclerView with 4dp top padding.

### Document Row
- 44dp section badge (cut corners, hue fill, extension in Badge type), Body file name in ink, Meta line in ink muted (path · date · size). Ripple is the theme highlight (`#1F7F735C`). Recents rows use the identical grammar. Long press selects the row: it takes the `row_selected` ink veil (activated state) and a 48dp checkbox appears at the end of every row; tap toggles, long press on any checkbox selects all visible rows or clears them. While selecting, the toolbar title reads «N SELEZIONATI» with a close icon, the FAB hides, and the actions are Elimina (always) plus Condividi and Mostra percorso for a single file. Changing tab, section or search clears the selection.

### Inputs / Fields
- **Style:** outlined dense box, acetate-raised fill, 1dp ink stroke, cut corners, ink-muted search icon, hint and clear icon, Body type, 48dp.
- **Focus:** stroke steps to 2dp ink.
- **In-viewer search:** a borderless input on an acetate-raised bar, with a Mono match count and 44×48dp up/down/close buttons.

### Navigation
- **Toolbar:** acetate. In the library the title is Display «151 VIEWER»; in the viewer the title is the file name (Title) with a Meta subtitle (MIME · size).
- **Top tabs:** acetate, Label type, ink muted unselected and ink selected, 3dp ink indicator at text width, 96dp minimum width, 1dp seam beneath.
- **Viewer section band:** 4dp of the document's section hue directly under the toolbar.

### Slip and Errata Slip (Signature)
The app's single voice for anything that is not a document: unsupported format, photo or video, APK sheet context, storage invite, crash trace.
- **Slip:** acetate fill, 1dp seam stroke, 6dp corners. Contents in order: 56dp section badge (cut corners), Headline title, Body file name (selectable), Meta line (MIME · size · detail, selectable), 1dp seam rule, Garamond explanation, primary button, then outlined button 8dp below.
- **Errata Slip:** the same slip with vermilion wash fill and a 1dp vermilion stroke; the badge turns vermilion with acetate text. Only for real failures.
- **Web chrome:** `.error` mirrors the errata slip (wash, 1dp vermilion, 6dp, 20px/22px padding, Archivo 700 title, Garamond 18px detail).

### Dialogs, Menus, Snackbars
- **Dialogs:** an acetate-raised leaf (6dp), Headline title, Garamond message; list dialogs use Archivo Body items.
- **Popup menus:** acetate raised, 1dp seam, 4dp corners, Body type.
- **Snackbars:** ink fill, cut corners, acetate Body text, chrome-yellow Label action («Annulla» after Elimina).

### Web Chrome (WebView pages)
Web tokens mirror `colors.xml` as CSS custom properties in `fonts.css` (`--board`, `--acetate`, `--ink`, `--seam`, `--vermilion`, `--h-*`; `--ui`, `--ui-cond`, `--prose`, `--meta`). Paged documents (Word, ODT, slides, comics) sit on `--board`. The sticky back bar and archive/attachment lists use acetate, 1dp seams, Mono sizes, and section-hue extension badges (`colorOf` in `fmt/listing.mjs`). Rendered Markdown keeps its Obsidian palette and devices; it is content, not chrome.

## Do's and Don'ts

### Do:
- **Do** give every new document type a single section hue in `colors.xml`, `values-night/colors.xml` (72% hue + 28% night board), `Sections.kt` and `fonts.css` together.
- **Do** pick text on a hue with `Sections.onHue` (contrast-driven), never by eye.
- **Do** write the extension on every badge (max 4 letters): a type must be readable without colour.
- **Do** route every failure to a slip with a Headline, a Garamond reason and a next-step button.
- **Do** keep chrome flat: 1dp seams or ink strokes, elevation 0, cut (4dp) or leaf (6dp) corners.
- **Do** set counts, sizes, dates, paths and MIME types in JetBrains Mono.
- **Do** keep all text in sp and every target at 48dp minimum.
- **Do** keep rendered Markdown Obsidian-faithful, including its callout colours and 3px left rules on callouts, blockquotes and embeds.

### Don't:
- **Don't** use vermilion for anything that is not an error or an irreversible action, including merely unsupported formats.
- **Don't** reuse a section hue as decoration, an accent, or a second meaning.
- **Don't** add soft drop shadows, elevation or translationZ to app chrome; the FAB stays flat with its 1dp ink stroke.
- **Don't** set `android:fontFamily` in the theme or use Material You dynamic colour.
- **Don't** use Garamond for commands or list items, or Archivo for explanatory sentences.
- **Motion**: one mode, always on, light — 150–200 ms, decelerate; the tab hinge stays 90 ms; with «Remove animations» everything is instant; helper `Motion` in `Widgets.kt`. No extreme effects.
- **Don't** restyle Markdown content to the manual palette; the system governs chrome only.
