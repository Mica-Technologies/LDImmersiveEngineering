# The Petroleum System

A fork-only feature: oil reservoirs rolled deterministically under the world, and the
above-ground equipment that finds, taps and (eventually) refines them.

Like the excavator's mineral veins, deposits are **virtual** — nothing is placed underground and
nothing runs at chunk-gen time. A cell is rolled the first time anything asks what is beneath it,
from a hash of the world seed and the cell's coordinates. That makes world generation free,
gives existing worlds oil retroactively with no retrogen pass, and means only cells somebody has
actually drawn from are worth persisting.

**Status: partially built.** The reservoir model, prospecting, the Wellhead, the Drilling Derrick
and the Pumpjack are complete and tested. The Distillation Tower and Industrial Burner exist only
as block metas and empty tile-entity stubs with no shape, no recipe wiring and no GUI — see
[Distillation Tower and Industrial Burner](#distillation-tower-and-industrial-burner-stub). The
`DistillationRecipe` type and one crude-oil recipe are registered and ready for a machine to use
them, but nothing currently calls `DistillationRecipe.findRecipe`.

---

## The parts

| Block | State | What it does |
|---|---|---|
| **Wellhead** | Complete | Caps a drilled bore. Collects whatever the deposit gives up and pushes it into any adjacent fluid handler. |
| **Drilling Derrick** | Complete | A 3×3×9 lattice tower, hammered together from Oilfield Frame. Sinks a bore over one minute of powered drilling, then hands over a Wellhead and packs itself back into frame. |
| **Pumpjack** | Complete | A 3×6×5 machine that drives a Wellhead whose deposit has fallen below free-flow pressure. Carries no fluid of its own. |
| **Distillation Tower** | Stub | Block meta and an empty `TileEntityDistillationTower` exist; no shape class, no `MultiblockHandler` registration, no logic. Cannot currently be formed. |
| **Industrial Burner** | Stub | Same: block meta and an empty `TileEntityIndustrialBurner`, otherwise unimplemented. |

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

## Distillation Tower and Industrial Burner (stub)

Both `TileEntityDistillationTower` and `TileEntityIndustrialBurner`
(`common/blocks/petroleum/TileEntityDistillationTower.java`,
`.../TileEntityIndustrialBurner.java`) are, in full, an empty `update()` override, `getBlockBounds`
returning `null`, no accessible fluid tanks, and `getOriginalBlock()` returning an Oilfield Frame.
Their block metas exist (`BlockTypes_PetroleumMultiblock.DISTILLATION_TOWER`,
`.INDUSTRIAL_BURNER`, `common/blocks/petroleum/BlockTypes_PetroleumMultiblock.java:37-41`) and
`BlockPetroleumMultiblock.createBasicTE` constructs the stub tile entities for them
(`BlockPetroleumMultiblock.java:54-57`), but **there is no `MultiblockDistillationTower` or
`MultiblockIndustrialBurner` shape class**, nothing is registered with
`MultiblockHandler.registerMultiblock` for either (compare `IEContent.java:1082-1083`, which
registers only the Derrick and the Pumpjack), and neither name appears in `en_us.lang`. They
cannot currently be formed by a hammer, have no GUI, and do nothing. `PetroleumGeometry` already
reserves their footprints (`TOWER_SIZE` 4×4×12, `BURNER_SIZE` 3×3×3,
`PetroleumGeometry.java:53-71`), which is the only committed design fact about either machine
today. Other agents are actively building these; document what is in the tree, not the plan.

---

## Distillation recipes

`DistillationRecipe` (`api/crafting/DistillationRecipe.java`) is the only recipe type in the mod
with **multiple fluid outputs from one input**, because nothing else needs it: every other
machine yields at most one fluid, and a distillation column that could only emit one cut at a time
would either have to run once per fraction or throw the rest away (class doc,
`DistillationRecipe.java:19-25`). Outputs are declared in column order, lightest first, matching
the tower's intended draw-off heights. Recipes are batched in small discrete amounts rather than
modelled as continuous flow, reusing the existing process-queue machinery that JEI and the other
multiblocks already understand (`DistillationRecipe.java:31-34`).

It is explicitly **not unit-tested and cannot be**: every method takes or returns a `FluidStack`,
whose constructor touches `FluidRegistry`, which cannot bootstrap outside a running game
(`DistillationRecipe.java:36-39`); coverage is the server smoke run instead.

One recipe is registered, at `IEContent.java:522-530`:

| Input | Output |
|---|---|
| 100 mB crude oil | 10 mB natural gas, 15 mB naphtha, 25 mB gasoline, 30 mB diesel, 10 mB heavy fuel oil, 4 mB lubricant, 6 mB bitumen |

Energy 2048, time 40 ticks. A comment at the registration site notes kerosene was deliberately cut
from the design, its yield folded into diesel (`IEContent.java:521`). No code currently calls
`DistillationRecipe.findRecipe` or `findIncompleteRecipes` — the recipe exists and is queryable,
but nothing drives it yet, consistent with the Distillation Tower being a stub.

---

## Fluids

Registered in `IEContent.java:251-259`:

| Fluid | Registry name | Role |
|---|---|---|
| Crude Oil | `ie_crude_oil` | What a Wellhead produces; the distillation feedstock. |
| Naphtha | `ie_naphtha` | Lightest cut; better fed to a cracker than burned. |
| Gasoline | `ie_gasoline` | Drill fuel only — see below. |
| Diesel | `ie_diesel` | The best fuel a compression engine can burn. |
| Heavy Fuel Oil | `ie_heavy_fuel_oil` | Distillation residue. |
| Lubricant | `ie_lubricant` | Distillation residue. |
| Bitumen | `ie_bitumen` | Heaviest cut. |

All seven are prefixed `ie_`. The comment at the crude oil registration explains why
(`IEContent.java:249-250`): `setupFluid` yields to whoever registered a fluid name first, and a
bare name as common as `crude_oil` risks silently inheriting another mod's density, viscosity and
texture rather than this fork's own.

**The engine-type split**, registered at `IEContent.java:871-896`:

- `DieselHandler.registerFuel` — burnable in the Diesel Generator. Crude oil is registered but
  deliberately awful (`50`, well under half of biodiesel's `125`), so burning it raw always reads
  as the wasteful choice next to refining it first (`IEContent.java:877-879`). Diesel (`162`) and
  naphtha (`112`) are the refined cuts registered here; **gasoline is absent** — a diesel
  generator cannot burn it at all (`IEContent.java:880-884`).
- `DieselHandler.registerDrillFuel` — burnable by handheld drills (spark engines, not compression
  engines). Gasoline and diesel are both registered here (`IEContent.java:889-890`), so gasoline
  has a use even though nothing else in the current tree consumes it, and diesel is deliberately
  on both lists.

The split — gasoline works in tools but never in a generator, diesel works in both — is what stops
one fluid being strictly the best fuel in every situation (`IEContent.java:880-888`).

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
| Distillation Tower (stub) | `common/blocks/petroleum/TileEntityDistillationTower.java` |
| Industrial Burner (stub) | `common/blocks/petroleum/TileEntityIndustrialBurner.java` |
| Placeable device / assembled-structure block metas | `common/blocks/petroleum/BlockPetroleumDevice.java`, `BlockTypes_PetroleumDevice.java`, `BlockPetroleumMultiblock.java`, `BlockTypes_PetroleumMultiblock.java` |
| Shared structure geometry | `common/blocks/petroleum/PetroleumGeometry.java` |
| Tick driver (why Wellheads are not `ITickable`) | `common/util/petroleum/PetroleumTickHandler.java` |
| World-save persistence | `common/util/petroleum/PetroleumSaveData.java` |
| Core-sample survey banding + NBT | `common/util/petroleum/ReservoirSurvey.java` |
| Server-side survey write | `common/blocks/metal/TileEntitySampleDrill.java` |
| Client-side survey tooltip | `common/items/ItemCoresample.java` |
| Fluids, `DieselHandler` fuel/drill-fuel registration | `common/IEContent.java` (fluid block ~L251-259, distillation recipe ~L518-530, `DieselHandler` calls ~L871-896) |
| City mode gate | `common/util/CityMode.java` |
| Commands | `common/util/commands/CommandReservoir.java` |
| Tests | `src/test/java/blusunrize/immersiveengineering/api/petroleum/` (`ReservoirHandlerTest`, `ReservoirModelTest`), `src/test/java/.../common/util/petroleum/ReservoirSurveyTest.java`, `src/test/java/.../common/blocks/petroleum/` (`DerrickTest`, `PumpjackTest`) |

The reservoir model (`ReservoirHandler`, `ReservoirModel`, `Reservoir`, `ReservoirType`) and
`ReservoirSurvey` are expressed purely in terms of plain data and `java.util.Random` — none of them
touch `World` or `TileEntity` — which is what makes cell rolling, the decline curve, the free-flow
threshold, the catch-up clamp and the survey banding all directly unit-testable. `DistillationRecipe`
is the one exception in this feature: it is untestable by construction because `FluidStack` requires
a live `FluidRegistry`, and its registration is covered by the server smoke run instead.
