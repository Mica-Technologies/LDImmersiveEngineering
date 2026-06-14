# Utilities

Technical documentation for Immersive Engineering's shared utility code
(Forge 1.12.2), in `common/util/`, `common/util/commands/`,
`common/util/advancements/`, and `common/datafixers/`.

## Overview

| File | Role |
|---|---|
| `common/util/Utils.java` | large grab-bag of static helpers (stacks, blocks, inventories, computer serialization) |
| `common/util/ItemNBTHelper.java` | typed NBT accessors on `ItemStack` |
| `common/util/IELogger.java` | thin log4j wrapper |
| `common/util/IEPotions.java` | IE's custom potion effects |
| `common/util/commands/` | the `/ie` and `/cie` command trees |
| `common/util/advancements/` | the multiblock-formed advancement trigger |
| `common/datafixers/` | NBT data-fix walkers for save migration |

---

## ItemNBTHelper

`common/util/ItemNBTHelper.java` — typed accessors layered over an `ItemStack`'s NBT tag.

### Tag access

`getTag(stack)` (`:21-26`) returns the stack's tag compound, **creating and attaching a
new one if absent** — so any `getTag` call (and therefore any setter) on a tag-less stack
mutates the stack. Read paths guard with `hasTag` first (e.g. `getInt`, `:64-67`,
returns 0 without allocating). `remove` (`:38-46`) deletes a key and strips the whole tag
compound if it becomes empty.

### Typed accessors

Setters/getters for `int`, `string`, `long`, `intArray`, `float`, `boolean`,
`TagCompound`, `FluidStack`, and `ItemStack` (`:49-163`), plus `modifyInt` / `modifyFloat`
(read-add-write) and `setLore`.

### Energy item helpers

`insertFluxItem` / `extractFluxFromItem` / `getFluxStoredInItem` (`:175-202`) — store FE
in an item's `"energy"` NBT key.

### Bulk / structural helpers

- `stackWithData(stack, Object... data)` (`:204-234`) — varargs key/value writer that
  dispatches on the value's runtime type (Boolean/Integer/Float/Long/String/TagCompound/
  int[]/ItemStack/FluidStack).
- `combineTags(target, add, pattern, multiplyDecimals)` (`:236-304`) — deep-merges two
  NBT compounds element-by-element (numerics add or multiply, lists/arrays concatenate,
  compounds recurse). Used to combine tool/upgrade modifier tags.

---

## Utils

`common/util/Utils.java` — the central static helper class. Highlights:

- `copyStackWithAmount(stack, amount)` (`:157-164`) — `stack.copy()` resized.
- `copyFluidStackWithAmount(stack, amount, stripPressure)` (`:192-204`).
- `getDye` / `isDye` (`:168-190`) — ore-dictionary dye detection.
- `generateNewUUID()` (`:209-214`) — returns a sequential UUID from a static counter
  (`UUIDBase` + incrementing `UUIDAdd`). **Deterministic, not random**; used for stable
  potion-modifier UUIDs (see IEPotions).
- `isBlockAt` / `blockstateMatches` (`:230-240`), `isOreBlockAt(world, pos, oreName)`
  (`:242-247`) — block/ore matching; `isOreBlockAt` allocates an `ItemStack` per call.
- `canFenceConnectTo` (`:249-259`) — fence/post connection logic.
- `insertStackIntoInventory(...)` (`:863-...`) — capability-based inventory insertion with
  simulate variant.
- `saveStack(ItemStack)` (`:1683-1698`), `saveFluidTank` (`:1700-1711`),
  `saveFluidStack` (`:1713-...`) — serialize game objects into `Map<String, Object>` for
  OpenComputers/ComputerCraft Lua callbacks. Each call allocates a fresh `HashMap` and
  resolves display names.
- `RAND` — a shared `Random` used across IE (e.g. by the Excavator).

---

## IELogger

`common/util/IELogger.java` — static wrapper over a log4j `Logger`. `info` / `warn` /
`error` with both `Object` and `(String, Object...)` overloads. Note: `debug(Object)`
(`:54-58`) is **a no-op** — its body is commented out, so debug logging is effectively
disabled regardless of the `debug` flag.

---

## IEPotions

`common/util/IEPotions.java` — IE's seven custom potion effects: `flammable`, `slippery`,
`conductive`, `sticky`, `stunned`, `concreteFeet`, `flashed`. `init()` (`:36-47`)
constructs each `IEPotion`, registers them with `ForgeRegistries.POTIONS`, and stores the
set in `IEApi.potions`. Movement-slowing potions attach a `MOVEMENT_SPEED` attribute
modifier with a deterministic UUID from `Utils.generateNewUUID()`.

`IEPotion` (`:49-130`) extends vanilla `Potion`:

- Configurable HUD/inventory visibility and a custom `tickrate`. `isReady` (`:96-102`)
  controls per-tick firing; `tickrate < 0` means the effect never fires `performEffect`.
- `performEffect` (`:104-129`) implements the active behaviors:
  **slippery** nudges the entity forward and can knock a held item out of hand (server
  side, ~1/300 per applicable tick); **concreteFeet** ends itself unless the entity is
  standing on IE stone decoration. Other effects are handled elsewhere (event hooks /
  damage sources).

---

## Commands

`common/util/commands/`. IE exposes a single root command per side, using Forge's
`CommandTreeBase` for subcommands.

### CommandHandler (`CommandHandler.java`)

The root. Two instances are created (`:27-41`):

- **`/ie`** (server, permission level 4, `:53`) — subcommands `mineral`, `shaders`, plus
  the auto help tree.
- **`/cie`** (client, permission level 0) — subcommand `resetRenders`.

`CommandHandler` adds quoting support: tab completions wrap multi-word results in
`<…>` (`:66-80`), and `execute` (`:82-111`) re-parses `<…>`-delimited arguments back into
single tokens so names containing spaces (like mineral mix names) work.

### CommandMineral (`CommandMineral.java`)

`/ie mineral`, permission level 4 (`:82-85`). Operates on the excavator mineral cache
(see WORLD_GEN.md). Subcommands:

| Subcommand | Effect |
|---|---|
| `list` (`:87-112`) | print all registered mineral mix names |
| `get` (`:114-141`) | print the deposit, override, and depletion for the sender's chunk |
| `set <name>` (`:143-187`) | force `mineralOverride` for the sender's chunk; marks save dirty |
| `setDepletion <n>` (`:189-218`) | set the depletion counter for the sender's chunk; marks save dirty |

`get`/`set`/`setDepletion` all call `ExcavatorHandler.getMineralWorldInfo` for the
sender's chunk, which can trigger first-time deposit generation for that chunk.
Tab-completion for `set` enumerates `mineralList` (`:71-74`).

### Other commands

- `CommandShaders.java` — grant/manage shader unlocks (server).
- `CommandResetRenders.java` — client render reset.
- `CommandHandler.java` also references `CommandShaders` for the server tree.

---

## Advancements

`common/util/advancements/`.

- `IEAdvancements.java` — holds the static `TRIGGER_MULTIBLOCK` and registers it with
  `CriteriaTriggers` in `preInit` (`:20-23`).
- `MultiblockTrigger.java` — a custom `ICriterionTrigger` fired when a player completes a
  multiblock structure. `trigger(player, multiblock, hammer)` (`:82-87`) finds the
  player's listeners and grants any whose `Instance` predicate matches. `Instance`
  (`:89-105`) tests the multiblock's `uniqueName` and an `ItemPredicate` on the hammer
  used. The per-player `Listeners` set (`:107-147`) is standard vanilla trigger
  plumbing. This fires only on the (rare) multiblock-formed event, not per tick.

---

## Data Fixers

`common/datafixers/` — NBT migration so older saves load correctly after item/TE format
changes.

`IEDataFixers.register()` (`IEDataFixers.java:32-74`):

- Initializes a `ModFixs` under the IE modid at `DATA_FIXER_VERSION` (`:34-35`).
- Registers an `ITEM_INSTANCE` fix `DataFixerHammerCutterDamage` and an item walker
  `IEItemFixWalker` (`:37-38`).
- Registers `BLOCK_ENTITY` `ItemStackData` / `ItemStackDataLists` walkers for specific
  tiles (metal press mold, charging station, crusher inputs) plus the custom
  `AssemblerPatternWalker` and `BottlingQueueWalker` (`:40-47`).
- Then **reflectively scans every registered IE tile** (`IEContent.registeredIETiles`,
  `:58-69`): for each `TileEntityMultiblockMetal` that is an in-world processing machine
  it registers a `MultiblockProcessWalker`; for each `IIEInventory` tile not in the
  hand-curated `specialCases` set it registers an `inventory` list walker.

Walker classes:

| Walker | Fixes |
|---|---|
| `DataFixerHammerCutterDamage` | legacy hammer/cutter damage values |
| `IEItemFixWalker` | item-embedded sub-item references |
| `AssemblerPatternWalker` | assembler crafting patterns |
| `BottlingQueueWalker` | bottling machine process queue |
| `MultiblockProcessWalker` | in-world multiblock process item references |

All data-fixer work runs at load time only.
