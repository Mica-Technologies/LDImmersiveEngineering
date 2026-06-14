# Multiblock System

Technical documentation for the multiblock structure framework and the machines built on
it in Immersive Engineering (Forge 1.12.2).

## Overview

Immersive Engineering's big machines (Crusher, Arc Furnace, Assembler, Diesel Generator, ...)
are *multiblocks*: the player builds a fixed arrangement of vanilla/IE blocks in the world,
then right-clicks it with an Engineer's Hammer. If the arrangement matches a registered
structure, every block in the footprint is replaced by a single "machine block" whose tile
entity (TE) stores the machine's logic. Of those TEs, exactly one is the **master** (it owns
the inventory, tanks, energy buffer and process queue); the rest are **dummies / slaves**
that simply forward interactions to the master and render their slice of the model.

There are two parallel families:

- **Metal multiblocks** — the energy-driven machines. Base class
  `TileEntityMultiblockMetal` (`common/blocks/metal/`). They have a Flux (FE-compatible)
  energy buffer and a generic per-tick *process queue*.
- **Stone multiblocks** — the early-game furnaces (Coke Oven, Blast Furnace, Alloy Smelter).
  They extend `TileEntityMultiblockPart` directly (no energy buffer) and implement their own
  simple `process`/`processMax` countdown in `update()`.

```
                       MultiblockHandler.IMultiblock           (api/MultiblockHandler.java)
                       ┌──────────────────────────────┐
   Engineer's Hammer ─►│ isBlockTrigger(state)        │  cheap pre-filter
   right-click          │ createStructure(world,pos..) │  full structure check + place
                       └──────────────┬───────────────┘
                                      │ on success: world.setBlockState(...) for each part
                                      ▼
        TileEntityMultiblockPart  (common/blocks/TileEntityMultiblockPart.java)
        formed, pos, offset[3], mirrored, facing
          │  master()  = TE at  getPos() - offset
          │  isDummy() = offset != (0,0,0)
          ├── TileEntityMultiblockMetal<T,R extends IMultiblockRecipe>   (energy + process queue)
          │     └── TileEntityCrusher / Arc Furnace / Assembler / Squeezer / ...
          └── (direct) TileEntityCokeOven / TileEntityBlastFurnace / TileEntitySilo / ...
```

## Code organization

| Path | Contents |
|---|---|
| `api/MultiblockHandler.java` | `IMultiblock` interface + global registry + formation events |
| `api/crafting/IMultiblockRecipe.java` | recipe contract for metal machines (inputs/outputs, process time/energy) |
| `api/crafting/MultiblockRecipe.java` | base impl with JEI helpers |
| `api/crafting/*Recipe.java` | per-machine recipe types (`CrusherRecipe`, `ArcFurnaceRecipe`, ...) |
| `common/blocks/multiblocks/Multiblock*.java` | the `IMultiblock` structure definitions (shape, trigger, materials, manual render) |
| `common/blocks/TileEntityMultiblockPart.java` | shared part logic: master/slave, formation offsets, disassembly, fluid wrapper |
| `common/blocks/metal/TileEntityMultiblockMetal.java` | energy buffer + process queue + the two process types |
| `common/blocks/metal/TileEntity*.java` | metal machine TEs |
| `common/blocks/stone/TileEntity*.java` | stone furnace TEs |
| `common/blocks/BlockIEMultiblock.java` | block class: break/disassemble, drops, pick-block |

## The structure framework

### IMultiblock and registration

Every structure is a singleton implementing `MultiblockHandler.IMultiblock`
(`api/MultiblockHandler.java:47`) and registered into a global `ArrayList` via
`MultiblockHandler.registerMultiblock` (`api/MultiblockHandler.java:36`). Key methods:

- `isBlockTrigger(IBlockState)` — a cheap test ("is this the block you'd hammer?") so the
  hammer doesn't run every structure's full check. e.g. `MultiblockCrusher.java:132` triggers
  only on a steel fence block.
- `createStructure(world, pos, side, player)` — the expensive check. It walks the expected
  footprint with `Utils.isBlockAt` / `Utils.isOreBlockAt` (see
  `MultiblockCrusher.structureCheck`, `MultiblockCrusher.java:192`), tries both mirrorings,
  fires the `MultiblockFormEvent`, and on success replaces every position with the machine
  block while writing each part's `pos` / `offset` / `mirrored` / `facing`
  (`MultiblockCrusher.java:167-188`).

### Part identity: pos, offset, mirror

`TileEntityMultiblockPart` (`common/blocks/TileEntityMultiblockPart.java:40-47`) stores:

- `formed` — part of an assembled structure.
- `pos` — a linear index into the structure's `H×L×W` grid.
- `offset[3]` — vector from this part back to the master. `(0,0,0)` *is* the master.
- `mirrored`, `facing` — orientation, used to map grid coordinates to world coordinates.

`master()` (`TileEntityMultiblockPart.java:262`) returns `this` when offset is zero, else
looks up the TE at `getPos() - offset`. `getBlockPosForPos(int)`
(`TileEntityMultiblockPart.java:342`) converts any grid index to a world `BlockPos` using
facing+mirror — this is how a machine addresses its own "energy block", "redstone block",
output inventory, etc. without storing extra coordinates.

### Disassembly

`disassemble()` (`TileEntityMultiblockPart.java:295`) iterates the full `H×L×W` footprint,
restores each part's `getOriginalBlock()` and clears `formed`. `BlockIEMultiblock.breakBlock`
(`common/blocks/BlockIEMultiblock.java:47`) calls it and drops the master's inventory
(`getDroppedItems()`). The `onlyLocalDissassembly` timestamp
(`TileEntityMultiblockPart.java:47`, checked at `:317`) prevents a drill/hammer from
disassembling the same structure twice in one tick.

### Fluid access

`TileEntityMultiblockPart` exposes a `MultiblockFluidWrapper`
(`TileEntityMultiblockPart.java:144`) on faces where `getAccessibleFluidTanks(side)` is
non-empty. Subclasses decide per-`pos` which faces expose which tanks (e.g.
`TileEntityAssembler.getAccessibleFluidTanks`, `TileEntityAssembler.java:551`).

## The metal-machine process model

`TileEntityMultiblockMetal<T, R extends IMultiblockRecipe>`
(`common/blocks/metal/TileEntityMultiblockMetal.java:56`) adds an energy buffer
(`FluxStorageAdvanced energyStorage`) and a generic process queue. The master holds the real
buffer; slaves forward to it via `getFluxStorage()` (`:168`). Energy is only accepted on the
designated "energy" positions (`getEnergyPos()`, `isEnergyPos()`, `:157`).

### Recipes — IMultiblockRecipe

`IMultiblockRecipe` (`api/crafting/IMultiblockRecipe.java:26`) is the contract the process
queue consumes:

- `getItemInputs()` / `getFluidInputs()` — `IngredientStack` / `FluidStack` requirements.
- `getItemOutputs()` / `getFluidOutputs()` (+ `getActual*Outputs(tile)` for randomized
  results, e.g. crusher secondary drops at `CrusherRecipe.java:54`).
- `getTotalProcessTime()` / `getTotalProcessEnergy()` — drive ticks and energy/tick.
- `getMultipleProcessTicks()` — how many ticks may be collapsed into one when surplus energy
  is available (crusher returns 4, `CrusherRecipe.java:159`).

Each recipe type keeps a `static ArrayList recipeList` and a `findRecipe(...)` that does a
**linear scan** matching the input (e.g. `CrusherRecipe.findRecipe`, `CrusherRecipe.java:118`;
`ArcFurnaceRecipe.findRecipe`, `ArcFurnaceRecipe.java:228`).

### The two process types

Both live inside `TileEntityMultiblockMetal`:

1. **`MultiblockProcessInWorld`** (`:800`) — items physically sitting in the world (dropped on
   the machine). Used by `isInWorldProcessingMachine()==true` machines: Crusher, Metal Press.
   The item is pushed in via `MultiblockInventoryHandler_DirectProcessing.insertItem` (`:892`)
   or by `onEntityCollision`, which immediately builds a process and queues it.

2. **`MultiblockProcessInMachine`** (`:641`) — items in fixed inventory input slots. Used by
   GUI machines: Arc Furnace, Squeezer, Fermenter, Mixer, Refinery, Bottling Machine. The
   process remembers `inputSlots`/`inputTanks`; on finish it shrinks those slots
   (`processFinish`, `:723`).

### The master tick

`TileEntityMultiblockMetal.update()` (`:325-349`) is the heart of every metal machine:

```
update():
  ApiUtils.checkForNeedlessTicking(this)      // de-tick dummies (see Performance)
  tickedProcesses = 0
  if world.isRemote || isDummy() || isRSDisabled(): return
  max = getMaxProcessPerTick()
  for each process in processQueue (up to max):
     if process.canProcess(this):             // energy available + output space free
        process.doProcessTick(this)           // spend energy, advance processTick
        updateMasterBlock(null, true)         // markDirty + block update
     if process.clearProcess: remove it
```

`canProcess` (`:492`) checks the energy buffer can supply `energyPerTick`, that output slots
or tanks have room, then `additionalCanProcessCheck`. `doProcessTick` (`:551`) extracts
energy and increments `processTick`; on reaching `maxTicks` it runs `processFinish` (`:584`),
which writes outputs into slots/tanks and sets `clearProcess`.

Note the master only does real work when its `processQueue` is non-empty. Filling that queue
— scanning the inventory for a matching recipe — is each subclass's responsibility, done in
its own overridden `update()` (which calls `super.update()` first). See the Performance notes
for the cost of those scans.

## Machine catalog

| Machine | Tile Entity | Structure def | Recipe type | Process model |
|---|---|---|---|---|
| Crusher | `metal/TileEntityCrusher` | `MultiblockCrusher` | `CrusherRecipe` | in-world (drop-in), queue ≤2048 |
| Metal Press | `metal/TileEntityMetalPress` | `MultiblockMetalPress` | `MetalPressRecipe` (mold-gated) | in-world, queue ≤3 |
| Arc Furnace | `metal/TileEntityArcFurnace` | `MultiblockArcFurnace` | `ArcFurnaceRecipe` (+additives, electrodes) | in-machine, 12 parallel |
| Assembler | `metal/TileEntityAssembler` | `MultiblockAssembler` | vanilla `IRecipe` via `AssemblerHandler` | custom (3 crafting patterns) |
| Auto-Workbench | `metal/TileEntityAutoWorkbench` | `MultiblockAutoWorkbench` | `BlueprintCraftingRecipe` | custom (blueprint) |
| Bottling Machine | `metal/TileEntityBottlingMachine` | `MultiblockBottlingMachine` | `BottlingMachineRecipe` | custom `BottlingProcess` queue |
| Mixer | `metal/TileEntityMixer` | `MultiblockMixer` | `MixerRecipe` (fluid + items) | in-machine |
| Refinery | `metal/TileEntityRefinery` | `MultiblockRefinery` | `RefineryRecipe` (fluid+fluid) | in-machine |
| Squeezer | `metal/TileEntitySqueezer` | `MultiblockSqueezer` | `SqueezerRecipe` | in-machine |
| Fermenter | `metal/TileEntityFermenter` | `MultiblockFermenter` | `FermenterRecipe` | in-machine |
| Diesel Generator | `metal/TileEntityDieselGenerator` | `MultiblockDieselGenerator` | none (`DieselHandler` fuel) | burns fuel → FE/tick |
| Excavator | `metal/TileEntityExcavator` | `MultiblockExcavator` | none (mineral veins) | drives Bucket Wheel |
| Bucket Wheel | `metal/TileEntityBucketWheel` | `MultiblockBucketWheel` | none | rotating digger for Excavator |
| Lightning Rod | `metal/TileEntityLightningrod` | `MultiblockLightningrod` | none | lightning → FE burst |
| Silo | `metal/TileEntitySilo` | `MultiblockSilo` | none | bulk single-item storage |
| Sheetmetal Tank | `metal/TileEntitySheetmetalTank` | `MultiblockSheetmetalTank` | none | bulk fluid storage |
| Coke Oven | `stone/TileEntityCokeOven` | `MultiblockCokeOven` | `CokeOvenRecipe` | countdown, no energy |
| Blast Furnace | `stone/TileEntityBlastFurnace` | `MultiblockBlastFurnace` | `BlastFurnaceRecipe` (+fuel) | countdown, no energy |
| Adv. Blast Furnace | `stone/TileEntityBlastFurnaceAdvanced` | `MultiblockBlastFurnaceAdvanced` | `BlastFurnaceRecipe` | extends Blast Furnace |
| Alloy Smelter | `stone/TileEntityAlloySmelter` | `MultiblockAlloySmelter` | `AlloyRecipe` | countdown, no energy |

### Notes on individual machines

**Crusher** (`TileEntityCrusher.java`) — drop items onto the internal bay (or pipe into the
top hopper face); `onEntityCollision` (`:300`) finds a `CrusherRecipe` and queues an in-world
process. Also grinds living entities for energy (`:330`). Output is pushed into the inventory
below-behind (`doProcessOutput`, `:367`).

**Metal Press** (`TileEntityMetalPress.java`) — requires a *mold* item placed by hand
(`interact`, `:96`). Items arriving on the conveyor face (`onEntityCollision`, `:152`) are
matched by `MetalPressRecipe.findRecipe(mold, stack)` and pressed. Max 3 in flight with a
minimum spacing (`getMinProcessDistance`, `:236`).

**Arc Furnace** (`TileEntityArcFurnace.java`) — the most complex. 12 input slots, 4 additive
slots, 6 output slots, a slag slot, and 3 consumable *electrode* slots. Its `update()`
(`:72`) damages electrodes while running (`:100`), scans the 12 input slots for recipes
(`:134-154`), and every 8 ticks (`:157`) pushes finished output into the adjacent inventory.
Processing requires `hasElectrodes()` (`:725`). Runs up to 12 processes in parallel.

**Assembler** (`TileEntityAssembler.java`) — not a recipe-list machine. Holds 3 user-defined
3×3 crafting *patterns* (`CrafterPatternInventory`, `:580`) resolved against vanilla recipes.
Its `update()` (`:152`) is throttled by position hash (`:156`), then for each pattern gathers
available stacks, runs `AssemblerHandler` to query inputs, and crafts if energy + ingredients
suffice. Can pull container items back out and optionally recurse on sub-ingredients.

**Diesel Generator** (`TileEntityDieselGenerator.java`) — not a process machine. `update()`
(`:80`) reads `DieselHandler.getBurnTime` for the fuel in its tank and, if downstream FE
receivers exist, drains fuel and pushes `dieselGen_output` FE/tick split across up to three
output blocks (`:122-160`).

**Excavator + Bucket Wheel** — the Excavator (`TileEntityExcavator.java:88`) spins the
adjacent `TileEntityBucketWheel` (`TileEntityBucketWheel.java:103`), consuming energy and
pulling ore from the chunk's mineral vein. The Bucket Wheel is a plain
`TileEntityMultiblockPart` (no energy) whose `update()` only animates/syncs.

**Coke Oven / Blast Furnace / Alloy Smelter** (stone) — these are pre-electricity. Their
`update()` (e.g. `TileEntityCokeOven.java:102`) runs a simple `process--` countdown, calls
`getRecipe()` to validate the current job, and on completion writes the output and (coke oven)
fills its creosote tank. No `TileEntityMultiblockMetal`, no FE.

## How to extend

To add a new energy-driven multiblock machine:

1. **Recipe type** — create `api/crafting/MyRecipe.java extends MultiblockRecipe`
   implementing `IMultiblockRecipe`. Keep a `static List recipeList`, an `addRecipe(...)`,
   a `findRecipe(...)` (linear scan), and `writeToNBT` / `loadFromNBT` so in-flight processes
   survive save/load.

2. **Structure definition** — create
   `common/blocks/multiblocks/MultiblockMyMachine.java implements IMultiblock`. Build the
   `ItemStack[H][L][W]` template, implement `isBlockTrigger` (cheap), `createStructure`
   (full check + placement writing `pos`/`offset`/`mirrored`/`facing` per part), plus the
   manual-render methods. Register it via `MultiblockHandler.registerMultiblock` and add its
   instance to the formation handler.

3. **Tile entity** — create
   `common/blocks/metal/TileEntityMyMachine extends TileEntityMultiblockMetal<TileEntityMyMachine, MyRecipe>`.
   Pass `{H,L,W}`, energy capacity, redstone-control flag to `super`. Implement the abstract
   members: `getEnergyPos()`, `getRedstonePos()`, `getOutputSlots()`/`getOutputTanks()`,
   `getInternalTanks()`, `findRecipeForInsertion`, `readRecipeFromNBT`,
   `isInWorldProcessingMachine`, `getMaxProcessPerTick`, `getProcessQueueMaxLength`,
   `getMinProcessDistance`, and the `doProcessOutput`/`onProcessFinish` hooks.

4. **Tick logic** — override `update()`, call `super.update()` first, then (server side,
   non-dummy, RS-enabled) scan your inputs for a recipe and `addProcessToQueue`. The base
   `update()` advances and finishes queued processes for you. Expose item/fluid handler
   capabilities on the appropriate `pos`+face in `hasCapability`/`getCapability`.

5. **Register** the block meta in `BlockTypes_MetalMultiblock` + `IEContent`, and bind the TE.

For a non-energy (stone-style) machine, extend `TileEntityMultiblockPart` directly and
implement your own `process`/`processMax` countdown in `update()` as the Coke Oven does.
