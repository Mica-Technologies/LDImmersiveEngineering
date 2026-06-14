# Tile Entities & Devices

Technical documentation for the block / tile-entity foundation of Immersive Engineering
(Forge 1.12.2) and the catalog of **non-multiblock** devices built on top of it.

Multiblock machines (arc furnace, crusher, refinery, etc.) and the wire / energy
connection internals are documented separately — see `WIRE_AND_ENERGY_NETWORK.md`.
This document focuses on the base classes, the block/TE interface contracts, and the
mechanics of the standalone devices (capacitors, generators, pumps, lights, turrets,
fluid pipes, and the wooden / cloth / stone / plant blocks).

All code lives under
`src/main/java/blusunrize/immersiveengineering/common/blocks/`.

---

## 1. Block & Tile-Entity base classes

### `BlockIEBase<E extends Enum & IBlockEnum>`
`common/blocks/BlockIEBase.java:45`

The shared superclass for nearly every IE block. It is a **meta-block**: a single
`Block` instance backs many sub-types selected by a `PropertyEnum<E>` (the enum carries
per-meta hardness, resistance, render layers, light opacity, mobility flags, hammer
harvestability, etc.). Key responsibilities:

- Builds its `BlockStateContainer` from the main enum property plus any additional
  listed / unlisted properties passed to the constructor.
- Registers itself and a generated `ItemBlockIEBase` into `IEContent.registeredIEBlocks` /
  `registeredIEItems` (`BlockIEBase.java:103-106`).
- Implements `IIEMetaBlock` so the model/state-mapper system can enumerate variants.
- Per-meta overrides for `getBlockHardness`, `getExplosionResistance`, render layer,
  `getLightOpacity`, push reaction, and custom state mapping.

`getMetaProperty()` (`BlockIEBase.java:133`) returns the enum property; this is used by
`TileEntityIEBase.shouldRefresh` to decide whether a block change should drop the TE.

### `BlockIETileProvider<E>`
`common/blocks/BlockIETileProvider.java`

Extends `BlockIEBase` and adds tile-entity behavior — this is the base for every block
that has a TE. It bridges TE state onto block state for rendering and routes world
callbacks into the interfaces below. Notable methods:

- **`getActualState`** (`:258`) — reads the TE and applies `IAttachedIntegerProperies`,
  `IDirectionalTile` facing, `IActiveState` / `IDualState` boolean props, dummy-block
  flag, and `IMirrorAble` onto the block state. Called by the render path and on chunk
  load (see performance notes — this does a `world.getTileEntity(pos)` per call).
- **`getExtendedState`** (`:334`) — pulls unlisted properties (side config, OBJ model
  state, connections) for the smart-model renderer.
- **`getComparatorInputOverride`** (`:687`) — delegates to `IComparatorOverride`.
- Forwards `onBlockActivated`, neighbor changes, placement, drops, and collision into
  the corresponding TE interfaces.

### `TileEntityIEBase`
`common/blocks/TileEntityIEBase.java:33`

The abstract base for all IE tile entities. Provides:

- **Custom NBT split** — subclasses implement `readCustomNBT(nbt, descPacket)` /
  `writeCustomNBT(nbt, descPacket)` instead of the vanilla methods. The `descPacket`
  flag lets a TE write a smaller payload for the description (sync) packet than for
  disk save. `getUpdatePacket` / `getUpdateTag` / `onDataPacket` are wired to these
  (`:55-74`).
- **Rotation / mirror** — `rotate` / `mirror` (`:78-112`) operate through
  `IDirectionalTile` when present.
- **`shouldRefresh`** (`:147`) — keeps the TE alive across meta-only state changes
  (compares `getMetaProperty()` values) so e.g. a capacitor doesn't reset when its
  active flag flips.
- **`markContainingBlockForUpdate` / `markBlockForUpdate`** (`:157-169`) — the standard
  "notify render + neighbors" helper used throughout.
- **Energy capability passthrough** (`:172-186`) — exposes `CapabilityEnergy.ENERGY`
  when the TE is an `IIEInternalFluxConnector`.
- `getMaxRenderDistanceSquared` is scaled by `Config.IEConfig.increasedTileRenderdistance`.

### `TileEntityMultiblockPart` / `IGeneralMultiblock`
`common/blocks/TileEntityMultiblockPart.java`

Base for multiblock components (out of scope here) but relevant because
`IGeneralMultiblock.isLogicDummy()` is the hook used by `ApiUtils.checkForNeedlessTicking`
to unregister "dummy" parts from the tick list (see §5).

---

## 2. The `IEBlockInterfaces` contract

`common/blocks/IEBlockInterfaces.java` is a single file of marker / capability interfaces
that a TE (or block) opts into. `BlockIETileProvider` checks `instanceof` against these to
decide behavior, so adding a feature to a device usually means implementing one of these.

| Interface | Line | What it provides |
|---|---|---|
| `IIEMetaBlock` | `:44` | Meta-block contract: name, meta property, enum values, custom state mapping. Implemented by `BlockIEBase`. |
| `IAttachedIntegerProperies` | `:64` | Maps named `PropertyInteger`s onto block state from TE values (e.g. wallmount orientation). |
| `IUsesBooleanProperty` | `:77` | Supplies a `PropertyBoolInverted` for `IActiveState` / `IDualState` / `IMirrorAble`. |
| `IBlockOverlayText` | `:82` | Text shown when looking at the block (with/without hammer); optional nixie font. |
| `ISoundTile` | `:89` | Gate looping sounds. |
| `ISpawnInterdiction` | `:94` | Square radius in which mob spawning is blocked (floodlight, electric lantern, tesla coil). |
| `IComparatorOverride` | `:99` | Custom comparator output (capacitors, tanks, crates). |
| `IRedstoneOutput` | `:104` | Weak/strong RS output + `canConnectRedstone` (strip curtain). |
| `ILightValue` | `:116` | Dynamic emitted light level. |
| `IColouredBlock` / `IColouredTile` | `:121` / `:128` | Per-tint render colour from block or TE (dyed pipes, balloons). |
| `IDirectionalTile` | `:133` | Facing get/set, **`getFacingLimitation()`** (0=clicked side … 6=clicked-side preferring) driving placement orientation, rotation rules. |
| `IAdvancedDirectionalTile` | `:194` | Adds `onDirectionalPlacement` for tiles needing hit-vector at placement (floodlight, wallmount). |
| `IConfigurableSides` | `:199` | Per-side `SideConfig` (input/output/none) toggled by hammer. |
| `ITileDrop` | `:206` | Custom drops + `readOnPlacement` to restore NBT (windmill sails, crate contents, shader). |
| `IAdditionalDrops` | `:235` | Extra drops beyond the block itself (pipe cover). |
| `IEntityProof` | `:240` | Veto entity-caused destruction (turrets). |
| `IPlayerInteraction` | `:245` | Right-click handler routed from `onBlockActivated`. |
| `IHammerInteraction` | `:250` | Engineer's-hammer right-click handler. |
| `IPlacementInteraction` | `:255` | `onTilePlaced` callback (pipe colour matching). |
| `IActiveState` | `:260` | "active" boolean mapped to a block-state property (machines, lights). |
| `IDualState` | `:265` | A second two-state property (strip curtain open/closed). |
| `IMirrorAble` | `:270` | Mirrored boolean property. |
| `IBlockBounds` / `IFaceShape` | `:275` / `:280` | Custom bounding box / face shape. |
| `IAdvancedSelectionBounds` / `IAdvancedCollisionBounds` | `:285` / `:292` | Multi-AABB selection / collision (pipes, posts, banners). |
| `IHasDummyBlocks` | `:297` | Multi-block-tall single devices: `placeDummies` / `breakDummies` / `isDummy` (pump, drill, watermill, turret, energy meter). |
| `IHasObjProperty` / `IAdvancedHasObjProperty` | `:320` / `:325` | OBJ-model display-list / `OBJState` for the connected-geometry renderer (pipes). |
| `IGuiTile` | `:336` | GUI id + master TE for container blocks (crate, workbench, sorter, charging station). |
| `IProcessTile` | `:355` | Exposes process step/max arrays for progress rendering. |
| `INeighbourChangeTile` | `:362` | `onNeighborBlockChange(otherPos)` — targeted neighbor callback (pipe, turntable, thermoelectric). |
| `ICacheData` | `:371` | Render cache invalidation key. |

---

## 3. How TEs tick (and how they stop)

There is **no central IE tick dispatcher**. Each ticking device simply implements
vanilla `net.minecraft.util.ITickable` and is ticked by the world's
`tickableTileEntities` list every game tick.

To avoid wasting ticks on dummy/slave blocks of multi-tall devices, the convention is to
call `ApiUtils.checkForNeedlessTicking(this)` at the top of `update()`
(`api/ApiUtils.java:948`):

```java
public static <T extends TileEntity & IGeneralMultiblock> void checkForNeedlessTicking(T te) {
    if(!te.getWorld().isRemote && te.isLogicDummy())
        EventHandler.REMOVE_FROM_TICKING.add(te);
}
```

`EventHandler.onWorldTick` (`common/EventHandler.java:368`) then bulk-removes those TEs
from `world.tickableTileEntities` (`:443-446`). The slave still *exists* and still ran
`update()` at least once, but it will not be ticked again while it remains a dummy.

Spawn-interdiction TEs (`ISpawnInterdiction`) register themselves into
`EventHandler.interdictionTiles` and are scanned during the mob-spawn check rather than
in their own tick.

### TEs in scope that implement `ITickable`

`CapacitorLV` (+MV/HV/Creative subclasses), `Dynamo`*, `ThermoelectricGen`,
`ChargingStation`, `ElectricLantern`, `FluidPump`, `FluidPlacer`, `FurnaceHeater`,
`EnergyMeter`, `Floodlight`, `SampleDrill`, `Turret` (gun/chem), `Windmill`, `Watermill`,
`WoodenBarrel` (+`MetalBarrel`), `StripCurtain`.

(*`Dynamo` is **not** `ITickable` — it generates reactively when a windmill/watermill
pushes rotation into it. Listed here only to correct a common assumption.)

---

## 4. Device catalog

Energy in IE is **Immersive Flux (IF)**, stored in `FluxStorage`. Fluids use Forge
`FluidTank`. "Tickable" means the TE implements `ITickable`.

### Capacitors — `BlockMetalDevice0`
Configurable-sided IF batteries. Each side can be input / output / none (`IConfigurableSides`),
expose a comparator signal, and drop with stored energy.

| Device | Class:line | Storage | Notes |
|---|---|---|---|
| LV Capacitor | `metal/TileEntityCapacitorLV.java:40` | `FluxStorage` (LV limits) | Base impl; tickable, pushes energy out configured sides, comparator recomputed every 32 ticks (pos-staggered). |
| MV Capacitor | `metal/TileEntityCapacitorMV.java:13` | MV limits | Extends LV, overrides storage limits. |
| HV Capacitor | `metal/TileEntityCapacitorHV.java:13` | HV limits | Extends MV. |
| Creative Capacitor | `metal/TileEntityCapacitorCreative.java:17` | `Integer.MAX_VALUE` | Infinite source; pushes `MAX_VALUE` out every OUTPUT side each tick. |

### Generators
| Device | Class:line | Output | Notes |
|---|---|---|---|
| Dynamo | `metal/TileEntityDynamo.java:29` | IF | **Reactive, not tickable.** A windmill/watermill calls `inputRotation(power, side)`; the dynamo converts `dynamo_output * rotation` to IF and distributes to neighbors. |
| Thermoelectric Generator | `metal/TileEntityThermoelectricGen.java:37` | IF | Tickable. Recomputes output every 1024 ticks from the temperature differential of opposing block/fluid pairs, then pushes IF to neighbors. |
| Windmill | `wooden/TileEntityWindmill.java:38` | rotation | Tickable. Up to 8 sails; re-scans its clear area every 128 ticks (`checkArea()`), spins faster while raining/thundering, and feeds rotation to the dynamo it faces. |
| Watermill | `wooden/TileEntityWatermill.java:34` | rotation | Tickable, multi-wheel. Computes power from surrounding water-flow vectors, syncs rotation across a row of up to 3 wheels, drives the adjacent dynamo. |

### Lighting
| Device | Class:line | Notes |
|---|---|---|
| Floodlight | `metal/TileEntityFloodlight.java:49` | Tickable, wired (LV). Ray-traces a fan of beams and places/removes `BlockFakeLight` blocks along them; `ISpawnInterdiction` radius 32 while active. Aimable with hammer. |
| Electric Lantern | `metal/TileEntityElectricLantern.java:27` | Tickable, wired. Light 15 while powered; `ISpawnInterdiction`. |
| Lantern | `metal/TileEntityLantern.java:23` | Passive decorative lantern, constant light 14. No tick, no power. |

### Fluid handling
| Device | Class:line | Storage | Notes |
|---|---|---|---|
| Fluid Pump | `metal/TileEntityFluidPump.java:53` | `FluidTank(4000)` + `FluxStorage(8000)` | Tickable, 2-tall (`IHasDummyBlocks`). Drains world fluid / pulls from neighbors when powered (consuming IF to pressurize); infinite-water handling. The active driver of the pipe network. |
| Fluid Placer | `metal/TileEntityFluidPlacer.java:37` | `FluidTank(4000)` | Tickable. Places one fluid block every 16 ticks via a BFS placement queue; re-seeds the volume every 512 ticks. |
| Fluid Pipe | `metal/TileEntityFluidPipe.java:64` | none (passthrough) | **Not tickable** — push-based. See §6. |
| Wooden Barrel | `wooden/TileEntityWoodenBarrel.java:42` | `FluidTank(12000)` | Tickable. Outputs ≤40 mB/tick from configured top/bottom sides. Rejects gases and fluids ≥573 K. |
| Metal Barrel | `metal/TileEntityMetalBarrel.java:14` | inherits 12000 | Extends WoodenBarrel; accepts hot/gaseous fluids. |
| Fluid Sorter | `wooden/TileEntityFluidSorter.java:35` | filter defs only | Reactive router; sorts incoming fluid to sides by per-side filters. |

### Other metal devices
| Device | Class:line | Notes |
|---|---|---|
| Charging Station | `metal/TileEntityChargingStation.java:41` | Tickable. Charges a flux-storing item in its single slot from `FluxStorageAdvanced(32000)`. |
| Furnace Heater | `metal/TileEntityFurnaceHeater.java:33` | Tickable. Consumes IF to heat adjacent `IExternalHeatable` machines / vanilla furnaces. |
| Energy Meter | `metal/TileEntityEnergyMeter.java:37` | Tickable, 2-tall. Inline wire meter; keeps a rolling 20-sample average of energy throughput and outputs a comparator signal. |
| Sample Drill | `metal/TileEntitySampleDrill.java:47` | Tickable, 3-tall. Consumes IF over `coredrill_time` to produce a core sample of the chunk's mineral vein. |
| Turret (Gun / Chem) | `metal/TileEntityTurret.java:55` | Tickable, 2-tall. `FluxStorage(16000)`. Scans for `EntityLivingBase` in range, validates target (white/black-list, owner, animals/players/neutrals), ray-checks line of fire, and fires. `IEntityProof`. |
| Razor Wire | `metal/TileEntityRazorWire.java:38` | Damages entities on contact; shocks the whole wire span when fed LV. Not tickable. |
| Toolbox | `metal/TileEntityToolbox.java:41` | GUI item storage; drops retaining contents. |

### Wooden devices (non-fluid)
| Device | Class:line | Notes |
|---|---|---|
| Item Sorter | `wooden/TileEntitySorter.java:37` | Reactive item router; 6 sides × 8 filter slots, DFS loop protection. |
| Turntable | `wooden/TileEntityTurntable.java:22` | Rotates the block in front of it on a rising redstone edge (`INeighbourChangeTile`). |
| Engineer's Workbench | `wooden/TileEntityModWorkbench.java:37` | 2-block GUI workbench for tool config / blueprint crafting. |
| Wooden Crate | `wooden/TileEntityWoodenCrate.java:48` | 27-slot storage; drops with contents; supports loot tables. |
| Wooden Post | `wooden/TileEntityWoodenPost.java:31` | 4-tall fence post; hammer-added arms route wires. |
| Wallmount | `wooden/TileEntityWallmount.java:23` | Decorative angled bracket, hammer-adjustable. |

### Cloth — `BlockClothDevice`
| Device | Class:line | Notes |
|---|---|---|
| Balloon | `cloth/TileEntityBalloon.java:45` | Hangable light-emitting (13) balloon; dyeable + shaderable; pops when hit by a projectile. |
| Shader Banner | `cloth/TileEntityShaderBanner.java:32` | Displays a shader; drops banner + shader. |
| Strip Curtain | `cloth/TileEntityStripCurtain.java:37` | Tickable (every 4 ticks, staggered). Scans its AABB for entities and outputs a redstone signal when one passes through. |

### Stone
| Device | Class:line | Notes |
|---|---|---|
| Core Sample | `stone/TileEntityCoresample.java:43` | Displays a placed core-sample item; can mark surveyed coords on a map. |

### Plant
- **Hemp** — `plant/BlockIECrop.java` with metas from `plant/BlockTypes_Hemp.java`. A
  vanilla-style two-tall crop (`BlockBush`/`IGrowable`); top and bottom growth stages,
  drops hemp fiber + seeds. No tile entity.

### Wooden / stone / metal decoration blocks
Crates, barrels, the workbench and windmill *blades* (sails are an item consumed by the
windmill TE, not a block), treated-wood and metal decoration variants are plain
`BlockIEBase` meta-blocks with no TE unless listed above. They derive hardness, render
layer, and harvestability from their enum (`BlockTypes_*`).

---

## 5. Adding a new non-multiblock device

1. Add an enum constant to the relevant `BlockTypes_*` (e.g. `BlockTypes_MetalDevice0`).
2. Create a TE extending `TileEntityIEBase` (or `TileEntityImmersiveConnectable` for a
   wired device), implementing the `IEBlockInterfaces` you need.
3. Return it from the block's `createBasicTE(world, type)` switch
   (e.g. `BlockMetalDevice0.java:84`).
4. If it ticks, implement `ITickable`; **early-out on `world.isRemote`** and on any
   dummy/inactive state before doing world work.
5. Persist via `readCustomNBT` / `writeCustomNBT`, writing a smaller payload when
   `descPacket` is true.
6. For visual state driven by the TE, implement `IActiveState` / `IDirectionalTile` /
   `IAttachedIntegerProperies` so `getActualState` picks it up.

---

## 6. Fluid pipe network (`TileEntityFluidPipe`)

`metal/TileEntityFluidPipe.java:64`

The fluid pipe is **not a ticking machine**. It carries no fluid of its own; transfer is
**push-based**, initiated by whatever fills a pipe's `IFluidHandler` (in practice a fluid
pump or another machine's output).

### Connection model
- `byte connections` is a 6-bit mask of which faces have a valid neighboring fluid handler.
  Recomputed by `updateConnectionByte` on load, on neighbor change, and on hammer toggle.
- `int[6] sideConfig` — `0` = open, `-1` = disabled (hammer-toggled per face).
- Each open face exposes a `PipeFluidHandler` capability (`:354`).

### Transfer path
When something calls `PipeFluidHandler.fill()` (`:372`):

1. The pipe resolves **all reachable output handlers** for its position via
   `getConnectedFluidHandlers(pos, world)` (`:118`).
2. It distributes the incoming fluid across those outputs, weighted by how much each can
   accept, respecting `pipe_transferrate` (and `pipe_transferrate_pressurized` when the
   source is pressurized).

### Network traversal — `getConnectedFluidHandlers`
This is a breadth-first flood fill across connected `TileEntityFluidPipe`s starting from
the filled node (`:118-171`):

- Walks an open/closed list, capped at **1024** pipes (`closedList.size() < 1024`).
- For each pipe, checks all 6 faces; pipe neighbors are enqueued, non-pipe neighbors with
  a fluid-handler capability are collected as `DirectionalFluidOutput`s.
- The resulting set of outputs is **cached in a static map**
  `indirectConnections` (`:68`) keyed by source `BlockPos`, server-side only.

### Cache invalidation
The cache is coarse: it is **entirely cleared** (`indirectConnections.clear()`) whenever:
- any pipe is invalidated / removed (`invalidate()`, `:191-196`), or
- a pipe face is hammer-toggled (`hammerUseSide`, `:1056`).

So a single pipe break anywhere flushes every cached network and forces a full re-flood on
the next fill from any source. See the performance review for the implications.

### Other behavior
- `onEntityCollision` (`:200`) — pipes with a "climbable" cover (scaffold) act as a ladder.
- `getAdvancedCollisionBounds` / `getAdvancedSelectionBounds` (`:570` / `:615`) build
  multi-AABB shapes from the connection mask.
- The OBJ render state (`getStateFromKey`, `:725`) is keyed off the connection byte and
  cached in `cachedOBJStates`, picking pipe/curve/cross/t-junction model parts and rotation.
- Pipes can be dyed and covered with scaffolding blocks (`interact`, `:991`).
