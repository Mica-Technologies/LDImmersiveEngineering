# The Petroleum System

A fork-only feature: oil reservoirs rolled deterministically under the world, and the
above-ground equipment that finds, taps and (eventually) refines them.

Like the excavator's mineral veins, deposits are **virtual** — nothing is placed underground and
nothing runs at chunk-gen time. A cell is rolled the first time anything asks what is beneath it,
from a hash of the world seed and the cell's coordinates. That makes world generation free,
gives existing worlds oil retroactively with no retrogen pass, and means only cells somebody has
actually drawn from are worth persisting.

**Status: the whole feature is implemented.** Extraction, refining, the gas branch, large-scale
power generation, buried storage, the virtual fluid network, the forecourt, deep refining and
enhanced recovery are all in the tree, hammer-formable where they are multiblocks, covered by the
manual and tested where the logic is world-free.

**Not playtested.** Everything from the power plants onward has been built without a play session
behind it. The arithmetic is unit-tested and the server smoke run is clean, but no number in the
back half of this document has been judged in a game.

The only thing from the plan deliberately left out is **a vehicle of this fork's own**, whose
decision was always deferred: the Gas Station Pump and the Fuel Nozzle already target any entity
with a fluid capability, so one could be added with no change to anything here. Somebody else's
vehicles are handled — see [Immersive Vehicles (MTS)](#immersive-vehicles-mts).

Line numbers in this document are a reading aid, not a contract; the tree moves under them.

---

## The parts

| Block | State | What it does |
|---|---|---|
| **Wellhead** | Complete | Caps a drilled bore. Collects whatever the deposit gives up and pushes it into any adjacent fluid handler. |
| **Drilling Derrick** | Complete | A 3×3×9 lattice tower, hammered together from Oilfield Frame. Sinks a bore over one minute of powered drilling, then hands over a Wellhead and packs itself back into frame. |
| **Pumpjack** | Complete | A 3×6×5 machine that drives a Wellhead whose deposit has fallen below free-flow pressure. Carries no fluid of its own. |
| **Distillation Tower** | Complete | A 4×4×14 column that splits crude into its seven cuts, drawn off at heights matching the column order. |
| **Industrial Burner** | Complete | A 3×3×3 firebox that burns the fuels nothing else wants for process heat, never Flux. |
| **Flare Stack** | Complete | A single block that destroys gas nobody has anywhere better to put. Yields nothing, deliberately — it is what makes building a Scrubber pay for itself. |
| **Gas Scrubber** | Complete | A 3×3×6 pair of vessels that cleans raw wellhead gas into natural gas, dropping sulfur out of it. |
| **Gas Turbine** | Complete | A 3×6×3 machine that burns natural gas for Flux, with a spool-up curve that makes it a peaker rather than a base load. |
| **Lubrication Manifold** | Complete | A single block that bolts onto a working machine and rebates part of its running cost while it has lubricant. |
| **Propane Cylinder** | Complete | A portable four-bucket bottle that keeps its contents when broken. The domestic-scale answer to "I have propane, now what". |
| **Upright Propane Tank** | Complete | The same vessel in a taller body: twelve buckets, for a building rather than a barbecue. |
| **Torpedo Propane Tank** | Complete | The horizontal drum on saddles: twenty-four buckets, and what a house actually runs on. |
| **Fuel Oil Boiler** | Untested | A 5×5×7 furnace that makes steam rather than Flux. What finally gives heavy fuel oil a job at scale. |
| **Heat Recovery Steam Generator** | Untested | A 3×5×3 tube bank that makes steam from a Gas Turbine's exhaust and burns nothing of its own. |
| **Steam Turbine Hall** | Untested | 5×9×5, the largest structure here and the most efficient thing in the mod at turning fuel into Flux. |
| **Reciprocating Engine Bank** | Untested | 4×5×5 of cylinders. Instant response, mediocre efficiency, and the only plant that scales by building another one. |
| **Domestic / Commercial / Bulk buried tanks** | Untested | 2×2×2, 5×5×4 and 9×9×6 shells in the ground, showing only a fill cap. Storage that is not an eyesore. |
| **Gas Station Pump** | Untested | Two blocks hammered into one bowser: what hands fuel to a person rather than a machine, with the price on its own panel and reported through an event. |
| **Fluid Loading Gantry** | Untested | Two legs and a bay that fills portable containers out of one chest and into another. |
| **Portable Generator** | Untested | One block, 256 Flux/t, gasoline or ethanol. The consumer gasoline never had. |
| **Cracking Unit** | Untested | 5×3×6. Breaks heavy cuts into light ones, at whatever severity its firebox pays for. |
| **Re-injection Well** | Untested | Puts water or gas back downhole for a bounded second tranche out of a tiring field. |
| **Fluid Inlet / Outlet / Main Valve / Fluid Control Console** | Untested | The virtual fluid network — see `docs/FLUID_NETWORK.md`. |

Oilfield Frame (`BlockTypes_PetroleumDevice.OILFIELD_FRAME`,
`src/main/java/blusunrize/immersiveengineering/common/blocks/petroleum/BlockTypes_PetroleumDevice.java:32`)
is the structural block both the Derrick and the Pumpjack are built from, and the item a
disassembled rig or an unformed multiblock part drops back into.

---

## Reservoirs

### Rolling a cell

`ReservoirHandler` (`src/main/java/blusunrize/immersiveengineering/api/petroleum/ReservoirHandler.java`)
is the registry of deposit types and the map of what lies underground, modelled directly on
`ExcavatorHandler` and for the same reasons (class doc,
`ReservoirHandler.java:17-29`).

Deposits are rolled per **cell** of `PetroleumConfig.cellChunkSize` square chunks (default 8),
not per chunk, so a field covers a believable area and neighbouring chunks agree about what is
beneath them (`ReservoirHandler.java:30-32`). `getReservoir` (`ReservoirHandler.java:174-188`)
converts a chunk to its cell with `toCell` (`ReservoirHandler.java:165-168`), checks an in-memory
cache keyed by `CellPos{dimension, cellX, cellZ}`, and on a miss rolls the cell with a
`Random` seeded by `cellSeed` (`ReservoirHandler.java:197-204`) — the world seed mixed with the
cell's coordinates and dimension using vanilla's own odd multipliers, chosen only so neighbouring
cells land far apart in the sequence rather than producing a visibly correlated field.

`rollCell` (`ReservoirHandler.java:146-156`) draws the presence roll **unconditionally**, before
checking it against `PetroleumConfig.cellChance`, specifically so the `Random` stream advances the
same way regardless of outcome — the result must not depend on how many types happen to be
registered. If the roll fails or nothing is registered, the cell is an empty `Reservoir`.
`PetroleumConfig.enabled=false` or a dimension listed in `PetroleumConfig.dimensionBlacklist`
short-circuits straight to an empty reservoir without touching the `Random` at all
(`ReservoirHandler.java:181-185`).

Reservoir **type** is registered by name, not by `Fluid` reference — `ReservoirType`
(`api/petroleum/ReservoirType.java:17-22`) — for the same reason `DieselHandler` keys fuels by
name: a type can be declared before the fluid registry has settled, a name never goes stale, and
the class stays free of anything that cannot construct in a test JVM. `ReservoirHandler`'s only
shipped type is `crude_oil` → `ie_crude_oil`, weight 1
(`ReservoirHandler.java:91,103-108`). `registerDefaults()` is called both at load and on every
config reload, because the capacity bounds are baked into the type object at construction time —
without a re-call, raising `petroleumMaxCapacity` would silently do nothing until a restart.
Deposits already rolled keep the size they rolled at; re-registering types never touches the
cache, so a config reload cannot rewrite a player's field out from under them
(`ReservoirHandler.java:96-102`).

### Capacity

`ReservoirModel.rollCapacity` (`api/petroleum/ReservoirModel.java:52-60`) rolls between a type's
`minCapacity` and `maxCapacity` **log-distributed**, not uniform: `min * (max/min)^random()`. A
uniform roll over a 2M–16M mB range would make the average field enormous and a small one
vanishingly rare; log-weighting makes a modest field the common case and a giant one a genuine
event, which is also why `ReservoirSurvey`'s size bands are cut on a log scale (below).

### Persistence

Only cells that have actually been **drawn from** are worth saving — an unworked cell re-rolls
identically from the seed on the next load, so persisting it would be pure noise.
`getDirtyCells()` (`ReservoirHandler.java:230-240`) filters on `remaining != originalCapacity`.
`PetroleumSaveData` (`common/util/petroleum/PetroleumSaveData.java`) is its own
`WorldSavedData` (`dataName = "ImmersiveEngineering-PetroleumData"`,
`PetroleumSaveData.java:41`), attached to the overworld's map storage — same reasoning as
`GridSaveData` in the virtual grid: the wire save path stays unbloated and the whole feature
stays removable. `ReservoirHandler.clear()` runs before a world loads
(`PetroleumSaveData.load`, `PetroleumSaveData.java:72-84`), the same trap `serverStarted` avoids
for wires and the grid — otherwise a second world in one server process would inherit the first
world's depletion.

---

## The flow model

Everything about how a deposit behaves lives in `ReservoirModel`
(`api/petroleum/ReservoirModel.java`), which touches neither `World` nor `TileEntity` — the same
split the virtual grid draws between `GridEngine` and its tile entities, and for the same reason:
the decline curve, the free-flow threshold, the residual seep and the carried remainder are all
directly unit-tested against plain data (`ReservoirModelTest`).

**Free flow.** `isFreeFlowing` (`ReservoirModel.java:70-75`) is true while a deposit's remaining
fraction is at or above `PetroleumConfig.freeFlowThreshold` (default 0.6). Above that line the
deposit has enough of its own pressure to reach the surface unaided.

**The rate curve.** `flowRate` (`ReservoirModel.java:91-109`):

- At or above the threshold: peak rate (`PetroleumConfig.peakFlowRate`, default 30 mB/tick),
  **whether or not a pump is attached**. That is the early-game reward for a fresh field — a bare
  Wellhead is enough.
- Below the threshold, unpumped: zero. Pressure has fallen too far to lift the fluid on its own.
- Below the threshold, pumped: a **linear decline** from the peak (at the threshold) down to a
  residual floor (`PetroleumConfig.residualFlowRate`, default 0.025 mB/tick) at empty.

The residual floor is the point: an exhausted field keeps seeping rather than stopping dead, so a
base built around a pumpjack gets slower, never stranded.

**Extraction.** `extract` (`ReservoirModel.java:126-171`) is called with an elapsed-tick count
rather than being polled every tick — see `PetroleumTickHandler` below. Three things worth
knowing about it:

- **The catch-up clamp.** The rate is sampled once, at the deposit's *current* pressure, then
  multiplied by the elapsed ticks. Over a long interval that overstates production, because
  pressure would actually have declined as fluid came out. `MAX_CATCH_UP_TICKS = 200`
  (`ReservoirModel.java:37`) caps how much elapsed time a single call may be paid for, so a
  Wellhead that sat in an unloaded chunk for a week is not credited a week of peak flow the moment
  the chunk loads again — the well was not producing because nothing was there to receive it, and
  the cap says exactly that. It is a safety clamp, not a tuning knob, which is why it lives as a
  constant rather than a config entry.
- **The carried sub-millibucket remainder.** `Reservoir.pending` (`api/petroleum/Reservoir.java:43`)
  banks the fractional millibucket a draw rounds off, `0..1`, and is deliberately `transient` —
  losing under a millibucket across a reload is not worth a save field. Without it, a deposit
  seeping at a fraction of a millibucket per tick would floor to zero on every single poll and an
  "exhausted but still seeping" field would in fact be dead, which is exactly the promise the
  decline curve makes and breaks.
- **The pool bound is conditional.** An already-exhausted deposit (`remaining == 0`) is running
  purely on its residual seep, which comes from nowhere in the pool, so `extract` returns the
  computed `available` amount uncapped by `Reservoir.deplete`
  (`ReservoirModel.java:162-170`). A deposit that still holds something *is* bounded by
  `Reservoir.deplete`, so a small field cannot be over-drawn just because the sampled rate was
  generous for an interval that ran past when the field actually ran dry.

**City mode.** With `simulate` or `CityMode.petroleum()` true, `extract` still computes and
returns the same `available` amount — prospecting, drilling and the free-flow-then-pump
progression all look identical — but `Reservoir.deplete` is never called
(`ReservoirModel.java:157-160`). The deposit's stored `remaining` never changes, so its `fraction`
and therefore its rate never change either: a city-mode field delivers at whatever rate it rolled,
forever.

---

## Wellhead

`TileEntityWellhead`
(`common/blocks/petroleum/TileEntityWellhead.java`) is the valve stack a drilled bore leaves
behind. It owns a 1200 mB `FluidTank`
(`CAPACITY = 2*PetroleumTickHandler.PRODUCTION_INTERVAL*30`, `TileEntityWellhead.java:65`) —
two seconds of peak flow, sized so an unplumbed well does not quietly bank a tank's worth of oil.
Note the `30` is a literal, not a reference to `PetroleumConfig.peakFlowRate`; raising the peak
rate in config does not grow this buffer to match.

It is **not `ITickable`** — see `PetroleumTickHandler` below. `produce(elapsedTicks)`
(`TileEntityWellhead.java:160-192`) is called once per production interval: it resolves the
reservoir under the block via `ReservoirHandler.getReservoir`
(`TileEntityWellhead.java:134-138`), draws through `ReservoirModel.extract`, fills its tank, and
pushes whatever it can into any adjacent fluid handler (`pushOut`,
`TileEntityWellhead.java:194-220`), skipping other Wellheads specifically so a field of wells
cannot feed each other. Which faces currently accept fluid is cached in `outputFaces` and only
rescanned when a neighbour changes (`facesDirty`, set in `onNeighborBlockChange`,
`TileEntityWellhead.java:110-114`), not scanned every pass.

**The pump flag decays.** `setPumped(true)` (`TileEntityWellhead.java:149-152`) is set by an
attached Pumpjack, and `produce` clears it back to `false` at the end of every pass
(`TileEntityWellhead.java:191`). A Pumpjack must re-assert every interval, so destroying the pump
leaves the well behaving as though it were never pumped, with no separate "pump destroyed"
handling required.

Right-clicking a Wellhead (`interact`, `TileEntityWellhead.java:259-282`) reports remaining
percentage and whether it is free-flowing, pumped, or needs a pump. Its comparator output
(`getComparatorInputOverride`, `TileEntityWellhead.java:301-307`) reports tank fullness 0–15, so a
comparator can gate a pump on the well backing up.

---

## Drilling Derrick

`MultiblockDerrick` (`common/blocks/multiblocks/MultiblockDerrick.java`) defines the shape: a
3×3 footprint, 9 blocks tall (`PetroleumGeometry.DERRICK_SIZE`,
`common/blocks/petroleum/PetroleumGeometry.java:34-37`) — solid drill floor, corner legs, an open
girt band at the midpoint, a solid water table, and a lone crown block
(`MultiblockDerrick.isPart`, `MultiblockDerrick.java:85-101`). The shape is a pure predicate and
the manual `ItemStack[][][]` template is built lazily on first use rather than in a static
initialiser — every other `Multiblock*` class bakes its template statically, which makes the
class impossible to load outside a running game; `PetroleumGeometry` itself is split out of the
tile entities for the same reason, so the shape math stays directly testable
(`PetroleumGeometry.java:12-19`, `MultiblockDerrick.java:46-51`).

`TileEntityDerrick` (`common/blocks/petroleum/TileEntityDerrick.java`) drives drilling from its
master part only (`update`, `TileEntityDerrick.java:127-159`), staggered by position
(`getStagger`, `TileEntityDerrick.java:165-170`) and gated to run once every `DRILL_INTERVAL` (10
ticks, `TileEntityDerrick.java:78`). A full bore takes `DRILL_TIME = 1200` ticks — one minute
(`TileEntityDerrick.java:67`) — at `ENERGY_PER_TICK = 256` FE/t (`TileEntityDerrick.java:73`), for
307,200 FE total (`TileEntityDerrick.java:69-72`). `ENERGY_CAPACITY = 25600` FE
(`TileEntityDerrick.java:83`) buffers the draw; the doc comment above it calls this "roughly ten
seconds of drilling" (`TileEntityDerrick.java:80-82`), but at the stated 256 FE/t continuous rate
that buffer is 100 ticks — **five seconds**, not ten. Worth a second look; not fixed here.

The **site is checked once, at formation**, and cached (`resolveSite`,
`TileEntityDerrick.java:191-205`; called from `reportSiteTo`,
`TileEntityDerrick.java:315-328`), because a cell's roll is deterministic and cannot change under
a standing rig. Siting a rig over an empty cell tells the player immediately in chat, and the
block overlay (`getOverlayText`, `TileEntityDerrick.java:360-373`) repeats it for as long as the
rig stands, specifically because a missing deposit is otherwise invisible — no ore, no texture,
nothing to dig up — and "nothing is happening" has to be a statement the game makes rather than a
conclusion the player reaches on their own (class doc, `TileEntityDerrick.java:52-56`). A dry-hole
rig accepts power but never drills.

**Completion.** `completeWell()` (`TileEntityDerrick.java:240-262`) is written by hand rather than
through the shared multiblock `disassemble()` path, which is written for block-break and would
restore the tower as inert frame rather than pack it up — the rig is equipment, meant to travel to
the next site, not architecture. It replaces the bore block with a Wellhead and drops the whole
structure back as a stack of Oilfield Frame. Clearing `formed` on every part *before* clearing any
block is load-bearing (`TileEntityDerrick.java:244-252`): removing a block while a part still
believes it is formed would trigger `BlockIEMultiblock`'s own disassembly mid-teardown.

City mode changes only what a drilling pass costs, not the timeline: it still takes the full
minute and still needs power, but each pass spends a flat `CITY_SIP = 10` FE
(`TileEntityDerrick.java:87`) rather than metering the real 2560 FE/pass
(`drawPower`, `TileEntityDerrick.java:210-229`) — the same "keep the gesture, drop the accounting"
trade city mode makes everywhere else.

---

## Pumpjack

`MultiblockPumpjack` (`common/blocks/multiblocks/MultiblockPumpjack.java`) is a 3×6×5 structure
described as a `String[][]` shape table (`SHAPE`, `MultiblockPumpjack.java:63-74`) rather than
coordinate predicates, because the manual page and the world check have to agree exactly and a
half-matching structure fails silently. The well bay (`'W'`) is drawn in the manual for clarity but
deliberately excluded from the materials list and from the blocks the formation step overwrites
(`MultiblockPumpjack.java:114-119,254-257`) — a Wellhead is what drilling leaves behind, not a
part the machine is built from, and formation must not eat the very well it exists to drive.

`TileEntityPumpjack` (`common/blocks/petroleum/TileEntityPumpjack.java`) carries **no fluid of its
own**; every fluid-tank accessor returns empty (`TileEntityPumpjack.java:349-367`). Once per
`PetroleumTickHandler.PRODUCTION_INTERVAL`, staggered by position
(`update`, `TileEntityPumpjack.java:110-122`), it resolves the Wellhead it stands over
(`getWellhead`, `TileEntityPumpjack.java:196-207` — the bay in front, or one block down for a well
sunk into a dug cellar, cached until invalid) and, if the deposit is worth pumping, asserts
`setPumped(true)` on it for that pass only (`runPass`, `TileEntityPumpjack.java:127-143`).

**`shouldPump`** (`TileEntityPumpjack.java:160-165`) is the machine's only real decision: a
deposit still at or above its free-flow threshold already produces at peak rate whether or not a
pump is attached, so pumping it would spend `ENERGY_PER_TICK = 512` IF/t
(`TileEntityPumpjack.java:57-60`) for nothing — the machine idles instead. City mode is the
explicit exception: reservoirs there never deplete, so "has this field fallen past free flow"
can never become true, and gating on it would leave every pumpjack in a city world permanently
decorative; since city mode's cost is a flat `CITY_SIP = 1` IF rather than the real draw, running
unconditionally there costs nothing.

The animation (`getStrokePhase`, `TileEntityPumpjack.java:254-261`) is derived purely from world
time and the position-based stagger, and is **never synced** — the `active` boolean is the only
networked state, because a nod is not worth a packet for a block expected to appear twenty times
in one view.

---

## Distillation Tower

Both halves of the registration are present: `registerTile(TileEntityDistillationTower.class)`
(`IEContent.java:807`) and `MultiblockHandler.registerMultiblock(MultiblockDistillationTower.instance)`
(`IEContent.java:1106`), immediately alongside the Derrick's and Pumpjack's own calls.

`MultiblockDistillationTower` (`common/blocks/multiblocks/MultiblockDistillationTower.java`)
defines the shape: a two-by-two steel shell that runs the full fourteen-block height — the vessel —
ringed at its foot by a scaffolding deck where crude goes in and where power and redstone connect,
with four pairs of Oilfield Frame nozzles sticking out of the shell at each of seven draw heights,
one pair per face (class doc, `MultiblockDistillationTower.java:38-57`). The footprint is
`PetroleumGeometry.TOWER_SIZE`, four by four by fourteen (`PetroleumGeometry.java:57-60`). As with
the Derrick, the shape is a pure predicate (`isPart`, `MultiblockDistillationTower.java:155-164`)
and the manual `ItemStack[][][]` template is built lazily rather than in a static initialiser, so
the shape math stays loadable — and testable — outside a running game (`getStructureManual`,
`MultiblockDistillationTower.java:195-209`; pinned by `DistillationTowerTest`).

**The height-to-cut mapping is the whole machine.** `drawHeight(cut)`
(`MultiblockDistillationTower.java:106-115`) spreads the column's seven draw ports as evenly as
whole blocks allow between `BOTTOM_PORT` (one block above the feed deck) and `TOP_PORT` (the top of
the shell), lightest cut at the top and heaviest at the bottom, exactly as a real fractionating
column runs. The visible nozzles are placed from the same function the tile entity uses to decide
which tank a face can be drained from (`cutAtHeight`, `MultiblockDistillationTower.java:120-126`),
so a finished tower is legible from across the map: the pipe leaving a given height can only be
carrying one thing, and getting the plumbing right is a spatial puzzle rather than a GUI to read.
For the shipped seven-cut crude recipe (see [Distillation recipes](#distillation-recipes) below),
the mapping is:

| Cut | Fluid (shipped recipe) | Height | Port |
|---|---|---|---|
| 0 | Natural gas | 13 | `TOP_PORT` |
| 1 | Naphtha | 11 | |
| 2 | Gasoline | 9 | |
| 3 | Diesel | 7 | |
| 4 | Heavy fuel oil | 5 | |
| 5 | Lubricant | 3 | |
| 6 | Bitumen | 1 | `BOTTOM_PORT` |

The column is fourteen layers because seven ports two apart need a twelve-layer span and nothing
less. At twelve the rounding produced 11, 9, 8, 6, 4, 3, 1 — two pairs of ports on adjacent
layers. Each block only ever exposes its own cut, so nothing mixed by standing still; but a pipe
climbing the tower to reach the upper port of a pair connected to the lower one on the way past,
and two cuts ended up in one line. `portsAreNeverAdjacent` in `DistillationTowerTest` now fails if
that spacing is ever lost again.

`DistillationTowerTest` pins this table by value (`theMapping`,
`DistillationTowerTest.java:66-76`), specifically because every tower already built in a save is
plumbed to these heights — changing the formula is allowed, changing it by accident is not.

`TileEntityDistillationTower` (`common/blocks/petroleum/TileEntityDistillationTower.java`) keeps
one crude tank at the foot of the column (`TANK_FEED`, `FEED_CAPACITY = 24000` mB,
`TileEntityDistillationTower.java:59,64`) and one tank per cut (`TANK_FIRST_CUT`,
`CUT_CAPACITY = 8000` mB each, `TileEntityDistillationTower.java:63,70`). Every tank knows what
belongs in it: the feed tank accepts anything at least one incomplete recipe could use
(`buildTanks`, `TileEntityDistillationTower.java:138-150`, via
`DistillationRecipe.findIncompleteRecipes`), and each cut tank is a `CutTank` that refuses
everything except its own cut (`isCutOf`, `TileEntityDistillationTower.java:176-187`). That is what
lets the shared multiblock output plumbing route seven different fluids without a line of
special-case code, and it is also what makes "is there room for this batch" an exact check rather
than an approximate one.

**A batch only starts when every cut it will produce has somewhere to go.** `hasRoomForEveryCut`
(`TileEntityDistillationTower.java:241-254`) simulates filling every output tank before the tower
commits to a run, and a recipe declaring more cuts than the column has ports for is refused
outright rather than quietly losing the extra ones. That is deliberate: a column that produced its
light ends and then found nowhere to put the residue would have to either destroy fluid or wedge
itself, and both are worse than simply not starting. Once a batch is running, the same guarantee
holds every tick — the process queue's own throttle refuses to advance a batch whose outputs have
since filled up, so a tower that runs out of room stalls with its crude intact rather than jamming
with a partial run made (`update`, `TileEntityDistillationTower.java:201-232`).

**Process heat.** A tower can run on its own energy buffer alone (`ENERGY_CAPACITY = 32000`,
`TileEntityDistillationTower.java:71`), but distillation is mostly heat and only incidentally work,
so an outside heat source — the Industrial Burner, below — is worth having. `supplyProcessHeat`
(`TileEntityDistillationTower.java:304-313`) is the one-way, stateless contract a heater uses: it
says "there is fire under this thing for the next `ticks` ticks" and the column banks that against
`MAX_HEAT_TICKS = 200` (`TileEntityDistillationTower.java:82`), the same decay pattern as the
Pumpjack's `setPumped` flag. While heat is in hand, `rebateOperatingCost`
(`TileEntityDistillationTower.java:265-282`) hands back `HEATED_ENERGY_REBATE` — 60% — of whatever
the process queue just spent that tick (`TileEntityDistillationTower.java:77`), because there is no
hook to lower the queue's own per-tick cost, only to refund part of it afterwards. In city mode the
same method instead refunds down to a flat `CITY_SIP` of 1 (`TileEntityDistillationTower.java:86`)
— presence, not consumption, the same trade the Pumpjack and the virtual grid make; an unpowered
tower still stops, because the queue will not tick without the full cost in the buffer, it just
does not stay spent once it has.

**Readout.** The tower has no GUI, in keeping with the rest of the petroleum equipment: right-
clicking or hammering it shows an overlay naming the layer — "Crude feed" at the deck, the cut's
localized fluid name at a nozzle height, "Draw port" for a nozzle whose recipe has no name for that
cut — plus whether the tower is distilling or idle (`getOverlayText`,
`TileEntityDistillationTower.java:339-353`). Its comparator output reports the feed tank's
fullness, floored at 1 rather than 0 so a tower holding any crude at all never reads as empty
(`getComparatorInputOverride`, `TileEntityDistillationTower.java:380-392`).

---

## Industrial Burner

Registration is likewise complete: `registerTile(TileEntityIndustrialBurner.class)`
(`IEContent.java:808`) and `MultiblockHandler.registerMultiblock(MultiblockIndustrialBurner.instance)`
(`IEContent.java:1107`).

`MultiblockIndustrialBurner` (`common/blocks/multiblocks/MultiblockIndustrialBurner.java`) is a
three-by-three-by-three firebox: a refractory hearth and lining of Blast Brick carrying the fire, a
steel sheetmetal crown over the top, an Oilfield Frame burner head let into the front face of the
combustion chamber where the fuel line lands, and a second frame in the middle of the crown for the
flue (class doc and `SHAPE` table, `MultiblockIndustrialBurner.java:38-55,86-94`). The crown is the
working face — anything the burner heats directly sits on top of it — which is why it is the one
course that is not brick. The burner head is the machine's only asymmetry, so formation trusts the
shape over the click: it tries all four facings in turn and takes whichever one the player actually
built (`createStructure`, `orderedFacings`, `MultiblockIndustrialBurner.java:246-274`), rather than
refusing a correctly-built burner just because it was approached from the "wrong" side.

`TileEntityIndustrialBurner` (`common/blocks/petroleum/TileEntityIndustrialBurner.java`) makes no
Flux at all, and that is the point: Heavy Fuel Oil is deliberately not a Diesel Generator fuel — it
is the cheap, dirty residue at the bottom of the distillation column — and a firebox is what makes
owning a barrel of it better than owning nothing (class doc,
`TileEntityIndustrialBurner.java:44-50`). Fuels are keyed by registry name rather than by `Fluid`
reference, the same reasoning as `DieselHandler` and `ReservoirType` (`FUELS`,
`TileEntityIndustrialBurner.java:92-116`), and ranked by volumetric energy density — heat per
millibucket, not per fuel type — so Heavy Fuel Oil (`HEAT_PER_BUCKET_HEAVY_FUEL_OIL = 20000`) beats
Diesel (`18000`), Propane (`12500`) and Natural Gas (`11000`) by a clear margin
(`TileEntityIndustrialBurner.java:87-90`). That ordering has to hold for the machine to mean
anything: every other fuel on the list is worth more somewhere else, and only Heavy Fuel Oil has no
other consumer at all.

**The heat contract a neighbouring machine reads** is four methods, and deliberately mentions
neither fluids nor multiblocks, so a consumer that finds a `TileEntityIndustrialBurner` against one
of its own faces needs no knowledge of where the master is
(`TileEntityIndustrialBurner.java:52-57`):

- `isBurning()` (`TileEntityIndustrialBurner.java:244-248`) — whether the fire is lit and has heat
  to give.
- `getHeatRate()` (`TileEntityIndustrialBurner.java:258-262`) — heat units per tick the current
  fuel sustains; what a consumer should size itself against to run continuously.
- `getStoredHeat()` (`TileEntityIndustrialBurner.java:267-271`) — heat units available to take
  right now, which can be spent faster than `getHeatRate()` for a while before the store runs down.
- `drawHeat(max, simulate)` (`TileEntityIndustrialBurner.java:280-289`) — takes heat out of the
  firebox, never more than `max` and never more than is stored.

Heat is measured in heat units (HU): one HU is one tick of fire at one unit of intensity, and the
scale only matters relative to `getHeatRate()`. `HEAT_CAPACITY` — two passes of the best fuel,
3,200 HU (`TileEntityIndustrialBurner.java:181-189`) — is what makes the machine demand-driven
without any demand tracking: a full store has nothing to gain from stoking, so a burner nobody is
drawing from simply stops burning fuel, and nothing has to tell it whether a consumer is attached
at all (`runPass`, `TileEntityIndustrialBurner.java:327-366`).

Once per `BURN_INTERVAL` (20 ticks, `TileEntityIndustrialBurner.java:170`), staggered by position
like the Wellhead and the Derrick (`getStagger`, `TileEntityIndustrialBurner.java:450-455`), a
stoking pass works out a whole interval's fuel and heat in one go — a bucket lasts 250 ticks
whatever fuel it is, only the heat it carries differs (`FIRING_RATE = 4`, `CHARGE = 80`,
`TileEntityIndustrialBurner.java:165,174`). The burner also feeds whatever is standing on its crown
every tick a target exists and heat is available — vanilla furnaces are the case this is for, fed
through `ExternalHeaterHandler`'s adapter at a fixed one HU per unit of Flux requested (`heatCrown`,
`TileEntityIndustrialBurner.java:386-416`; targets re-scanned on the stoking interval by
`refreshHeatTargets`, `TileEntityIndustrialBurner.java:422-438`) — so the burner has a job the
moment it is built, before anything that speaks its own heat contract exists to plug into it.

City mode keeps the fire a fact rather than a simulation: fuel is still required and the tank still
empties, so a burner nobody refuels still goes dark, but nothing is metered against demand and the
store is simply held full (`runPass`, `TileEntityIndustrialBurner.java:337-352`) — the same "keep
the gesture, drop the accounting" trade as everywhere else in this feature.

**Readout.** No GUI here either: the overlay reports "Firing" and the current HU/t, or "Cold"
(`getOverlayText`, `TileEntityIndustrialBurner.java:462-472`), and the comparator output reports
fuel-tank fullness 0–15 (`getComparatorInputOverride`, `TileEntityIndustrialBurner.java:480-488`).
Fuel goes in at the hearth course and the burner head; nothing ever drains back out, so the tank
cannot double as a free fluid store that happens to also be a machine (`isFuelPort`,
`canDrainTankFrom`, `TileEntityIndustrialBurner.java:504-513,531-537`).

---


## Flare Stack

A single block, and the only thing in the feature that is a deliberate **loss**. It accepts sour
gas, natural gas or propane and destroys them, yielding nothing whatsoever.

That is the point. An oilfield produces gas whether or not there is a use for it, and a Wellhead
whose gas tank has backed up stops producing oil as well. Before a Gas Scrubber exists there is
nothing else to do with the stream, so the flare is what keeps a well running. It is also the
strongest argument the feature makes for building a Scrubber: the player can watch, in
millibuckets, exactly how much is going up the stack (`lifetimeBurned` in the block overlay), and
that number is the Scrubber's business case.

**It is not `ITickable`.** Burning happens on delivery, which is the only moment anything can
change, and the flame is extinguished by a scheduled block update rather than by a countdown that
has to be checked. An idle flare costs the server literally nothing. `BURN_RATE` (200 mB per
delivery) is sized so a Wellhead's gas stream is consumed as fast as it arrives and the well never
backs up behind the flare; `FLAME_TICKS` (40) outlasts a Wellhead's production interval, so a
steady feed reads as a steady flame rather than a stutter. It emits light 14 while lit and a full
comparator signal, so the waste stream can drive a lamp or an alarm.

Its intake is fill-only at the capability level, not merely by convention: the face a pipe sees is
a wrapper whose `drain` returns null in both overloads. A flare is where gas goes to stop existing,
and a drainable one would just be a tank that happens to glow.

---

## Gas Scrubber

A 3×3×6 pair of vertical vessels that turns the field's one worthless product into two useful
ones: 100 mB of sour gas yields 80 mB of natural gas (`SWEET_PERCENT = 80`), and every 2000 mB of
sour gas processed also drops a sulfur dust (`SOUR_PER_SULFUR`).

**Its operating cost is heat, not flux.** Sweetening gas is a reboiler duty in reality and the
machine is modelled that way: it draws heat units from an Industrial Burner standing against its
skid and does nothing at all without one (`HEAT_PER_BUCKET = 2000`). This is what stops the
machine being free money, and it is also the nicest loop in the feature — a burner will run on the
scrubber's own natural gas, so the plant bootstraps itself at a cost of roughly a quarter of what
it makes, while feeding the burner heavy fuel oil instead (the cut with no other consumer) keeps
the whole of the gas. Two waste streams cancelling each other out.

Partial heat is honoured rather than refused: a pass scrubs however much gas the heat on offer
pays for. An underfed scrubber runs visibly slowly, which is legible, instead of stalling on a
threshold nothing tells the player about. It reports four states on the overlay — idle, scrubbing,
no heat, backed up — so each way of being stopped is distinguishable without guesswork.

It works on a 20-tick interval (`SCRUB_INTERVAL`), scrubbing `SCRUB_RATE` mB per tick of that
interval in one batch, and its comparator output — gated to a single designated block, like every
other multiblock — reports the sour feed level, because a feed backing up means the machine is not
keeping pace with the field.

---

## Lubrication Manifold

A single block that bolts onto a working machine and rebates part of its running cost while it has
lubricant in it: a flat 8 FE/t, topped up in 20-tick batches, for 2 mB of lubricant per batch — a
bucket lasts about eight minutes of continuous running.

This exists because lubricant needs a job. Every other cut off the column burns; lubricant is the
one that does not, and without a consumer the heaviest useful fraction of a barrel would be a
fluid with nothing to do. The benefit is expressed as an energy rebate rather than a speed
multiplier for an implementation reason as much as a design one: a speed multiplier would mean
reaching into the shared multiblock process queue every machine in the mod runs on, whereas a
rebate is paid by topping the host's own buffer back up through the accessor it already exposes.

The rebate is **flat and absolute**, and that is load-bearing. It was briefly a fraction of the
host's buffer capacity — a quantity unrelated to what the host actually spends — which worked out
at a 59% discount on the Distillation Tower and, stacked with the Industrial Burner's heat rebate,
made the tower net-positive: it ran indefinitely with its power supply disconnected. A small
absolute offset cannot do that to anything, and it degrades honestly on a big machine instead of
scaling into absurdity.

It resolves its host on neighbour change rather than searching every interval, greases nothing
when the host has an empty process queue (burning lubricant on an idle machine is exactly the kind
of quiet drain that is miserable to diagnose), and syncs whether it is attached — host resolution
is server-side while the overlay draws client-side.

---

## Propane vessels

Three bodies, one behaviour. All three take propane and nothing else, all three keep their contents
when broken, and all three fill from a bucket, a can or a pipe.

| Block | Capacity | What it is |
|---|---|---|
| **Propane Cylinder** | 4,000 mB | The bottle off the back of a barbecue |
| **Upright Propane Tank** | 12,000 mB | The tall bottle standing beside a building |
| **Torpedo Propane Tank** | 24,000 mB | The horizontal drum on saddles at the end of a garden |

They exist because a forecourt, a back garden and a job site do not look alike and until now all
three had the same bottle on them. The sizes follow the shapes rather than the other way round —
a player who sees a bigger tank expects it to hold more, and a ladder that contradicted that would
be worse than no ladder.

The torpedo deliberately stops short of the buried **Domestic Tank**'s 32,000. That tier's whole
proposition is being invisible and cheap, and an above-ground tank beating it on capacity as well
as on convenience would leave it with nothing to be.

They are one class: `TileEntityPropaneCylinder` with a capacity in its constructor, and a subclass
per body carrying only that number. A constructor argument rather than a value in NBT, for the
reason the buried tanks give — Forge builds a tile entity through a no-argument constructor on
chunk load, so a vessel that had to recall its own size would have to guess when the tag was
missing, and guessing small silently voids whatever was in it.

**The torpedo does not rotate.** `petroleum_device` declares no facing property at all — the two
directional blocks on it keep their facing on the tile and are drawn as plain cubes — so making
this one turn means adding a facing to every meta and a rotation submap applying to all of them.
That is a change worth making on its own rather than as a rider on a new tank.

### The cylinder

4000 mB, portable, and it keeps its contents when broken so that swapping an empty for a full one
is the gesture rather than plumbing anything. The same trick the wooden barrel uses.

It is the smallest rung of the tank ladder and the only portable one. Everything else in this
feature is infrastructure — wells, towers, pipe runs — and all of it is useless to a player who
just wants to run one thing in one place. A bottle filled at a tank and set down next to a burner
is a complete loop with no plumbing at all. It takes propane and nothing else, deliberately:
letting it hold anything would quietly make it the cheapest portable tank in the mod for every
fluid at once.

## Fuel Oil Boiler

`common/blocks/petroleum/TileEntityFuelOilBoiler.java`, shape in
`common/blocks/multiblocks/MultiblockFuelOilBoiler.java`.

A 5×5×7 furnace that makes **steam, not Flux**. This is what finally gives heavy fuel oil a job at
scale: crude and diesel burn in it too, and both are worse trades, which is the point — the boiler
is where the cut nothing else wants becomes the base load of a power station.

Water and fuel go in along the base course; steam leaves from the crown. Splitting the boiler from
the turbine hall rather than making one enormous machine is the whole reason `ie_steam` is a
registered fluid: a boiler house and a hall are two buildings you plumb together across a yard.

Fuel values are keyed by fluid registry name, so the table is readable — and testable — without a
fluid registry, the same arrangement the Industrial Burner and the Gas Scrubber use.

---

## Heat Recovery Steam Generator

`common/blocks/petroleum/TileEntityHRSG.java`, shape in
`common/blocks/multiblocks/MultiblockHRSG.java`.

A 3×5×3 bank of finned tubes that **burns nothing**. Butted against a Gas Turbine's exhaust end it
makes steam out of heat that was going up the stack anyway.

Two details carry it:

- **The exhaust is claimed.** `TileEntityGasTurbine.claimExhaust(BlockPos, int leaseTicks)` grants
  one HRSG a lease on a turbine's exhaust; a second HRSG on the same machine gets nothing. Without
  that, stacking HRSGs would multiply a fixed quantity of waste heat.
- **The face has to match.** The HRSG is three wide and three tall because that is exactly the
  turbine's exhaust face. If it will not sit flush, it is not in line — which is a legible failure
  rather than a silent one.

---

## Steam Turbine Hall

`common/blocks/petroleum/TileEntitySteamTurbineHall.java`, shape in
`common/blocks/multiblocks/MultiblockSteamTurbineHall.java`.

5×9×5, the largest structure in the expansion, and the most efficient thing in the mod at turning
fuel into Flux. Steam in at the condenser end, Flux out at the switchyard.

It is deliberately slow to start and punished for cycling. Fed steadily it beats everything;
switched on and off against a wobbling demand it spends most of its life spinning up. That is the
axis the whole power-generation tier is built on — **response against efficiency** — and the hall
is the efficient extreme.

---

## Reciprocating Engine Bank

`common/blocks/petroleum/TileEntityEngineBank.java`, shape in
`common/blocks/multiblocks/MultiblockEngineBank.java`.

4×5×5 of cylinder banks burning diesel, biodiesel or natural gas. Instant response, mediocre
efficiency, and the only plant that **scales by building another one**: place a second bank
alongside the first and they run as a set, with no single bank changing.

The word in the plan was "extensible"; the honest word is **modular**. Nothing grows — you simply
have two.

---

## The combined cycle

Gas Turbine → HRSG → Steam Turbine Hall. The turbine's waste heat becomes steam, the steam becomes
more Flux, and a millibucket of gas does considerably more work than it does alone.

It is also the least forgiving thing in the expansion to build: three structures in a line, one
fuel line, one steam line, and any of the three idle makes the other two worse. That is the reward
structure the tier was designed around, and the reason the HRSG consumes no fuel of its own.

> **Balance, still open.** Heavy fuel oil in a boiler feeding a hall currently beats the combined
> cycle on FE per millibucket. That may be correct — HFO has no other use and gas has several — or
> it may make the combined cycle decorative. **It is not a spreadsheet question.** Judge it after
> building both.

---

## Buried tanks

`common/blocks/petroleum/TileEntityBuriedTank.java` and its three subclasses, geometry in
`common/blocks/petroleum/BuriedTankGeometry.java`, shape in
`common/blocks/multiblocks/MultiblockBuriedTank.java`.

| Tier | Box | Blocks | Capacity | Metal |
|---|---|---|---|---|
| **Domestic Tank** | 2×2×2 | 8 | 32,000 mB | Iron sheetmetal |
| **Commercial Tank** | 5×5×4 | 82 | 512,000 mB | Steel sheetmetal |
| **Bulk Depot** | 9×9×6 | 290 | 4,000,000 mB | Steel sheetmetal |

Three instances of **one parameterised multiblock**, not three classes: everything that differs
between the tiers is data, so there is one copy of the shape arithmetic to get right rather than
three to keep in step.

Four decisions worth knowing:

- **The middle is hollow.** A solid depot would be 486 blocks and 486 tile entities; a shell is
  290, and it is also what a tank actually is. The hollow interior doubles as the guard that keeps
  the tiers apart — a smaller tank's footprint laid over a bigger one's roof always lands on a void
  cell, so hammering a depot can never form the wrong structure.
- **The fill cap is the master**, not the origin corner. Nothing in `TileEntityMultiblockPart`
  requires the two to be the same block, and putting the state on the cap keeps the readout, the
  comparator level and the client sync local to the one block a player can see. On the origin
  corner they would be a cross-chunk lookup from a buried block.
- **Burial is enforced and explained.** The structure forms only when every roof block but the cap
  is covered, and when the shape is right and only the cover is missing the player is told so. A
  silent refusal there would be unusually cruel, because the tank looks completely correct.
- **The gauge syncs in 64 bands**, not on every change, and the arithmetic is in longs — four
  million millibuckets times sixty-four overflows an int, and the negative reads as a level falling
  while the tank fills.

Capacity per block rises with the tier, which is the reason to dig the bigger hole.

---

## The forecourt

`common/blocks/petroleum/TileEntityGasPump.java`, `TileEntityForecourtSign.java`,
`TileEntityPortableGenerator.java`, and the nozzle in `common/items/ItemPetroleum.java`.

Everything else in this feature moves fuel between machines. The **Gas Station Pump** hands it to a
person.

- **It is two blocks, assembled with a hammer.** Stack two pumps and strike either with an
  Engineer's Hammer: the lower block keeps the fuel, the tank, the GUI and the plumbing, the upper
  one becomes a head that draws nothing of its own, and a single bowser two blocks tall is drawn
  from the lower block. Breaking either block takes it apart and leaves two loose pumps.
  Hammering an *assembled* pump turns it instead — the model faces the road, and a pump built
  facing the wrong way is the first thing anybody wants to fix (`TileEntityGasPump.interact`,
  `canHammerRotate`).
- **The model is this fork's only OBJ.** `models/block/petroleum/gas_pump.obj.ie`, generated by
  `docs/tools/make_gaspump_assets.py` along with the five textures it wears. It carries the loose
  block's crate as a group of its own (`unformed`), because a blockstate variant names exactly one
  model and this block has to look like two things. Which groups draw, and how far the model is
  turned, are answered per block by `IOBJModelCallback` on the tile rather than by a facing
  property on `petroleum_device` — that block deliberately has none (see the Torpedo Tank, above),
  and adding one would give all thirteen of its metas four states apiece.
- **The price is on the pump.** `TileRenderGasPump` writes it, in the mechanical-digit font, on
  the display panel of the model, facing the way the pump faces. It used to be overlay text beside
  the crosshair, which a playtester reported as a price "floating in the air"; the block overlay
  now reports only the tank level once the pump is assembled.
- A held container is filled directly — a bucket, a jerrycan, a Mining Drill — because making a
  player fetch the nozzle for the common case would be ceremony rather than immersion.
- The **Fuel Nozzle** is for the rest: take it off a pump and it remembers which pump, then fill
  anything within eight blocks that accepts fluid, **including entities**, so a vehicle would need
  no change here at all. The rendered hose is not built; the range check does the job its length
  would have done.
- The pump **does not tick**. It spends nearly all its life doing nothing and a forecourt is
  several of them, so dispensing happens on the interaction that asks for it.
- The **Forecourt Price Sign** has no price of its own. It finds the nearest pump and shows that
  one's — the only arrangement that cannot go stale.

### `FuelDispensedEvent`

`api/petroleum/FuelDispensedEvent.java`. Cancellable, fired on the server **before anything moves**,
carrying the player, the pump, the fluid name, the amount and the forecourt's price. The amount is
mutable, so a listener may shorten a sale rather than refusing it.

**This fork implements no currency, and that event is the reason it does not have to.** A city
server with an economy plugin subscribes and charges whatever its own money system charges. Half an
economy inside a tech mod would be worse than none.

### Portable Generator

Gasoline runs handheld tools and is explicitly refused by the Diesel Generator, which is correct —
a compression engine cannot burn it — and until now that left the cut with no consumer that scales.
The Portable Generator burns the **spark-ignition** fuels (gasoline, ethanol) and refuses diesel,
at 256 Flux/t from a four-bucket tank that lasts about five minutes. It is deliberately small: a thing you carry to a build site
and walk back to the pump to refill.

---

## Immersive Vehicles (MTS)

`common/util/compat/mts/MTSHelper.java`, table in `MTSFuelTable.java`, registered as the `mts`
compat module in `common/util/compat/IECompatModule.java`.

This fork builds no vehicle of its own, but the pack it is built for runs **Immersive Vehicles**
(internal modid `mts`, once Minecraft Transport Simulator) alongside content packs — the official
one, UNU, DKZ. Out of the box **none of this fork's fuels worked in any of them**: a gas pump would
reject a tank of `ie_gasoline` on contact.

### Why they were rejected

MTS decides what an engine will burn from `ConfigSystem.settings.fuel.fuels`, a
`Map<fuelType, Map<fluidRegistryName, potency>>`. `PackParser.parsePacks` seeds it during MTS's own
pre-init from a hardcoded table, keyed on the fuel types the loaded packs' engines actually ask for,
and **a fuel type already in `mtsconfig.json` is never revisited**. Its defaults are:

| MTS fuel type | Fluids it accepts by default |
|---|---|
| `gasoline` | `lava` 1.0, `gasoline` 1.0, `ethanol` 0.85 |
| `diesel` | `lava` 1.0, `diesel` 1.0, `biodiesel` 0.8, `creosote` 0.7, `oil` 0.5 |
| `avgas` | `lava` 1.0, `gasoline` 1.0 |
| `redstone` / `water` / `nothing` | not petroleum |

All bare names. This fork prefixes its distillation cuts `ie_` on purpose — see
[Fluids](#fluids) — so `ie_gasoline` and `ie_diesel` are in none of those lists.
`AEntityVehicleE_Powered.checkFuelTankCompatibility` looks the tank's fluid up in that map, misses,
and returns `INVALID`. That is the rejection, and it is a naming mismatch rather than anything to do
with how good the fuel is.

### What the integration does

At **post-init** — late enough that MTS's pre-init has certainly run and the map is populated, early
enough that no vehicle has ticked — `MTSHelper` reaches that map by reflection and folds this fork's
fluids into every fuel type it recognises. Nothing is written to `mtsconfig.json`: MTS saves its
config during its own init, *before* this runs, so the injection is rebuilt from scratch each launch
and leaves no mark on a file the player may have hand-edited. It is applied identically on both
logical sides, which matters — MTS throws outright if a client and a server disagree about a fuel.

Two rules make it safe to run against somebody else's config: **it never overwrites** a fluid the
file already lists (that potency may be a pack author's deliberate edit), and **it never adds a fuel
type** MTS does not already have (a type no engine asked for is noise in a config file).

### The mapping

Potency divides fuel consumption in MTS, so 1.0 is a full-value fuel and 0.5 burns twice as fast.

| MTS fuel type | This fork's fluid | Registry name | Potency | Why |
|---|---|---|---|---|
| `gasoline` | Gasoline | `ie_gasoline` | 1.00 | The reference spark-ignition fuel. |
| `gasoline` | Ethanol | `ethanol` | 0.85 | MTS's own number for ethanol, so a tuned pack sees no change. |
| `gasoline` | Naphtha | `ie_naphtha` | 0.60 | A blendstock, not a finished fuel — it runs and knocks. Matches the cut's standing everywhere else in this feature. |
| `gasoline` | Propane | `propane` | 0.70 | An LPG conversion; about seven tenths of gasoline by the litre. |
| `avgas` | Gasoline | `ie_gasoline` | 1.00 | MTS's own avgas list is gasoline and nothing else. No substitutes are offered — an aircraft is the one place this table refuses to be generous. |
| `diesel` | Diesel | `ie_diesel` | 1.00 | The reference compression-ignition fuel. |
| `diesel` | Biodiesel | `biodiesel` | 0.80 | MTS's own number. |
| `diesel` | Creosote | `creosote` | 0.70 | MTS's own number. |
| `diesel` | Plant Oil | `plantoil` | 0.50 | What MTS gives its generic `oil`, and the same bargain: straight vegetable oil will run a diesel, poorly. |
| jet / kerosene types | Diesel | `ie_diesel` | 0.90 | This fork has no kerosene — the design folded its yield into diesel — so diesel is what a turbine gets, discounted for the mismatch. |
| jet / kerosene types | Naphtha | `ie_naphtha` | 0.70 | A naphtha-kerosene blend is a real wide-cut jet fuel. |

Registry names are read off the live `Fluid` objects, not hardcoded, because `IEContent.setupFluid`
yields to whoever registered a name first: on a pack where another mod owns `ethanol` or `creosote`,
`IEContent.fluidEthanol` **is** that mod's fluid, and its name is the one MTS will see in the tank.

### Fuel types this code has never heard of

The four names above are MTS's own. Content packs invent their own freely, and a fuel type MTS has
no default for gets nothing but `lava` — so `MTSFuelTable.classify` reads the name instead of
looking it up: `avgas`/`aviation`/`100LL` before anything else (`avgas` contains "gas", and getting
that order wrong hands a light aircraft a tank of ethanol), then `jet`/`kerosene`/`jp8`/`turbine`,
then `diesel`, then `gasoline`/`petrol`/`octane`. Never a bare `gas` substring, so `natural_gas` is
not mistaken for petrol. A hard refusal list — `redstone`, `water`, `nothing`, `electric`, `steam`,
`lava`, `coal`, `furnace`, `brewer`/`brewing`, `milk`, `creative` — is checked first, so a crafter
or an electric drive never starts taking diesel because a word happened to match.

### What is deliberately not offered

Crude oil, heavy fuel oil, lubricant, bitumen, asphalt, sour gas, natural gas, steam and concrete
are all left out. Sour gas and steam are not fuels at all; crude is deliberately awful even in the
machines that will take it; and heavy fuel oil, lubricant, bitumen and natural gas each exist to
give one fork machine its reason to be built — the Industrial Burner, the Lubrication Manifold, the
asphalt line, the Gas Turbine. Handing them to a car would dissolve the choice each of those blocks
poses.

### Verification

**The mapping and the merge are unit-tested**
(`src/test/java/.../common/util/compat/mts/MTSFuelTableTest.java`); the reflection is not, because
MTS is not in this dev environment. The module says exactly what it did at startup, so a playtester
can confirm it from the log without a debugger — search `latest.log` for
`Immersive Vehicles compat`:

```
[INFO] Immersive Vehicles compat: fuel type 'gasoline' now also accepts {ie_gasoline=1.0, ie_naphtha=0.6, propane=0.7}
[INFO] Immersive Vehicles compat: extended 3 of its 4 fuel types ([gasoline, diesel, avgas, redstone]). Fuel pumps and jerrycans should now take these fluids.
```

Every way it can fail logs a `WARN` naming the step that failed. If nothing at all is logged, the
compat module never ran: check that `mts` is `true` under `compat` in IE's config.

---

## Fluid Loading Gantry

`common/blocks/petroleum/TileEntityLoadingGantry.java`, shape in
`common/blocks/multiblocks/MultiblockLoadingGantry.java`.

Two legs, a beam and a one-block bay. It takes empties out of the inventory on one side, fills them,
and puts them into the inventory on the other.

Both chests stand **in front of the gantry, one against each leg**. They were briefly on opposite
faces, which is buildable and undiscoverable -- and this machine has no interface to explain itself
with.

**No GUI, deliberately**: a machine whose whole job is to sit between two chests should be
configured by where the chests are, and that also means there is no internal inventory to spill
when the structure comes apart. The insert is simulated before the empty is taken — the other way
round destroys a jerrycan the moment the output chest fills up, and a machine that eats containers
is a far worse failure than one that stops.

---

## Cracking Unit

`common/blocks/petroleum/TileEntityCrackingUnit.java`, shape in
`common/blocks/multiblocks/MultiblockCrackingUnit.java`.

5×3×6: two reactor columns straddling a coke drum on a scaffolding deck. Heavy fuel oil or naphtha
in at the deck, gasoline out of the front of the head, diesel out of the back, petcoke out of the
bottom of the drum.

**The severity dial is the burner.** The plan called for a temperature slider in a GUI; there is
none, and it is better for it. The cracker always asks for the heat of a maximum-severity run and
cracks at whatever severity the heat it actually receives pays for:

| Firebox | Severity | Yield |
|---|---|---|
| Cool (natural gas) | low | favours diesel |
| Hot (heavy fuel oil) | high | favours gasoline |

That makes the Industrial Burner's fuel table matter twice and turns the knob into a thing built
out of blocks. Below the cold threshold the machine refuses to run at all rather than cracking
badly — a machine producing a trickle of the wrong thing is harder to diagnose than one that has
stopped.

The two products leave on **opposite rows of the head**, with the row between them connected to
nothing. Two fluids on one face makes the split unenforceable, which this expansion has already
shipped once on the Wellhead.

Petcoke burns half again as long as coal, which is what stops the byproduct being a nuisance.

---

## Blowouts and the Blowout Preventer

A field that still has its own pressure will **blow out** if drilled without a Blowout Preventer:
a fountain of crude, a spill of ordinary flammable fluid blocks around the wellhead, and a tenth of
the field's remaining pressure gone up the bore.

The mechanic teaches itself. Only a field with pressure left can blow out, which is exactly when
the preventer is worth fitting — and it stops being worth fitting at the same moment the pumpjack
starts being worth building. The rig warns on its overlay while there is still time to act, because
a blowout with no notice at all would be a gotcha rather than a lesson.

The spill only lands on ground that is already open; a gusher that carved through a player's floor
would be a grief mechanic. **Absorbent Pads** lift spilt petroleum fluids three blocks at a time,
and only this mod's own — a pad that deleted water would be the answer to terraforming rather than
to a blowout.

Config-gated on `petroleumGushers`, default on.

---

## Re-injection Well

`common/blocks/petroleum/TileEntityReinjectionWell.java`.

A single block that puts water or gas back downhole and gets a second tranche out of a tiring
field — the thinking player's alternative to the flare.

| Injectant | Recovered per mB |
|---|---|
| Water | 0.35 |
| Sour gas (straight off the wellhead) | 0.55 |
| Natural gas (scrubbed) | 0.70 |

The ranking is the decision, and the middle row is the one that matters: a field can drive its own
enhanced recovery on the waste stream it was already producing. That turns "I have sour gas and
nowhere to put it" from a disposal problem into a third genuine option.

**Bounded by a permanent per-deposit allowance**, `PetroleumConfig.reinjectionCapFraction`, default
20% of the field's original capacity. `Reservoir.restore` on its own only stops at the original
capacity and says nothing about how many times you may refill to it; without the allowance this is
a water-to-crude converter. `Reservoir.restoredTotal` is persisted, so a server restart does not
hand every field its allowance back.

---

## Seismic Survey Kit

`api/petroleum/SeismicSurvey.java` (arithmetic), behaviour in `common/items/ItemPetroleum.java`.

The Core Sample Drill answers "is there oil *here*", one cell at a time, and a cell is 128 blocks
across. The survey kit answers "which way do I walk": it reads the nine cells around the player and
reports each deposit by **bearing and rough range** — never a coordinate, because that is still the
sample drill's job and a sharper reading would make it pointless.

Costs one gunpowder per shot. An item with subtypes cannot carry durability — metadata is already
spoken for — so the running cost is a consumable, which is thematically a seismic charge anyway.

The bearings are unit-tested rather than trusted: Minecraft's north is negative Z, which is exactly
the sort of thing that gets mirrored by accident, and a survey pointing the wrong way sends a player
four hundred blocks to nothing with no way to tell that the kit was what was wrong.

---

## Distillation recipes

`DistillationRecipe` (`api/crafting/DistillationRecipe.java`) is the only recipe type in the mod
with **multiple fluid outputs from one input**, because nothing else needs it: every other
machine yields at most one fluid, and a distillation column that could only emit one cut at a time
would either have to run once per fraction or throw the rest away (class doc,
`DistillationRecipe.java:19-25`). Outputs are declared in column order, lightest first, matching
the heights `MultiblockDistillationTower.drawHeight` actually draws them off at (see
[Distillation Tower](#distillation-tower) above). Recipes are batched in small discrete amounts
rather than modelled as continuous flow, reusing the existing process-queue machinery that JEI and
the other multiblocks already understand (`DistillationRecipe.java:31-34`).

It is explicitly **not unit-tested and cannot be**: every method takes or returns a `FluidStack`,
whose constructor touches `FluidRegistry`, which cannot bootstrap outside a running game
(`DistillationRecipe.java:36-39`); coverage is the server smoke run and `DistillationTowerTest`'s
shape/mapping checks instead.

One recipe is registered, at `IEContent.java:536-544`:

| Input | Output |
|---|---|
| 100 mB crude oil | 10 mB natural gas, 15 mB naphtha, 25 mB gasoline, 30 mB diesel, 10 mB heavy fuel oil, 4 mB lubricant, 6 mB bitumen |

Energy 2048, time 40 ticks. A comment at the registration site notes kerosene was deliberately cut
from the design, its yield folded into diesel (`IEContent.java:535`). The Distillation Tower is now
what drives this: `DistillationRecipe.findRecipe` is called from the tower's `update()` once a
batch slot is free (`TileEntityDistillationTower.java:221`), matched by `containsFluid` against
whatever the feed tank holds, and `findIncompleteRecipes` gates what the feed tank accepts in the
first place (`TileEntityDistillationTower.java:148`). The recipe list can hold more than one
recipe — `findRecipe` returns the first match — but only the crude-oil recipe above ships.

---

## Fluids

Registered in `IEContent.java:256-270`:

| Fluid | Registry name | Role |
|---|---|---|
| Crude Oil | `ie_crude_oil` | What a Wellhead produces; the distillation feedstock. |
| Naphtha | `ie_naphtha` | Lightest cut; better fed to a cracker than burned. |
| Gasoline | `ie_gasoline` | Drill fuel only — see below. |
| Diesel | `ie_diesel` | The best fuel a compression engine can burn. |
| Heavy Fuel Oil | `ie_heavy_fuel_oil` | Distillation residue; the Industrial Burner's best fuel. |
| Lubricant | `ie_lubricant` | Distillation residue. |
| Bitumen | `ie_bitumen` | Heaviest cut; the Mixer's feedstock for wet asphalt. |

All seven are prefixed `ie_`. The comment at the crude oil registration explains why
(`IEContent.java:254-255`): `setupFluid` yields to whoever registered a fluid name first, and a
bare name as common as `crude_oil` risks silently inheriting another mod's density, viscosity and
texture rather than this fork's own. `ie_asphalt`, registered just after the six cuts
(`IEContent.java:269`), is prefixed for the same reason but is the wet, pourable form of asphalt,
not itself a distillation cut; see [Asphalt and roads](#asphalt-and-roads) below.

**The engine-type split**, registered at `IEContent.java:893-918`:

- `DieselHandler.registerFuel` — burnable in the Diesel Generator. Crude oil is registered but
  deliberately awful (`50`, well under half of biodiesel's `125`), so burning it raw always reads
  as the wasteful choice next to refining it first (`IEContent.java:899-901`). Diesel (`162`) and
  naphtha (`112`) are the refined cuts registered here; **gasoline is absent** — a diesel
  generator cannot burn it at all (`IEContent.java:902-907`).
- `DieselHandler.registerDrillFuel` — burnable by handheld drills (spark engines, not compression
  engines). Gasoline and diesel are both registered here (`IEContent.java:911-912`), so gasoline
  has a use even though nothing else in the current tree consumes it, and diesel is deliberately
  on both lists.

The split — gasoline works in tools but never in a generator, diesel works in both — is what stops
one fluid being strictly the best fuel in every situation (`IEContent.java:902-910`).

---

## Asphalt and roads

Wet asphalt is downstream of distillation, not a cut of it: the Mixer turns bitumen — the heaviest,
least useful cut — into something a base can actually use, the same story lubricant and diesel
tell for the lighter ones. `MixerRecipe.addRecipe(new FluidStack(fluidAsphalt, 500), new
FluidStack(fluidBitumen, 250), new Object[]{"sand", "gravel", "gravel"}, 3200)`
(`IEContent.java:549`) turns 250 mB of bitumen and a shovelful of aggregate (one `sand`, two
`gravel`, by ore dictionary tag) into 500 mB of `ie_asphalt` (registered `IEContent.java:269`) —
bitumen is the bottleneck, not the aggregate, so paving is what stops the bottom of the barrel
being a waste product the player has to dump (comment at the registration site,
`IEContent.java:547-548`).

`BlockIEFluidAsphalt` (`common/blocks/petroleum/BlockIEFluidAsphalt.java`) is the poured, flowing
form — modelled on the concrete this fork already ships, and deliberately simpler than it. Concrete
sets into five different shapes depending on how deep the pour was, which suits a structural
material poured into forms; a road is flat by definition, so asphalt always sets into a full block
regardless of how sloppily it was poured — the mess is in the paving, not the result (class doc,
`BlockIEFluidAsphalt.java:24-31`). `updateTick` (`BlockIEFluidAsphalt.java:58-70`) counts
`SET_TICKS = 12` ticks (`BlockIEFluidAsphalt.java:41`) on an `IEProperties.INT_16` timer while
letting the pour keep flowing and find its own level, then replaces the block outright with
`BlockPetroleumDecoration.ASPHALT`.

`BlockPetroleumDecoration` (`common/blocks/petroleum/BlockPetroleumDecoration.java`) is the set
road surface, with a lowered `getSlipperiness` of `0.68` — enough that a road reads as faster than
the mud beside it without turning into an ice rink (`BlockPetroleumDecoration.java:36-44`).
`BlockTypes_PetroleumDecoration` (`common/blocks/petroleum/BlockTypes_PetroleumDecoration.java`)
carries three metas — `ASPHALT` (`:32`), `ASPHALT_TILE` (`:36`) and `ASPHALT_MARKED` (`:40`), all
three `listForCreative` (`:54-58`) — but **only `ASPHALT` is currently reachable through normal
play**: the wet-asphalt fluid always sets into it, and nothing in the tree — no recipe, no world
generation — produces `ASPHALT_TILE` or `ASPHALT_MARKED`. They exist as block metas and nothing
else today; worth a second look if the intent was for them to be craftable, not fixed here.

---

## Prospecting

`ReservoirSurvey` (`common/util/petroleum/ReservoirSurvey.java`) defines what a core sample
records about the oil under its chunk: a size band, a pressure percentage and a free-flowing
flag, deliberately short of an exact millibucket figure — a prospector is deciding *whether* to
drill, not reading a meter (class doc, `ReservoirSurvey.java:19-38`).

**The reading is taken once, on the server**, in `TileEntitySampleDrill.createCoreSample`
(`common/blocks/metal/TileEntitySampleDrill.java:138-143`) — only the server has
`ReservoirHandler`'s map — and baked into the sample stack's NBT via `ReservoirSurvey.write`
(`ReservoirSurvey.java:137-149`). The `KEY_SURVEYED` marker is written whether or not anything was
found, so an old sample cut before petroleum existed (missing the key entirely) can be told apart
from a sample that surveyed and found nothing (`ReservoirSurvey.java:48-55`).

**The display path is client-only.** `ItemCoresample.addReservoirInformation`
(`common/items/ItemCoresample.java:125-148`) is `@SideOnly(Side.CLIENT)` and reads only the NBT
already on the stack — it never asks `ReservoirHandler` anything, which is what makes the tooltip
correct in multiplayer at all (method doc, `ItemCoresample.java:111-119`). The same method is
shared with the placed core-sample block's overlay so the two readouts cannot drift apart
(`ItemCoresample.java:113-114`). Size bands are cut on a **log scale**
(`ReservoirSurvey.sizeBand`, `ReservoirSurvey.java:79-96`) to match the log-distributed capacity
roll — a linear cut would dump the overwhelming majority of fields into the bottom band.
Pressure below 1% still rounds up to `1`, never `0`, as long as `remaining > 0`, because a field
worked down that far still holds tens of thousands of millibuckets and still seeps
(`pressurePercent`, `ReservoirSurvey.java:114-125`).

---

## `PetroleumTickHandler`: why Wellheads are not `ITickable`

`PetroleumTickHandler` (`common/util/petroleum/PetroleumTickHandler.java`) drives every Wellhead
from a single `ServerTickEvent` subscriber rather than letting each tile entity tick itself. The
class doc states the reasoning plainly: a well produces a fraction of a bucket per second at best,
so ticking each one twenty times a second to move nothing is pure waste, and a mature field is
dozens of wells (`PetroleumTickHandler.java:23-29`) — the same arrangement, and the same
justification, as `GridTickHandler` for the virtual grid.

`PRODUCTION_INTERVAL = 20` ticks (`PetroleumTickHandler.java:39`). Each registered Wellhead fires
once per interval, on a tick chosen by `(tick + wellhead.getStagger()) % PRODUCTION_INTERVAL`
(`PetroleumTickHandler.java:97-102`), so a whole field of wells never all compute on the same
tick. Membership is a `LinkedHashSet`, iterated through a snapshot array rebuilt only when
membership changes (`snapshotDirty`, `PetroleumTickHandler.java:41-48,92-96`) — iterating the live
set directly would risk a concurrent-modification crash if pushing fluid into a neighbour ran
third-party code that touched registration. The Drilling Derrick and Pumpjack are driven the same
way but independently, gating their own `update()` against the same interval rather than
registering with this handler (`TileEntityDerrick.java:135`, `TileEntityPumpjack.java:119`) —
they are still `TileEntityMultiblockPart`s and therefore already tick, but do real work on only
one tick in twenty (Derrick: one in `DRILL_INTERVAL`, 10).

---

## City mode

`CityMode.petroleum()` (`common/util/CityMode.java:87-90`) gates on `IEConfig.cityMode &&
IEConfig.cityModePetroleum` (both default `true`). Per its doc comment
(`CityMode.java:82-86`) and `Config.java:111-114`:

- Reservoirs still exist, are still prospected and still drilled — the whole progression is
  unchanged in feel.
- A reservoir **never depletes**: `ReservoirModel.extract` still computes what would be drawn but
  never calls `Reservoir.deplete` (`ReservoirModel.java:157-171`).
- Because pressure never falls, `flowRate` never decays and `isFreeFlowing` never lapses — a
  well delivers at peak rate forever, with or without a pump.
- The Pumpjack still asserts itself and still costs power, but a flat per-pass `CITY_SIP` (1 IF)
  rather than the real 512 IF/t (`TileEntityPumpjack.java:170-186`), and per `shouldPump` it now
  pumps unconditionally rather than only past the (unreachable) free-flow threshold
  (`TileEntityPumpjack.java:146-165`).
- The Derrick still takes the full minute and still needs power, but a flat 10 FE per pass rather
  than 2560 (`TileEntityDerrick.java:210-229`).
- The Distillation Tower still needs its full energy cost banked before a batch advances, but every
  tick after that refunds down to a flat `CITY_SIP` of 1 FE instead of the real per-tick queue cost
  (`TileEntityDistillationTower.java:265-282,86`).
- The Industrial Burner still needs fuel in its tank and still empties it, but at a flat
  `CITY_FUEL_SIP` of 1 mB per stoking pass, with the heat store simply held full rather than
  metered against what has actually been drawn (`TileEntityIndustrialBurner.java:337-352,195`).

This is the same trade city mode makes for the wire network and the virtual grid: keep every
gesture and every piece of equipment meaningful to place, drop only the per-tick accounting.

---

## Commands

`CommandReservoir` (`common/util/commands/CommandReservoir.java`), registered as `/ie reservoir`
in `CommandHandler.java:39`, permission level 4 (`CommandReservoir.java:61-65`). It exists because
deposits are invisible — without it there is no way to tell "this cell rolled nothing" apart from
"the pumpjack is wired up wrong" (class doc, `CommandReservoir.java:28-34`).

| Subcommand | Usage | Does |
|---|---|---|
| `info` | `/ie reservoir info [x] [z]` | Type, remaining/original capacity, free-flow state, and unpumped/pumped rate at the target cell (player's position by default). |
| `types` | `/ie reservoir types` | Lists every registered `ReservoirType` with its weight, roll share, and capacity range. |
| `deplete` | `/ie reservoir deplete <amount\|all> [x] [z]` | Removes fluid from a cell's pool. Testing / incident tool. |
| `refill` | `/ie reservoir refill [amount\|all] [x] [z]` | Restores fluid, never above original capacity. The recovery half — undoing a mistake or a bug, not a game mechanic. |

Both `deplete` and `refill` call `PetroleumSaveData.setDirty()` explicitly
(`CommandReservoir.java:213-214,249-250`) since they mutate a `Reservoir` object directly rather
than going through `TileEntityWellhead.produce`.

---

## Configuration

`Config → Immersive Engineering → Petroleum` (`common/Config.java:233-259`), mirrored into
`PetroleumConfig` (`api/petroleum/PetroleumConfig.java`) by `Config.onConfigUpdate()`
(`Config.java:695-709`) — the api-side model must not reach into `common.Config`, the same
arrangement `GridConfig` and `WireType` use.

| Key | Default | Meaning |
|---|---|---|
| `enablePetroleum` | true | Master switch. Off = no reservoir is ever generated and extraction blocks stay inert (still placeable). |
| `petroleumCellChunkSize` | 8 | Edge length, in chunks, of the square cell a single reservoir occupies. |
| `petroleumCellChance` | 0.25 | Chance any given cell contains a reservoir at all. |
| `petroleumMinCapacity` | 2,000,000 | Smallest reservoir roll, mB. |
| `petroleumMaxCapacity` | 16,000,000 | Largest reservoir roll, mB. Rolls are log-distributed between the two bounds. |
| `petroleumFreeFlowThreshold` | 0.6 | Fraction of original capacity remaining above which a well flows without a pump. |
| `petroleumPeakFlowRate` | 30 | Peak extraction rate, mB/tick, at full pressure. |
| `petroleumResidualFlowRate` | 0.025 | Floor the flow rate decays to rather than reaching zero, mB/tick. |
| `petroleumDimensionBlacklist` | `[1]` (the End) | Dimensions in which reservoirs are never generated. |
| `cityModePetroleum` | true | City-mode sub-flag (`common/Config.java:111-114`), under the city mode block; see [City mode](#city-mode). |

Raising `petroleumMaxCapacity` (or any other bound) takes effect on the next `registerDefaults()`
call, which `onConfigUpdate()` triggers on every reload (`Config.java:706-709`) — but only for
cells not yet rolled. A cell already cached keeps the capacity it rolled at.

---

## Performance notes

- No Wellhead is `ITickable`; all production runs from one `ServerTickEvent` pass in
  `PetroleumTickHandler`, staggered per well so a field never computes on one tick.
- Extraction is computed analytically over an elapsed-tick interval rather than polled every
  tick, with `MAX_CATCH_UP_TICKS` bounding how much idle time a single catch-up call may be
  billed for.
- A Wellhead's output-face scan is cached and only rebuilt on neighbour change
  (`facesDirty`), not re-scanned every production pass.
- The Derrick and Pumpjack still tick as multiblock parts but gate real work behind their own
  interval checks, and `ApiUtils.checkForNeedlessTicking` drops non-master parts out of the
  ticking list entirely for the structure's lifetime.
- The Industrial Burner stokes once per `BURN_INTERVAL` (20 ticks), staggered by position the same
  way the Wellhead is, and only re-scans its crown for heatable neighbours on that same interval —
  not the furnace-feeding pass, which has to run every tick to keep a lit furnace lit
  (`TileEntityIndustrialBurner.java:314-321`). The Distillation Tower throttles its own recipe
  search rather than re-evaluating the recipe list every tick while idle.
- Reservoir cells are rolled once and cached in memory (`ReservoirHandler.CACHE`); repeat lookups
  of the same cell are a hash-map hit, not a re-roll.

---

## Source map

| Concern | File |
|---|---|
| Deposit registry, cell rolling, seed mixing, persistence shape | `api/petroleum/ReservoirHandler.java` |
| Flow/decline model, capacity roll, catch-up clamp (world-free, unit-tested) | `api/petroleum/ReservoirModel.java` |
| Per-deposit state | `api/petroleum/Reservoir.java` |
| Deposit type definition | `api/petroleum/ReservoirType.java` |
| Config mirror | `api/petroleum/PetroleumConfig.java` |
| Multi-fluid-output recipe type | `api/crafting/DistillationRecipe.java` |
| Wellhead | `common/blocks/petroleum/TileEntityWellhead.java` |
| Drilling Derrick tile entity / shape | `common/blocks/petroleum/TileEntityDerrick.java`, `common/blocks/multiblocks/MultiblockDerrick.java` |
| Pumpjack tile entity / shape | `common/blocks/petroleum/TileEntityPumpjack.java`, `common/blocks/multiblocks/MultiblockPumpjack.java` |
| Distillation Tower tile entity / shape | `common/blocks/petroleum/TileEntityDistillationTower.java`, `common/blocks/multiblocks/MultiblockDistillationTower.java` |
| Industrial Burner tile entity / shape | `common/blocks/petroleum/TileEntityIndustrialBurner.java`, `common/blocks/multiblocks/MultiblockIndustrialBurner.java` |
| Wet asphalt fluid block / laid road blocks | `common/blocks/petroleum/BlockIEFluidAsphalt.java`, `BlockPetroleumDecoration.java`, `BlockTypes_PetroleumDecoration.java` |
| Placeable device / assembled-structure block metas | `common/blocks/petroleum/BlockPetroleumDevice.java`, `BlockTypes_PetroleumDevice.java`, `BlockPetroleumMultiblock.java`, `BlockTypes_PetroleumMultiblock.java` |
| Shared structure geometry | `common/blocks/petroleum/PetroleumGeometry.java` |
| Tick driver (why Wellheads are not `ITickable`) | `common/util/petroleum/PetroleumTickHandler.java` |
| World-save persistence | `common/util/petroleum/PetroleumSaveData.java` |
| Core-sample survey banding + NBT | `common/util/petroleum/ReservoirSurvey.java` |
| Server-side survey write | `common/blocks/metal/TileEntitySampleDrill.java` |
| Client-side survey tooltip | `common/items/ItemCoresample.java` |
| Fluids, distillation recipe, asphalt Mixer recipe, `DieselHandler` fuel/drill-fuel registration | `common/IEContent.java` (fluid block ~L256-270, distillation recipe ~L536-544, asphalt Mixer recipe ~L549, `DieselHandler` calls ~L893-918) |
| City mode gate | `common/util/CityMode.java` |
| Commands | `common/util/commands/CommandReservoir.java` |
| Power plants | `common/blocks/petroleum/TileEntityFuelOilBoiler.java`, `TileEntityHRSG.java`, `TileEntitySteamTurbineHall.java`, `TileEntityEngineBank.java` and their `Multiblock*` shapes |
| Buried tanks | `common/blocks/petroleum/BuriedTankGeometry.java`, `TileEntityBuriedTank.java` (+ three tier subclasses), `common/blocks/multiblocks/MultiblockBuriedTank.java` |
| Forecourt | `common/blocks/petroleum/TileEntityGasPump.java`, `TileEntityForecourtSign.java`, `TileEntityPortableGenerator.java`, `client/gui/GuiGasPump.java`, `common/gui/ContainerGasPump.java`, `common/util/network/MessagePumpSettings.java` |
| Economy hook | `api/petroleum/FuelDispensedEvent.java` |
| Immersive Vehicles fuel injection (reflection) / mapping table (world-free, unit-tested) | `common/util/compat/mts/MTSHelper.java`, `common/util/compat/mts/MTSFuelTable.java` |
| Loose items (nozzle, drill pipe, preventer, pad, petcoke, survey kit) | `common/items/ItemPetroleum.java`, `common/items/PetroleumItemNames.java` |
| Loading gantry | `common/blocks/petroleum/TileEntityLoadingGantry.java`, `common/blocks/multiblocks/MultiblockLoadingGantry.java` |
| Cracking unit | `common/blocks/petroleum/TileEntityCrackingUnit.java`, `common/blocks/multiblocks/MultiblockCrackingUnit.java` |
| Enhanced recovery | `common/blocks/petroleum/TileEntityReinjectionWell.java`, allowance tracking in `api/petroleum/Reservoir.java` |
| Seismic survey arithmetic (world-free, unit-tested) | `api/petroleum/SeismicSurvey.java` |
| Virtual fluid network | `api/fluid/network/`, `common/blocks/fluidnet/`, `common/util/fluidnet/` — see `docs/FLUID_NETWORK.md` |
| Tests | `src/test/java/blusunrize/immersiveengineering/api/petroleum/` (`ReservoirHandlerTest`, `ReservoirModelTest`), `src/test/java/.../common/util/petroleum/ReservoirSurveyTest.java`, `src/test/java/.../common/blocks/petroleum/` (`DerrickTest`, `PumpjackTest`, `DistillationTowerTest`, `IndustrialBurnerTest`, `PetroleumAssetsTest`), `src/test/java/.../common/util/compat/mts/MTSFuelTableTest.java` |

The reservoir model (`ReservoirHandler`, `ReservoirModel`, `Reservoir`, `ReservoirType`) and
`ReservoirSurvey` are expressed purely in terms of plain data and `java.util.Random` — none of them
touch `World` or `TileEntity` — which is what makes cell rolling, the decline curve, the free-flow
threshold, the catch-up clamp and the survey banding all directly unit-testable. `DistillationRecipe`
is the one exception in this feature: it is untestable by construction because `FluidStack` requires
a live `FluidRegistry`, and its registration is covered by the server smoke run instead.
