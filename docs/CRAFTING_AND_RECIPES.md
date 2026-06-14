# Crafting & Recipe System

Technical documentation for Immersive Engineering's custom recipe types, ingredient
matching, and recipe registration. All citations are `path:line` against real source.

## Overview

IE defines a family of machine recipe types (Crusher, Arc Furnace, Alloy Smelter, Metal
Press, Mixer, Refinery, Squeezer, Fermenter, Bottling Machine, Blast Furnace, Coke Oven,
Blueprint crafting) plus vanilla-crafting extensions (`RecipeShapedIngredient`,
`RecipeShapelessIngredient` and a host of special-case recipes).

Two concepts underpin everything:

- **`IngredientStack`** — a flexible recipe input that can be a concrete ItemStack, an
  ore-dictionary name, a list of stacks, or a fluid. It carries an `inputSize`.
  (`api/crafting/IngredientStack.java`)
- **`ComparableItemStack`** — a hashable/equatable wrapper used as a `HashMap` key so a
  recipe set can be indexed instead of linearly scanned.
  (`api/ComparableItemStack.java`)

Almost every machine recipe class follows the same shape:

```
public static ArrayList<XRecipe> recipeList = new ArrayList<>();   // global registry
public static XRecipe  addRecipe(...)   // construct + add to recipeList
public static XRecipe  findRecipe(input)// LINEAR scan of recipeList, returns first match
public static List<XRecipe> removeRecipes(stack) // for CraftTweaker/IMC removal
public static XRecipe  loadFromNBT(nbt) // rehydrate a running process after world load
```

The recipe objects are **immutable singletons** held in static lists; a running machine
stores only enough NBT (`writeToNBT`) to re-find its recipe via `loadFromNBT` on reload.

---

## IngredientStack — the universal input

`api/crafting/IngredientStack.java:29` holds four mutually-exclusive payloads plus a size:

| Field | Meaning |
|---|---|
| `stack` (`ItemStack`) | concrete item input (default payload) |
| `stackList` (`List<ItemStack>`) | "any of these" item input |
| `oreName` (`String`) | ore-dictionary entry, resolved lazily |
| `fluid` (`FluidStack`) | fluid input (matched against fluid-containing items) |
| `inputSize` (`int`) | required count; defaults to `stack.getCount()` |
| `useNBT` (`boolean`) | when set, NBT must match too |

### Matching

`matchesItemStack(ItemStack)` (`IngredientStack.java:204`) is the hot method. Its logic:

1. If `fluid!=null`, compare via `FluidUtil.getFluidContained` + `containsFluid`.
2. If `oreName!=null`, call `ApiUtils.compareToOreName(input, oreName)` **and** check
   `inputSize <= input.getCount()`.
3. If `stackList!=null`, loop the list calling `OreDictionary.itemMatches`.
4. Otherwise compare `stack` via `OreDictionary.itemMatches(stack, input, false)`, then a
   size check, then (if `useNBT`) a tag-compound equality check.

`matchesItemStackIgnoringSize` (`:235`) is the same without the count requirement — used
where the machine pulls partial stacks (Blueprint workbench, mixer, arc additives).

`ApiUtils.compareToOreName` (`ApiUtils.java:70`) is itself a loop over
`OreDictionary.getOres(oreName)` calling `OreDictionary.itemMatches` for each — so an
ore-keyed `IngredientStack` match is O(number of ores registered to that name).

### Equality and NBT round-tripping

`equals` (`:266`) compares by payload type (fluid vs ore vs stackList vs stack). For
stackList-vs-stackList it does an **O(n·m) cross-product** of `OreDictionary.itemMatches`.
`writeToNBT`/`readFromNBT` (`:302`/`:334`) serialize the payload with an `nbtType` tag
(0 stack, 1 stackList, 2 oreName, 3 fluid), which is how a running machine's recipe is
re-located after a world reload.

### Construction

Inputs reach an `IngredientStack` through `ApiUtils.createIngredientStack(Object)`
(`ApiUtils.java:792`), which accepts `ItemStack`, `Item`, `Block`, `Ingredient`,
`List<ItemStack>`, `List<String>`, `ItemStack[]`, `String[]`, `String` (ore name), or
`FluidStack`. A `preferWildcard` overload turns bare `Item`/`Block` inputs into
wildcard-damage stacks.

---

## ComparableItemStack — hash key for indexed lookup

`api/ComparableItemStack.java:15` wraps an `ItemStack` so it can be a map key.

- `hashCode()` (`:76`) returns the cached `oreID` if known (set in the constructor from
  `OreDictionary.getOreIDs(stack)[0]`), else a hash of damage + item + (optionally) tag.
  **Items sharing the same first ore-ID hash identically** — that is intentional, so an
  ore-equivalent mold maps to the same bucket.
- `equals()` (`:87`) short-circuits on matching `oreID`, otherwise falls back to
  `OreDictionary.itemMatches` + optional `ItemStack.areItemStackTagsEqual`.

This is the only recipe type-key in the API and is used by the **Metal Press** to index
recipes by mold (see below).

---

## Machine recipe types

### CrusherRecipe (`api/crafting/CrusherRecipe.java`)

- Inputs: single `IngredientStack input` (`:37`). Output: one primary `ItemStack` plus an
  optional array of secondary outputs with per-entry chances (`secondaryOutput`/
  `secondaryChance`, `:39`).
- `getActualItemOutputs` (`:55`) rolls each secondary against `Utils.RAND`.
- Storage: `ArrayList<CrusherRecipe> recipeList` (`:108`). Lookup: `findRecipe` (`:118`)
  is a **linear scan** calling `input.matchesItemStack`.
- Process timing: `totalProcessTime = 50 * timeModifier`; energy scaled by
  `energyModifier`.

### ArcFurnaceRecipe (`api/crafting/ArcFurnaceRecipe.java`)

- Inputs: a primary `IngredientStack input` plus a `IngredientStack[] additives` (`:35`,
  `:37`). Outputs: `output` + a non-null `slag` stack.
- `matches(input, additives)` (`:139`) first checks the primary input, then
  `getConsumedAdditives` (`:150`) which greedily walks the additive list against the
  supplied additive inventory, mutating counts and rolling them back if a requirement
  can't be met.
- Storage: `ArrayList recipeList` (`:44`); `findRecipe` (`:228`) linear-scans calling
  `matches`. Helper predicates `isValidRecipeInput`/`isValidRecipeAdditive` (`:252`/`:260`)
  also linear-scan the whole list.
- **Recycling:** arc-furnace recipes can be auto-generated from the vanilla recipe graph.
  `recyclingAllowed`/`invalidRecyclingOutput` (`:268`/`:278`) gate which items participate.
  Generation runs off-thread (see *Arc recycling* below) and appends synthetic
  `ArcRecyclingRecipe` entries tagged `specialRecipeType == "Recycling"`.

### AlloyRecipe (`api/crafting/AlloyRecipe.java`)

- Two unordered inputs `input0`/`input1` (`:26`). `findRecipe(a, b)` (`:48`) tries both
  orderings against each recipe — linear scan, no index.

### MetalPressRecipe (`api/crafting/MetalPressRecipe.java`)

- The one type that is **indexed**. Inputs: an `IngredientStack input` and a
  `ComparableItemStack mold` (`:33`/`:34`).
- Storage: `ArrayListMultimap<ComparableItemStack, MetalPressRecipe> recipeList` (`:75`)
  keyed by mold.
- `findRecipe(mold, input)` (`:89`) builds a `ComparableItemStack` from the mold, does an
  **O(1) `recipeList.get(comp)`** to get the candidate list for that mold, then linearly
  scans only those candidates for an input match. `isValidMold` (`:121`) is a pure
  `containsKey`.
- Packing/unpacking variants live in `common/crafting/MetalPressPackingRecipe.java` and
  `MetalPressUnpackingRecipe.java`, registered through the `deserializers` map (`:134`).

### MixerRecipe (`api/crafting/MixerRecipe.java`)

- Inputs: a `FluidStack fluidInput` + `IngredientStack[] itemInputs` (`:34`/`:35`).
  Output: `FluidStack fluidOutput`.
- `matches`/`compareToInputs` (`:80`/`:85`) copy the component inventory into a working
  list and greedily consume against each item input.
- `findRecipe(fluid, components)` (`:65`) linear-scans `recipeList`.
- Potion brewing is layered on top via `common/crafting/MixerPotionHelper.java` (see
  FLUIDS.md).

### RefineryRecipe (`api/crafting/RefineryRecipe.java`)

- Two fluid inputs `input0`/`input1` and a fluid `output` (`:28`-`:30`).
- `findRecipe(a, b)` (`:53`) linear-scans, trying both input orderings via
  `FluidStack.containsFluid`. `findIncompleteRefineryRecipe` (`:82`) supports the JEI/GUI
  hint when only one input is present.

### SqueezerRecipe / FermenterRecipe

Structurally identical (`api/crafting/SqueezerRecipe.java`,
`api/crafting/FermenterRecipe.java`): one `IngredientStack input`, a `FluidStack
fluidOutput`, and an `ItemStack itemOutput`. Both expose `findRecipe(input)` as a linear
scan (`SqueezerRecipe.java:67`, `FermenterRecipe.java:67`) and a
`getFluidValuesSorted(Fluid, boolean)` helper for the manual.

### BottlingMachineRecipe (`api/crafting/BottlingMachineRecipe.java`)

- An item `input` + a `FluidStack fluidInput` → an item `output`.
- `findRecipe(input, fluid)` (`:54`) linear-scans, matching the item via
  `ApiUtils.stackMatchesObject` and the fluid via `containsFluid`.

### BlastFurnaceRecipe (`api/crafting/BlastFurnaceRecipe.java`)

- Note this class does **not** extend `MultiblockRecipe` (the IE Blast Furnace is a simple
  burn-time machine, not a powered multiblock).
- `input` is stored as a raw `Object` (ItemStack / ore list) and matched with
  `ApiUtils.stackMatchesObject` in `findRecipe` (`:50`).
- A separate `blastFuels` list + `BlastFurnaceFuel` inner class (`:76`-`:88`) defines
  fuels; `getBlastFuelTime` (`:97`) linear-scans them.

### CokeOvenRecipe (`api/crafting/CokeOvenRecipe.java`)

- Also standalone (not a `MultiblockRecipe`). `input` (raw Object), `output`, `time`, and
  `creosoteOutput`. `findRecipe` (`:48`) linear-scans via `ApiUtils.stackMatchesObject`.

### BlueprintCraftingRecipe (`api/crafting/BlueprintCraftingRecipe.java`)

The Engineer's Workbench recipes — the most complex matcher.

- Inputs: `IngredientStack[] inputs` (`:47`), an output, and a `blueprintCategory` string.
- Storage is **indexed by category**: `ArrayListMultimap<String,
  BlueprintCraftingRecipe> recipeList` (`:42`). `findRecipes(category)` (`:269`) returns
  the candidate array in O(1).
- `getMaxCrafted(query)` (`:134`) is the real matcher: it buckets the query inventory into
  an amount map (collapsing ore-equivalent stacks), formats the recipe inputs via
  `getFormattedInputs` (`:223`, which merges duplicate ore/stack inputs and sums their
  sizes), then computes how many full craft-cycles the inventory supports.
- `consumeInputs(query, crafted)` (`:184`) actually withdraws items, honoring container
  items (e.g. buckets).

---

## MultiblockRecipe base & the IMultiblockRecipe contract

`api/crafting/MultiblockRecipe.java:22` is the abstract base for every powered-multiblock
recipe (Crusher, Arc Furnace, Metal Press, Mixer, Refinery, Squeezer, Fermenter,
Bottling). It implements `IMultiblockRecipe` (`api/crafting/IMultiblockRecipe.java:26`)
and `IJEIRecipe`, and holds the normalized input/output views the machine logic and JEI
consume:

- `inputList` (`List<IngredientStack>`), `outputList` (`NonNullList<ItemStack>`),
  `fluidInputList`, `fluidOutputList` — populated by each subclass constructor.
- `totalProcessTime` / `totalProcessEnergy` — the machine reads these to drive its process
  queue.
- `setupJEI()` (`:83`) pre-expands ore inputs into concrete stack lists once at recipe
  registration, caching `jeiItemInputList` etc. so the JEI category doesn't re-resolve ore
  dictionaries every frame.

`IMultiblockRecipe` default methods worth noting:

- `shouldCheckItemAvailability()` (`IMultiblockRecipe.java:30`) — Mixer overrides this to
  `false` (`MixerRecipe.java:202`) so its fluid-driven process isn't gated on item slots.
- `getDisplayStack(input)` (`:46`) — finds which ingredient an inserted stack satisfies and
  returns a sized copy, used by in-world processing machines.

---

## Recipe registration (common layer)

### Machine recipes

All machine recipes are registered imperatively in
`common/IERecipes.java` during mod init by calling the static `addRecipe` methods, e.g.:

```
MetalPressRecipe.addRecipe(new ItemStack(itemBullet,2,0), "ingotCopper",
                           new ItemStack(itemMold,1,3), 2400);   // IERecipes.java:207
BlastFurnaceRecipe.addRecipe(new ItemStack(itemMetal,1,8), "ingotIron", 1200, slag); // :195
BlueprintCraftingRecipe.addRecipe("components", output, "plateIron", "plateIron",
                                  "ingotCopper");                // :95
```

`IERecipes` also generates large recipe families programmatically (every metal's plate/gear
metal-press recipe, every wool→string crusher recipe, etc.).

### Vanilla-grid recipes (RecipeShapedIE / RecipeShapelessIE)

IE ships JSON-driven crafting recipes plus custom `Ingredient`/`IRecipe` factories so that
ore-dictionary and IE-specific ingredients work in the vanilla grid:

- **`RecipeShapedIngredient`** (`common/crafting/RecipeShapedIngredient.java:29`) extends
  Forge's `ShapedOreRecipe`. It wraps any `IngredientStack` arguments into
  `IngredientIngrStack` (`:50`), and adds IE-only features:
  - `allowQuarterTurn()` / `allowEighthTurn()` (`:61`/`:73`) — recipe matches under
    rotation by pre-building rotated ingredient layouts; `checkMatch` (`:154`) tries the
    base layout then the rotated variants and records which matched (`lastMatch`).
  - `setNBTCopyTargetRecipe`/`setNBTCopyPredicate` (`:88`/`:94`) — copy NBT from input
    slots onto the result (used for tools/upgrades), applied in `getCraftingResult` (`:106`).
  - `getRemainingItems` (`:127`) drains fluid-container ingredients (`IngredientFluidStack`)
    instead of consuming them.
- **`RecipeShapelessIngredient`** (`common/crafting/RecipeShapelessIngredient.java`) — the
  shapeless analogue.
- These are wired through the JSON recipe factories
  `RecipeFactoryShapedIngredient` / `RecipeFactoryShapelessIngredient`, and the ingredient
  factories `IngredientFactoryFluidStack` / `IngredientFactoryStackableNBT`.

### Custom Ingredient adapters

- **`IngredientIngrStack`** (`common/crafting/IngredientIngrStack.java:32`) — adapts an IE
  `IngredientStack` into a Forge `Ingredient`. `apply` (`:103`) delegates to
  `ingredientStack.matchesItemStack`. `getMatchingStacks` (`:46`) and the client packed-id
  list (`:75`) are **lazily built and cached**, expanding wildcard-damage stacks into
  sub-items; the cache is keyed on list size and invalidated via `invalidate()` (`:111`).
- **`IngredientFluidStack`** (`common/crafting/IngredientFluidStack.java:22`) — matches any
  item containing a given fluid; `getMatchingStacks` caches a filled bucket (`:42`).
- **`IngredientMultiOre`** — matches across several ore names.

### Special recipes

`common/crafting/` holds many `IRecipe` implementations for things that need code logic:
`RecipeRevolver`/`RecipeRevolverAssembly`, `RecipePotionBullets`/`RecipeFlareBullets`/
`RecipePotionBullets`, `RecipeShaderBags`, `RecipeRGBColouration`, `RecipeBannerAdvanced`,
`RecipeJerrycan`, `RecipePowerpack`, `RecipeEarmuffs`, `RecipeSpeeloader`,
`RecipeIEItemRepair`. Conditions (`ConditionFactoryIEConfig`,
`ConditionFactoryOreExists`) gate JSON recipes on config flags / ore presence.

### Manual page hookup

The Engineer's Manual reads from the same recipe lists. The IE Manual library generates
pages for machine recipes by iterating the relevant `recipeList`, using
`IngredientStack.getRandomizedExampleStack`/`getExampleStack`
(`IngredientStack.java:160`/`:176`) to display ore inputs as rotating example items.

---

## Arc-furnace recycling generation

`common/crafting/ArcRecyclingThreadHandler.java:28` builds recycling recipes by analyzing
the **entire vanilla+modded recipe registry** (`ForgeRegistries.RECIPES`) off the main
thread:

- It snapshots the recipe list (`:33`) and splits it across
  `availableProcessors()-1` worker threads (`:53`-`:62`).
- Each worker (`RegistryIterationThread.run`, `:146`) reduces every craftable item to its
  constituent ingots via `ApiUtils.breakStackIntoPreciseIngots`, producing
  `RecyclingCalculation` records.
- A fixed-point pass (`:90`-`:108`) resolves recipes whose subcomponents are themselves
  recyclable (e.g. an item made of a part made of ingots), bounded by `invalidCount*10`
  iterations.
- `finishUp()` (`:113`) appends the results as `ArcRecyclingRecipe`s tagged
  `"Recycling"`; on re-run, old recycling recipes are first stripped (`:38`-`:49`).

This is a startup/reload cost, not a per-tick cost, and is deliberately threaded to keep it
off the world tick.

---

## How a running machine re-finds its recipe

When a multiblock with an active process is saved and reloaded, it doesn't store the whole
recipe — it stores the minimal NBT from `writeToNBT` and calls the matching
`loadFromNBT` on load (e.g. `CrusherRecipe.loadFromNBT`,
`CrusherRecipe.java:171`). These methods read the serialized `IngredientStack` and **scan
`recipeList` for a recipe whose stored input `.equals()` it**, returning the canonical
singleton. If the recipe no longer exists (mod/recipe removed) the process is dropped.

---

## Performance characteristics (summary)

- **Indexed lookups (O(1)+small scan):** Metal Press (by `ComparableItemStack` mold),
  Blueprint crafting (by category string).
- **Linear scans (O(recipeList)):** Crusher, Arc Furnace, Alloy, Mixer, Refinery,
  Squeezer, Fermenter, Bottling, Blast Furnace, Coke Oven. Each comparison may itself
  resolve ore dictionaries.
- These `findRecipe` calls are reused by every machine's tick/insertion logic, so their cost
  is amplified by machine count. See the multiblock tile-entity logic in
  `common/blocks/metal/` for call sites (`TileEntityArcFurnace.java:140`,
  `TileEntitySqueezer.java:128`, `TileEntityMultiblockMetal.java:896`, etc.).
