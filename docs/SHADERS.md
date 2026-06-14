# Shader System

Technical documentation for Immersive Engineering's cosmetic shader system — the registry,
the per-item-type "shader cases", how shaders are stored on items, and how they are applied
at render time. Citations are `path:line` against real source.

## Overview

A *shader* is a cosmetic skin that can be applied to a supported item or entity (revolver,
chemical thrower, drill, railgun, shield, minecart, balloon, banner, and the generic
shader item itself). Each shader is registered once into a global `ShaderRegistry` and
produces a `ShaderCase` per supported item type. A `ShaderCase` is just an ordered list of
**render layers** (texture + ARGB colour), plus per-pass visibility rules.

Shaders are obtained from loot crates and from "shader grab bags", with a weighted-rarity
distribution that prioritizes shaders the player has not yet received.

Key files (`api/shader/`):

| File | Role |
|---|---|
| `ShaderRegistry.java` | global registry, weighted random selection, per-type registration |
| `ShaderCase.java` | abstract case = ordered `ShaderLayer[]` + visibility hooks |
| `ShaderCaseItem.java`, `ShaderCaseRevolver.java`, … | one concrete case per item type |
| `CapabilityShader.java` | the capability that stores a shader on an item/entity |
| `IShaderItem.java` | implemented by items that *accept* a shader |
| `IShaderEffectFunction.java` | optional gameplay/visual effect callback |

---

## ShaderRegistry

`api/shader/ShaderRegistry.java:31`. All state is static.

### Core data structures

- `shaderRegistry` — `LinkedHashMap<String, ShaderRegistryEntry>` (`:36`). Name → entry.
  Insertion-ordered so weighted iteration is deterministic.
- `rarityWeightMap` — `HashMap<EnumRarity, Integer>` (`:46`), seeded in a static block
  (`:48`): COMMON 9, UNCOMMON 7, RARE 5, EPIC 3, Masterwork 1. Higher weight = more common.
- `chestLootShaders` — `ArrayList<String>` (`:41`). For crate loot, each shader name is
  added *weight* times so a uniform random pick is implicitly weighted.
- `totalWeight` — `HashMap<EnumRarity, Integer>` (`:70`). Pre-summed weight of all shaders
  "of that rarity or rarer", computed once (see *weighted selection*).
- `playerTotalWeight` — `HashMap<String, HashMap<EnumRarity, Integer>>` (`:74`).
  Per-player weight totals that discount already-received shaders.
- `receivedShaders` — `ArrayListMultimap<String, String>` (`:66`). Player → shader names
  already granted (saved with world data).
- `defaultLayerBounds` — `HashMap<ResourceLocation, double[]>` (`:82`). Default UV bounds
  per overlay texture, so layers needn't redeclare them.
- `defaultReplicationCost` — `IngredientStack` (`:78`), scaled by rarity per entry.

### ShaderRegistryEntry

Each registered shader is one `ShaderRegistryEntry` (inner class, near the bottom of the
file) holding:

- `name`
- `cases` — `HashMap<String, ShaderCase>` keyed by shader-type string (e.g.
  `"immersiveengineering:revolver"`). `getCase(type)` is an O(1) map get.
- `rarity` and a derived `weight` (= `rarityWeightMap.get(rarity)`).
- `isCrateLoot` / `isBagLoot` / `isInLowerBags` flags.
- `replicationCost` (`IngredientStack`) — what the Shader Bag/replication costs.
- `effectFunction` (`IShaderEffectFunction`) — defaults to a no-op.

### Registration

`registerShader(name, overlayType, rarity, colours…, loot, bags)` (`:91`) is the
front-door. It registers **one `ShaderCase` per supported item type** by calling the
per-type helpers in sequence (`:93`-`:101`):

```
registerShader_Item        → ShaderCaseItem
registerShader_Revolver    → ShaderCaseRevolver
registerShader_Chemthrower → ShaderCaseChemthrower
registerShader_Drill       → ShaderCaseDrill
registerShader_Railgun     → ShaderCaseRailgun
registerShader_Shield      → ShaderCaseShield
registerShader_Minecart    → ShaderCaseMinecart
registerShader_Balloon     → ShaderCaseBalloon
registerShader_Banner      → ShaderCaseBanner
```

Each helper builds an `ArrayList<ShaderLayer>` from the supplied colours and texture
locations, e.g. `registerShader_Item` (`:127`) builds three layers
(`shader_0`/`shader_1`/`shader_2`) and calls `registerShaderCase`.

`registerShaderCase(name, case, rarity)` (`:107`) either creates a new
`ShaderRegistryEntry` or, if the name already exists, adds the case to the existing entry
(`addCase`). This is how all the per-type cases collapse under a single shader name.

Third-party item types can register their own case for every shader by adding an
`IShaderRegistryMethod` to `shaderRegistrationMethods`; it is invoked inside
`registerShader` (`:102`).

### Lookup

`getShader(name, shaderType)` (`:84`): O(1) — `containsKey` then `entry.getCase(type)`,
both `HashMap` operations. There is **no linear scan on the shader hot path**.

`getStoredShaderAndCase(ItemStack)` resolves the shader name from the item's capability and
returns the `(stack, entry, case)` triple; this is what the renderer and effect dispatch
call.

### Weighted random selection

The grab-bag roll is the one O(n) operation, and it only runs when a bag is opened.

- `compileWeight()` runs once after registration (called at post-init). It clears and
  rebuilds `totalWeight` and `chestLootShaders`, and sorts `sortedRarityMap` by weight.
  Cost is O(#shaders × #rarities) with #rarities = 5 — a one-time startup cost.
- `recalculatePlayerTotalWeight(player)` rebuilds that player's `playerTotalWeight` map,
  giving already-received shaders weight 1 instead of their full weight. It runs when a
  player *receives* a shader, not per render or per tick.
- `getRandomShader(player, rand, minRarity, addToReceived)` picks a random integer in
  `[0, total)` for the requested rarity, then walks `shaderRegistry.values()` subtracting
  each eligible entry's (possibly discounted) weight until the counter goes non-positive,
  returning that entry's name. Worst case O(#shaders). If `addToReceived`, the name is
  recorded and the player's weights are recalculated.

Because `totalWeight` is precomputed and never invalidated at runtime, adding shaders after
post-init requires re-running `compileWeight()`.

---

## ShaderCase and ShaderLayer

`api/shader/ShaderCase.java:23` is abstract.

- `layers` — `ShaderLayer[]` (`:28`), the ordered render passes.
- Abstract hooks each concrete case implements:
  - `getShaderType()` — the type string used as the registry-entry case key.
  - `getLayerInsertionIndex()` — where extra/dynamic layers slot in.
  - `renderModelPartForPass(shader, item, modelPart, pass)` — whether a given model group
    is drawn on a given pass (lets a case paint only the grip on one pass, only the blade
    on another, etc.).
- Per-pass accessors `getReplacementSprite(...)` and `getARGBColourModifier(...)`
  (`:86`-`:97`) simply return `getLayers()[pass]`'s texture / colour. These are O(1).

### ShaderLayer

Inner class (`ShaderCase.java:110`): an immutable `texture` (`ResourceLocation`), a packed
ARGB `colour`, and optional `textureBounds` / `cutoutBounds` (UV sub-rects, defaulted from
`defaultLayerBounds`). A `DynamicShaderLayer` subclass (`:203`) is flagged dynamic
(`isDynamicLayer()`), excluded from static batching, and can hook GL state via
`modifyRender(pre, partialTick)`.

### Concrete cases

Each supported item type has a case with its own layer count and visibility logic. Example:
`ShaderCaseRevolver` (`api/shader/ShaderCaseRevolver.java`) — its
`renderModelPartForPass` only draws the grip parts on the grip pass and the bayonet/blade
parts on the blade pass, so the revolver's grip, body and blade get independently coloured.
`ShaderCaseItem` (`api/shader/ShaderCaseItem.java`) is the simplest — three flat layers
used for the shader item's own inventory icon. The remaining cases
(`ShaderCaseChemthrower`, `ShaderCaseDrill`, `ShaderCaseRailgun`, `ShaderCaseShield`,
`ShaderCaseMinecart`, `ShaderCaseBalloon`, `ShaderCaseBanner`) follow the same pattern.

---

## Storing a shader on an item — CapabilityShader

`api/shader/CapabilityShader.java:29`. A shader is attached to an item or entity through a
Forge capability rather than a fixed field, so any shaderable thing exposes the same API.

- `ShaderWrapper` (abstract, `:34`) — the stored value. Holds the shader item and the
  `shaderType` string this wrapper is for.
- `ShaderWrapper_Item` (`:59`) — backs the wrapper with the host item's own NBT (key
  `"IE:Shader"`), so the shader travels with the stack.
- `ShaderWrapper_Direct` (`:95`) — holds a shader `ItemStack` directly; used for entities
  (minecarts) that aren't themselves item stacks.
- `IStorage` (`:155`) serializes/deserializes the wrapper to/from NBT.

`IShaderItem` (`api/shader/IShaderItem.java`) is implemented by every item that *can wear*
a shader (revolver, drill, etc.). Its `getShaderCase(shader, item, shaderType)` reads the
shader name from the shader stack's NBT and resolves it through
`ShaderRegistry.getShader(name, shaderType)` — i.e. the item never stores the case, only
the name, and looks the case up on demand.

The standalone shader item (`ItemShader`) implements `IShaderItem` and registers shaders
via `ShaderRegistry.registerShader`. The shader's identity lives in its NBT as a string
name.

---

## Applying a shader at render time

The smart OBJ model resolves the stored shader+case once per item render via
`getStoredShaderAndCase`, then bakes each model group through the case's layers: for each
pass it checks `renderModelPartForPass`, fetches the layer's sprite and ARGB modifier, and
emits coloured quads. All of these are O(1) array/map accesses — there are **no registry
scans and no ore-dictionary work on the render path**. Layer count per case is small
(typically 3–6), so per-item render cost is bounded and constant.

`DynamicShaderLayer`s are pulled out of the static bake and rendered separately so they can
animate (their `modifyRender` runs each frame).

---

## Shader effects

`IShaderEffectFunction` (`api/shader/IShaderEffectFunction.java`) is a functional interface:

```java
void execute(World world, ItemStack shader, ItemStack item, String shaderType,
             Vec3d pos, Vec3d direction, float scale);
```

Most shaders use the default no-op. A handful (e.g. the special "Ikelos" shader) provide a
real function that spawns particles. The effect is dispatched from gameplay events (for
example when a shadered minecart moves) by looking up the entry via
`getStoredShaderAndCase` (O(1)) and calling `entry.getEffectFunction().execute(...)`.

---

## Obtaining shaders

- **Crates / chest loot:** a uniform pick from `chestLootShaders`, which is pre-weighted by
  repeated entries.
- **Shader Grab Bags** (`ItemShaderBag`): on use, call
  `ShaderRegistry.getRandomShader(player, rng, bagRarity, true)`. The `true` records the
  result in `receivedShaders` and recalculates the player's weights so duplicates become
  rarer over time. `RecipeShaderBags` (`common/crafting/RecipeShaderBags.java`) handles the
  crafting side of bags.
- **Replication:** an existing shader can be copied for its `replicationCost`
  (`IngredientStack`, scaled by rarity).

---

## Performance characteristics (summary)

- **Registry lookup** (`getShader`, `getCase`): O(1) HashMap operations. Used per render
  and per effect dispatch.
- **Render application**: O(layer count) per model group, no registry/ore work — constant
  and cheap.
- **`compileWeight`**: one-time O(#shaders × 5) at post-init.
- **`getRandomShader` / `recalculatePlayerTotalWeight`**: O(#shaders), but only on
  bag-open / shader-grant, never per tick or per frame.

The shader system is **not** a server-tick concern; its only linear work is gated behind
infrequent player actions.
