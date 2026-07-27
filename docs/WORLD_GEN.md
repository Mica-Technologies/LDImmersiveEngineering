# World Generation

Technical documentation for Immersive Engineering's world-generation subsystem
(Forge 1.12.2): ore veins, the excavator mineral-mix / vein system, retrogen, and
the engineer's house village structure.

## Overview

IE adds three distinct world-gen mechanisms:

1. **Ore veins** — classic `WorldGenMinable` ore spawning (copper, bauxite, lead,
   silver, nickel, uranium) driven by `IEWorldGen`, an `IWorldGenerator`.
2. **The excavator mineral system** — a virtual, per-chunk "mineral mix" map used by
   the Excavator multiblock and the Core Sample Drill. This is **not** placed blocks;
   it is a logical deposit assigned to each chunk and stored in world save data
   (`ExcavatorHandler` / `MineralMix`).
3. **Retrogen** — re-runs ore-vein generation on already-generated chunks that were
   created before IE was installed (or before a given ore was enabled).

Plus a village structure (`VillageEngineersHouse`).

Code lives in `src/main/java/blusunrize/immersiveengineering/common/world/` and
`src/main/java/blusunrize/immersiveengineering/api/tool/ExcavatorHandler.java`.

---

## Ore Veins (`IEWorldGen`)

`common/world/IEWorldGen.java` implements `IWorldGenerator` and is also subscribed to
chunk save/load and world-tick events.

### OreGen descriptor

Each ore is described by a static `OreGen` instance
(`IEWorldGen.java:38-67`):

| Field | Meaning |
|---|---|
| `name` | identifier, also used as the retrogen key suffix |
| `mineableGen` | a `WorldGenMinable(state, maxVeinSize, BlockMatcher.forBlock(target))` |
| `minY` / `maxY` | vertical band the vein may spawn in |
| `chunkOccurence` | number of placement attempts per chunk |
| `weight` | percent chance (out of 100) each attempt actually places |

`OreGen.generate` (`IEWorldGen.java:57-66`) loops `chunkOccurence` times; on each pass
it rolls `rand.nextInt(100) < weight` and, if it passes, picks a random position inside
the 16×16 chunk within `[minY, maxY)` and calls `WorldGenMinable.generate`. Ores always
replace `Blocks.STONE` (`addOreGen`, `IEWorldGen.java:73-78`).

Registration is via the static `addOreGen(name, state, maxVeinSize, minY, maxY,
chunkOccurence, weight)` helper, which appends to `orespawnList` and returns the
descriptor (so the caller can further configure it). The IE ore set is registered from
config during mod init.

### Generation entry point

`generate(...)` (`IEWorldGen.java:80-84`) — the `IWorldGenerator` callback — simply
delegates to `generateOres(random, chunkX, chunkZ, world, true)` with
`newGeneration=true`.

`generateOres` (`IEWorldGen.java:86-92`):

- Skips entirely if the dimension is in `oreDimBlacklist`.
- Iterates `orespawnList`; each `OreGen` generates **either** when this is fresh
  generation (`newGeneration`) **or** when its retrogen flag
  (`retrogenMap.get("retrogen_"+name)`) is set.

> Note: when `newGeneration` is `false` (retrogen path), `retrogenMap` is read for
> every ore. A missing key returns a `null` `Boolean`, so the map must contain an entry
> for every registered ore name or this auto-unboxes to an NPE — entries are populated
> at init for each ore.

---

## Retrogen

Retrogen lets IE add its ores to chunks that already exist on disk. It works in three
stages:

### 1. Flagging on chunk save (`chunkSave`, `IEWorldGen.java:94-100`)

On `ChunkDataEvent.Save`, IE writes an `ImmersiveEngineering` NBT compound into the
chunk data containing a single boolean keyed by `IEConfig.Ores.retrogen_key`. This is a
**marker**: any chunk that has been saved while IE was installed carries this tag, so IE
knows it has already been processed.

### 2. Detecting candidates on chunk load (`chunkLoad`, `IEWorldGen.java:102-112`)

On `ChunkDataEvent.Load`, IE checks whether the loaded chunk's
`ImmersiveEngineering` compound is **missing** the retrogen key. If it is missing **and**
at least one ore's retrogen config is enabled (`retrogen_copper`, `retrogen_bauxite`,
`retrogen_lead`, `retrogen_silver`, `retrogen_nickel`, `retrogen_uranium`), the chunk
position is queued into `retrogenChunks`, an
`ArrayListMultimap<Integer, ChunkPos>` keyed by dimension id
(`IEWorldGen.java:114`). Optionally logs the flagged chunk
(`retrogen_log_flagChunk`).

### 3. Processing on world tick (`serverWorldTick`, `IEWorldGen.java:116-142`)

On the server-side `WorldTickEvent` (END phase only):

- Reads the queue for the current dimension.
- Processes **up to 2 chunks per tick** (the `for(i = 0; i < 2; i++)` loop).
- For each, it reconstructs a deterministic per-chunk `Random` from the world seed
  (the same `xSeed*chunkX + zSeed*chunkZ ^ worldSeed` scheme vanilla/FML uses), then
  calls `generateOres(..., newGeneration=false)` so only retrogen-enabled ores spawn.
- Removes the processed chunk from the queue.
- Optionally logs progress and remaining count (`retrogen_log_remaining`).

The 2-per-tick throttle bounds the per-tick cost of a large backlog (e.g. after enabling
IE on an existing world). See `PERFORMANCE` notes — the scan itself is cheap, but a huge
backlog still trickles for a long time.

---

## Excavator Mineral System (`ExcavatorHandler` / `MineralMix`)

`api/tool/ExcavatorHandler.java` owns the chunk→ore-deposit calculation. Unlike ore
veins, these "minerals" are **virtual**: a chunk is assigned a `MineralMix` deposit that
the Excavator multiblock mines from, and the Core Sample Drill / treasure maps reveal.

### Data model

- **`MineralMix`** (`ExcavatorHandler.java:132-294`) — a named deposit definition:
  `ores` (ore-dictionary names) with parallel `chances`, an overall `failChance`,
  optional dimension whitelist/blacklist, and optional `replacementOres` fallbacks.
  - `recalculateChances` (`:165-191`) resolves each ore name to a preferred `ItemStack`
    via `IEApi.getPreferredOreStack`, dropping any that don't exist in the ore
    dictionary, and normalizes the surviving chances into `recalculatedChances` (sum to
    1.0). Sets `isValid` if at least one ore resolved. This must run after ore
    dictionary population.
  - `getRandomORE` → `getRandomOre(Random)` (`:193-203`) — weighted pick over
    `recalculatedChances`.
  - `validDimension(dim)` (`:210-227`) — whitelist beats blacklist.
- **`MineralWorldInfo`** (`ExcavatorHandler.java:296-333`) — per-chunk assignment:
  `mineral` (rolled deposit), `mineralOverride` (forced via command), and `depletion`
  (how much has been mined). Serialized to/from NBT.
- **Registries:**
  - `mineralList : LinkedHashMap<MineralMix, Integer>` — every registered mix and its
    spawn weight (`ExcavatorHandler.java:39`).
  - `mineralCache : HashMap<DimensionChunkCoords, MineralWorldInfo>` — the per-chunk
    assignment cache (`:40`). This is the persistent vein map.
  - `dimensionPermittedMinerals` — cache of which mixes are legal per dimension (`:41`),
    cleared on recalculation.

### Registration

`addMineral(name, mineralWeight, failChance, ores[], chances[])`
(`ExcavatorHandler.java:47-53`) builds a `MineralMix` and puts it in `mineralList`.

`recalculateChances(mutePackets)` (`:55-68`) recomputes every mix's resolved ore stacks,
clears `dimensionPermittedMinerals`, and — on the server when packets are allowed —
broadcasts the full mineral list to all clients via `MessageMineralListSync` so client
GUIs/tooltips know deposit contents.

### Per-chunk deposit selection

`getMineralWorldInfo(world, chunkCoords, guaranteed)`
(`ExcavatorHandler.java:89-123`) is the core. Server-side only.

1. Returns the cached `MineralWorldInfo` if present (the common case).
2. On a cache miss, it derives a deterministic `Random` from the chunk
   (`world.getChunk(...).getRandomWithSeed(940610)`) and rolls `dd = r.nextDouble()`.
3. If `!guaranteed && dd > mineralChance`, the chunk is **empty** (no deposit).
4. Otherwise it builds a **`MineralSelection`** (`:335-378`) over a 5×5 neighbourhood
   (radius 2): it collects the mixes already assigned to surrounding cached chunks, then
   considers every mix in `mineralList` that is valid, legal for the dimension, **and not
   already present in a neighbour** — accumulating their weights. A weighted random pick
   selects the deposit. (This neighbour-exclusion spreads deposit types out spatially.)
5. The result (deposit or `null`) is cached and returned.

`getRandomMineral(world, cx, cz)` (`:70-82`) is what the Excavator tile calls each work
cycle: it fetches the world info, returns `null` if the chunk is empty or the deposit is
depleted past `mineralVeinCapacity`, otherwise returns the override or rolled mineral.

`depleteMinerals(world, cx, cz)` (`:125-130`) increments `depletion` and marks the
dimension's save data dirty.

### Consumers

- **Excavator multiblock** — `TileEntityExcavator.update`
  (`common/blocks/metal/TileEntityExcavator.java:88-...`): when running and powered, it
  calls `getRandomMineral` (`:132`), rolls an ore from the mix (`mineral.getRandomOre`,
  `:163`), checks `excavator_fail_chance` and the mix's `failChance`, deposits the result
  into the bucket wheel, and calls `depleteMinerals` (`:172`).
  `getComparatorInputOverride` (`:70-85`) also reads world info to report remaining vein
  percentage as a redstone signal.
- **Core Sample Drill** — `TileEntitySampleDrill` reads `getMineralWorldInfo`
  (`TileEntitySampleDrill.java:89`) to print the deposit name. The same `createCoreSample`
  call also takes an oil reading via `ReservoirSurvey`
  (`common/util/petroleum/ReservoirSurvey.java`) and bakes presence, a size band and a
  pressure percentage into the sample's NBT, so the item tooltip and the placed sample's
  block overlay can render it client-side without asking the server-only reservoir map.
  Samples cut before this existed carry no survey marker and stay silent about oil.
- **Treasure-map villager trade** — `IEVillagerHandler.OreveinMapForEmeralds`
  (`common/util/IEVillagerHandler.java:270-...`) searches up to 8 random nearby
  *uncached* chunks (`:279-286`), forces deposit generation with `guaranteed=true`
  (`:290`), and builds a map pointing at the deposit.
- **`/ie mineral` command** — see UTILITIES.md.

### Persistence

The mineral cache is saved in `IESaveData` (world-attached save data):

- Load (`IESaveData.java:63-74`): clears `mineralCache`, then reads each entry from the
  `mineralDepletion` NBT list and repopulates the cache.
- Save (`IESaveData.java:118-126`): writes every non-null cache entry. Marked dirty by
  `depleteMinerals` and by the mineral commands.

---

## Engineer's House (`VillageEngineersHouse`)

`common/world/VillageEngineersHouse.java` extends vanilla
`StructureVillagePieces.Village` and registers an `IVillageCreationHandler` so vanilla
villages can spawn an engineer's house. It places the structure block-by-block, fills a
`TileEntityWoodenCrate` with loot from the `chests/engineers_house` loot table
(`:49`), and spawns the IE engineer villager (`IEVillagerHandler`). Standard vanilla
village-piece pattern; no per-tick cost.

---

## Configuration Summary

| Config | Effect |
|---|---|
| `IEConfig.Ores.ore_*` | per-ore vein parameters (vein size, Y-band, occurrence, weight) |
| `IEConfig.Ores.retrogen_*` | per-ore retrogen enable flags |
| `IEConfig.Ores.retrogen_key` | NBT marker key written on chunk save |
| `IEConfig.Ores.retrogen_log_flagChunk` / `retrogen_log_remaining` | retrogen logging |
| `ExcavatorHandler.mineralChance` | probability a chunk has any deposit |
| `ExcavatorHandler.mineralVeinCapacity` | depletion limit (`< 0` = infinite) |
| `ExcavatorHandler.defaultDimensionBlacklist` | default dimension blacklist for new mixes |
