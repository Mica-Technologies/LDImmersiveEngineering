# Engineer's Manual System

Technical documentation for the in-game manual framework used by the Engineer's Manual
(Forge 1.12.2). The framework is a reusable book engine in `blusunrize/lib/manual`,
specialized for IE in `blusunrize/immersiveengineering/client/manual`.

## Overview

The manual is a structured book of **entries**, grouped into **categories**. Each entry
is an ordered array of **pages**, and each page is an `IManualPage` that knows how to lay
itself out and render itself inside the manual GUI. The framework also maintains a
recipe→page index so a player can shift-look at any item and jump to the page that
documents it.

```
ManualInstance (the book)
 └── ArrayListMultimap<category, ManualEntry>
      └── ManualEntry { name, category, IManualPage[] pages }
           └── IManualPage  (Text / Image / Crafting / Multiblock / ...)
```

Key files:

| File | Role |
|---|---|
| `lib/manual/ManualInstance.java` | abstract book: content store, formatting, recipe index |
| `lib/manual/IManualPage.java` | page interface contract |
| `lib/manual/ManualPages.java` | base page class + all generic page types |
| `lib/manual/ManualUtils.java` | text rendering, search spell-check, render helpers |
| `lib/manual/gui/GuiManual.java` | the rendered book screen |
| `lib/manual/gui/GuiButton*` / `GuiClickableList.java` | buttons, links, table of contents |
| `client/manual/IEManualInstance.java` | IE's concrete book + text-tag parser |
| `client/manual/ManualPageShader.java` | IE shader-catalog page type |
| `api/ManualPageMultiblock.java` | IE animated 3D multiblock page type |
| `api/ManualHelper.java` | static API facade + category constants |

---

## ManualInstance — the book

Abstract base (`ManualInstance.java`). A subclass supplies fonts, colours, and
formatting; the base owns the content and the index.

### Content model

- `manualContents : ArrayListMultimap<String, ManualEntry>` (`:107`) — category →
  entries.
- `ManualEntry` (`:122-154`) — `{ name, category, IManualPage[] pages }` with getters
  and `setPages`.
- `ManualLink` (`:196-224`) — an `(entryName, pageIndex)` pointer.
  `changePage(GuiManual)` (`:217-223`) pushes the current entry onto the GUI's history
  stack, switches entry, sets the page, and re-inits the GUI. This is the navigation
  primitive used by in-text links and tooltip jumps.

### Adding entries

`addEntry(name, category, IManualPage... pages)` (`:109-112`) wraps the pages in a
`ManualEntry` and stores it under the category. `getEntry(name)` (`:114-120`) is a
case-insensitive linear scan.

### Recipe → page index

- `itemLinks : HashMap<Integer, ManualLink>` (`:156`).
- `indexRecipes()` (`:158-172`) — clears the map, walks every page of every entry,
  calls `page.recalculateCraftingRecipes()`, and for every recipe `ItemStack` a page
  reports via `getProvidedRecipes()` records `itemHash → ManualLink(entry, pageIndex)`.
  **Must be called once after all entries are registered** to enable item→manual jumps.
- `getManualLink(ItemStack)` (`:174-178`) / `getItemHash(ItemStack)` (`:180-194`) —
  hash combines the item registry name, metadata (if the item has subtypes), and NBT
  (if present).

### Abstract contract (`:32-62`)

Subclasses must provide: `getManualName`, `getSortedCategoryList`, the formatting hooks
`formatCategoryName` / `formatEntryName` / `formatEntrySubtext` / `formatLink` /
`formatText`, the visibility predicates `showCategoryInList` / `showEntryInList`, the
five colour getters (`getTitleColour`, `getSubTitleColour`, `getTextColour`,
`getHighlightColour`, `getPagenumberColour`), and the toggles `allowGuiRescale` /
`improveReadability`.

### Lifecycle hooks (overridable, default no-op)

`openManual` / `closeManual` / `openEntry` (`:64-74`) and the paired pre/post render
hooks `titleRenderPre/Post`, `entryRenderPre/Post`, `tooltipRenderPre/Post` (`:76-98`)
— IE uses these to toggle font tweaks for "bad eyesight" mode.

`getGui()` (`:100-105`) reuses `GuiManual.activeManual` if it belongs to this instance,
otherwise constructs a fresh `GuiManual(this, texture)`.

---

## IManualPage — the page contract

`IManualPage.java` (`:17-42`):

| Method | Purpose |
|---|---|
| `getManualHelper()` | back-reference to the owning `ManualInstance` |
| `initPage(gui, x, y, pageButtons)` | layout; register nav/link buttons |
| `renderPage(gui, x, y, mx, my)` | draw the page (mouse at mx,my) |
| `buttonPressed(gui, button)` | handle a button on this page |
| `mouseDragged(...)` | drag handling (used by the multiblock rotator) |
| `listForSearch(searchTag)` | does this page match a text search? |
| `recalculateCraftingRecipes()` | (re)resolve recipes for the index |
| `getProvidedRecipes()` (default `[]`) | recipe outputs this page documents |
| `getHighlightedStack()` (default EMPTY) | item currently hovered, for tooltip jumps |

The page content origin used throughout the GUI is `(guiLeft+32, guiTop+28)` with a
content width of ~120px.

---

## ManualPages — base class and page types

`ManualPages.java` is the abstract base implementing `IManualPage` (`:30-112`). Common
fields: `manual`, `text` (a translation key), `localizedText`, `providedItems`,
`highlighted`. The base `initPage` (`:45-59`) runs `manual.formatText(text)` then
`addLinks(...)` to convert `<link;...>` tags into clickable buttons; the base
`buttonPressed` (`:61-66`) routes a `GuiButtonManualLink` to `link.changePage`.

### Generic page types

| Type | Lines | Renders | Constructor |
|---|---|---|---|
| `Text` | `:114-135` | plain wrapped text | `(manual, text)` |
| `Image` | `:137-211` | stacked bordered textures + text | `(helper, text, String... images)` where each image = `"resource;u;v;w;h"` |
| `Table` | `:213-340` | text + column-aligned table with bars | `(manual, text, String[][] table, boolean horizontalBars)` |
| `ItemDisplay` | `:342-452` | auto-scaled grid of item icons w/ tooltips + text | `(manual, text, ItemStack... stacks)` or `(..., NonNullList<ItemStack>)` |
| `Crafting` | `:454-671` | crafting recipes for one or more targets, each with its own page arrows; recipes auto-discovered from `CraftingManager.REGISTRY` | `(manual, text, Object... stacks)` — stacks may be `ItemStack`, `ItemStack[]`, or ore-name `String` |
| `CraftingMulti` | `:673-886` | single shared recipe carousel; accepts prebuilt `PositionedItemStack[]` layouts or `ResourceLocation` recipe ids | `(manual, text, Object... stacks)` |

`Crafting.checkRecipe` (`:499-535`) and `CraftingMulti.handleRecipe` (`:731-767`) infer
grid dimensions for shaped/shapeless/ore recipes and build `PositionedItemStack[]`
layouts (ingredients + output positioned at `xBase + w*18 + 18`).

### Helpers inside ManualPages

- `addLinks(...)` (`:888-946`) — scans text for `<link;key;label;page>` tags, tokenizes
  the label per-word, wraps to width, and emits a `GuiButtonManualLink` at each rendered
  token's coordinates. Guarded to 50 iterations.
- `PositionedItemStack` (`:948-1017`) — an `Object stack` (ItemStack / Ingredient / List
  / ore string) at `(x,y)`. `getStack()` lazily expands wildcards/ingredients into a
  display list and **cycles through it once per second** via `System.nanoTime()` for the
  animated alternating-ingredient effect.

### IE-specific page types

- **`ManualPageMultiblock`** (`api/ManualPageMultiblock.java`, ctor `:61`) — renders a
  rotatable, animated 3D view of an `IMultiblock` structure, built layer-by-layer, with a
  required-materials tooltip and layer/play/pause/formed-view nav buttons. Inner
  `MultiblockBlockAccess` (`:317-415`) is an `IBlockAccess` over the structure;
  `MultiblockRenderInfo` (`:418-506`) advances the build animation on `tick%20==0`
  (`:173`). Constructor: `(manual, text, IMultiblock multiblock)`.
- **`ManualPageShader`** — see below.

---

## ManualUtils — rendering & search helpers

`ManualUtils.java`:

- `stackMatchesObject(stack, Object)` (`:29-44`) — match a stack against an ore name or
  another stack (wildcard/NBT aware); `compareToOreName` / `isExistingOreName`
  (`:46-60`).
- `drawTexturedRect(x, y, w, h, double... uv)` (`:62-72`).
- **Search spell-check:** `getPrimitiveSpellingCorrections(query, valid[], maxDistance)`
  (`:74-92`) and `getSpellingDistanceBetweenStrings` (`:94-127`) — a custom edit-distance
  with a transposed-letter special case (`:117-118`), powering the manual's "it looks
  like you meant…" suggestions.
- **`drawSplitString(fontRenderer, string, x, y, width, colour)`** (`:132-163`) — the
  key custom text renderer. Re-implements vanilla split-string drawing because vanilla
  doesn't carry text colour across wrapped lines; it re-injects the `§`colour code per
  line.
- `attemptStringTranslation(key, arg)` (`:165-172`) — format-and-translate, returning the
  raw arg if no translation exists.
- Render utilities with a cached `resourceMap` of `ResourceLocation`s (`:174-202`):
  `tes()`, `mc()`, `bindTexture(path)`, `getResource(path)`, `renderItem()`.

---

## GuiManual — the rendered screen

`lib/manual/gui/GuiManual.java` extends `GuiScreen`. Geometry `xSize=186, ySize=198`
(`:29-30`). Navigation state: `selectedCategory`, `selectedEntry`,
`Stack<String> previousSelectedEntry` (back history), `int page`, and the static
`activeManual` (`:36-40`). `doesGuiPauseGame()` returns false.

### initGui (`:89-164`)

Four-way branch:

- **Entry open:** calls `mPage.initPage(this, guiLeft+32, guiTop+28, pageButtons)` and
  adds the page's buttons (`:110-117`).
- **Otherwise (table of contents):** builds a `GuiClickableList` of either entries (when
  ≤1 category or inside a selected category) or the category list (`:118-147`), plus a
  back-arrow `GuiButtonManualNavigation` (id 1) and the search `GuiTextField` (id 99)
  (`:148-161`).

### drawScreen (`:166-255`)

Binds the book texture; draws the search box and any suggestion popup. When an entry is
open it draws the page-turn arrows, the bold title + subtext + page number (via
`drawCenteredStringScaled`, `:223-226`), then delegates to `mPage.renderPage(...)`
(`:230-232`). Wrapped in the `entryRenderPre/Post` hooks.

### Interaction

- **Page turning:** mouse wheel in `handleMouseInput` (`:366-384`) and the arrow
  hot-zones in `mouseClicked` (`:390-419`).
- **Item/link jumps:** `mouseClicked` (`:405-417`) reads `mPage.getHighlightedStack()`,
  looks up `getManualLink`, and calls `link.changePage`. `getItemToolTip` (`:340-355`)
  appends `manual.formatLink(link)` to a documented item's tooltip.
- **Back / exit:** right-click (button 1) clears search, pops history, or exits the
  category (`:420-430`).
- **`actionPerformed`** (`:267-312`): id 0 = table-of-contents selection; id 11 =
  spell-check suggestion; id 1 = back; otherwise forwarded to `mPage.buttonPressed`.
  Uses `buttonHeld` to debounce.
- **Search** (`keyTyped`, `:460-525`): filters entries by name-contains, falls back to
  `page.listForSearch`, and offers spelling suggestions.
- `mouseClickMove` (`:447-457`) forwards drags to the current page (multiblock rotation).

### Buttons & list

- `GuiButtonManual` — text button with normal/hover colour pairs; gradient rect + text.
- `GuiButtonManualLink` — invisible hot-zone carrying a `ManualLink`; on hover draws the
  highlighted label + a `formatLink` tooltip.
- `GuiButtonManualNavigation` — small textured arrow/icon button; `type` (0–6) selects
  the UV region (left/right arrows, layer up/down, play/pause/formed); hover shifts V.
- `GuiClickableList` — the scrollable table of contents; `perPage`/`maxOffset` paging,
  wheel scroll, per-row hover, and a `translationType` (-1 raw / 0 category / 1 entry)
  that chooses which formatter to apply.

---

## IE specialization

### IEManualInstance (`client/manual/IEManualInstance.java`)

IE's concrete `ManualInstance`. Constructor (`:42-54`) uses `IEItemFontRender`, the
texture `immersiveengineering:textures/gui/manual.png`, overrides colour codes 6/22 to
IE orange, and registers the font renderer as a resource-reload listener.

**`formatText` (`:56-198`)** is the heavy text parser. It:

- auto-translates a single-word key via `ie.manual.entry.%s` (`:59-66`),
- expands `<br>` → newline (`:69`),
- resolves `<config;type;key;…>` tags from live config values
  (`Config.manual_bool/int/intA/double/doubleA`, `:72-135`),
- resolves `<dim;id>` → dimension name (`:137-164`) and `<keybind;name>` → bound key
  (`:167-183`),
- and, in readability mode, re-bolds after every `RESET` (`:185-196`).

Other overrides: `addEntry` also records the category in an ordered `categorySet`
(`:274-280`); `getSortedCategoryList` returns it (`:282-286`); `formatLink` returns a
gold "→ name, page" (`:330-334`); colours are title/subtitle `0xf78034`, highlight
`0xd4804a`, page number `0x9c917c`; `allowGuiRescale` / `improveReadability` map to
`IEConfig.adjustManualScale` / `IEConfig.badEyesight`. `openEntry` (`:336-341`) sends a
shader-sync packet when the `"shaderList"` entry is opened.

### ManualPageShader (`client/manual/ManualPageShader.java`)

One page per shader registry entry. Constructor (`:51-55`) takes
`(ManualInstance, ShaderRegistry.ShaderRegistryEntry)`. `initPage` (`:57-120`) checks
whether the player has unlocked the shader (`ShaderRegistry.receivedShaders`), builds a
preview + example items with the shader capability applied, sets the text to
rarity/set/details info, and adds an **order** button (id 102, red if unaffordable) or,
in creative, an **unlock** button (id 103). `buttonPressed` (`:158-182`) sends
`MessageShaderManual` SPAWN/UNLOCK packets to the server. `listForSearch` returns false.

---

## Registration flow (where IE's content is defined)

1. **API facade** — `api/ManualHelper.java` holds the static `ieManualInstance` and the
   category constants `CAT_GENERAL/CONSTRUCTION/ENERGY/MACHINES/TOOLS/HEAVYMACHINES/
   UPDATE` (`:19-25`). `getManual()` and `addEntry(name, category, pages…)` delegate to
   the instance.
2. **Instantiation + content** — `ClientProxy.postInit()` sets
   `ManualHelper.ieManualInstance = new IEManualInstance()` (`:591`), then registers
   **all** entries through long sequences of
   `ManualHelper.addEntry("name", CAT_*, new ManualPages.Text(...),
   new ManualPages.Crafting(...), new ManualPageMultiblock(...), ...)` (e.g. `:612-659`
   and dozens more multiblock entries). This is the single authoritative place IE's
   manual content lives.
3. Each page is constructed with `ManualHelper.getManual()` as its instance; the `text`
   argument is a translation key resolved later by `IEManualInstance.formatText`.
4. `indexRecipes()` is run afterward to wire up item→page tooltip navigation.

### Adding a new manual entry

```java
ManualHelper.addEntry("myMachine", ManualHelper.CAT_MACHINES,
    new ManualPages.Text(ManualHelper.getManual(), "myMachine0"),     // text key
    new ManualPages.Crafting(ManualHelper.getManual(), "myMachine1",  // recipe page
        new ItemStack(IEContent.blockMetalDevice0, 1, META)),
    new ManualPageMultiblock(ManualHelper.getManual(), "myMachine2",  // 3D structure
        MultiblockMyMachine.instance));
```

Then add the matching `ie.manual.entry.myMachine*` / `ie.manual.category.*` lang keys.
