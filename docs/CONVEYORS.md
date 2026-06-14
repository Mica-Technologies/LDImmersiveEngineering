# Conveyor System

Technical documentation for the conveyor belt subsystem in Immersive Engineering (Forge 1.12.2).

## Overview

Conveyor belts move dropped `EntityItem`s (and push other entities) across the world. The
system is built around a single block / tile entity pair plus a registry of pluggable
*conveyor subtypes* (basic, dropper, vertical, splitter, extract, covered, chute, ...). All
conveyors share one block (`ConveyorHandler.conveyorBlock`) and one tile entity
(`TileEntityConveyorBelt`); the behavioural differences live entirely in an `IConveyorBelt`
subtype instance held by the tile.

Code lives in:
- `api/tool/ConveyorHandler.java` - registry + the `IConveyorBelt` / `IConveyorTile` interfaces
- `common/blocks/metal/TileEntityConveyorBelt.java` - the tile entity
- `common/blocks/metal/conveyors/` - the subtype implementations

## Architecture

```
  ConveyorHandler                 (registry: ResourceLocation -> class + factory function)
        |
        | getConveyor(key, tile)  -> new IConveyorBelt
        v
  TileEntityConveyorBelt          (one TE for every conveyor; holds `facing` + a subtype)
        |  conveyorBeltSubtype
        v
  IConveyorBelt subtype           (ConveyorBasic, ConveyorVertical, ConveyorSplit, ...)
        ^
        |  onEntityCollision(tile, entity, facing)   <- vanilla per-entity callback
  EntityItem / players / mobs walking onto the belt
```

### The registry

`ConveyorHandler` keeps four parallel maps (`ConveyorHandler.java:47-52`):
- `classRegistry` - key -> subtype class
- `functionRegistry` - key -> `Function<TileEntity, IConveyorBelt>` factory
- `reverseClassRegistry` - subtype class -> key (used for NBT save and model cache keys)
- `substituteRegistry` - allowed multiblock substitutes for a key

Subtypes are registered at startup with `registerConveyorHandler(key, class, factory)`
(`ConveyorHandler.java:62`). The factory receives the (possibly null) tile entity, so a
subtype like `ConveyorSplit` / `ConveyorExtract` can read the tile's facing at creation
(`IEContent.java:495-509`). Built-in keys: `conveyor`, `uncontrolled`, `dropper`,
`vertical`, `splitter`, `extract`, `covered`, `droppercovered`, `verticalcovered`,
`extractcovered`, and four metal `chute_*` variants.

`getConveyor(key, tile)` (`ConveyorHandler.java:86`) looks up the factory and builds an
instance. `getConveyorStack(key)` (`ConveyorHandler.java:97`) produces a placed item stack
carrying the type in NBT under `conveyorType`.

### The `IConveyorBelt` interface

`IConveyorBelt` (`ConveyorHandler.java:164`) is the heart of the system. Notable members:
- `getConveyorDirection()` - `HORIZONTAL`, `UP`, or `DOWN` (`ConveyorHandler.java:185`)
- `getDirection(tile, entity, facing)` - returns the velocity `Vec3d` applied to an entity
  (`ConveyorHandler.java:297`)
- `onEntityCollision(tile, entity, facing)` - the per-entity movement tick
  (`ConveyorHandler.java:333`)
- `handleInsertion(...)` - inserts a carried item into a target inventory at the belt end
  (`ConveyorHandler.java:385`)
- `isActive(tile)` - redstone gating (`ConveyorHandler.java:214`)
- `isTicking(tile)` / `onUpdate(tile, facing)` - opt-in per-tick server logic
  (`ConveyorHandler.java:409`, `:414`)
- `writeConveyorNBT()` / `readConveyorNBT(nbt)` - persistence (`ConveyorHandler.java:431`)
- model/selection helpers: `getModelCacheKey`, `renderWall`, `getSelectionBoxes`,
  `getColisionBoxes`, `modifyQuads` (client only)

Two marker interfaces sit alongside it:
- `IConveyorAttachable` (`ConveyorHandler.java:470`) - exposes `getFacing()` and
  `sigOutputDirections()`, so neighbouring conveyors know whether to draw a side wall.
- `IConveyorTile extends IConveyorAttachable` (`ConveyorHandler.java:483`) - marks a tile as
  a conveyor (so it is *skipped* for item insertion) and bridges to its subtype.

## The tile entity

`TileEntityConveyorBelt` (`TileEntityConveyorBelt.java:51`) implements a large pile of IE
interfaces but is mostly a thin shell around `conveyorBeltSubtype`:

- `facing` (an `EnumFacing`) and `conveyorBeltSubtype` (`IConveyorBelt`) are the only real
  state (`TileEntityConveyorBelt.java:53-54`).
- NBT: `writeCustomNBT` stores `facing`, the subtype key (looked up via
  `reverseClassRegistry`) and the subtype's own NBT blob
  (`TileEntityConveyorBelt.java:91-99`). `readCustomNBT` reconstructs the subtype with
  `ConveyorHandler.getConveyor` (`TileEntityConveyorBelt.java:77-88`).
- `onEntityCollision(world, entity)` simply forwards to the subtype
  (`TileEntityConveyorBelt.java:70-74`).
- Item-handler capability: the tile exposes a `CapabilityItemHandler` on all sides
  (`TileEntityConveyorBelt.java:251-266`). The handler is a one-slot, insert-only
  `ConveyorInventoryHandler` (`TileEntityConveyorBelt.java:306`) whose `insertItem` *spawns
  a new `EntityItem`* at the belt centre (Y offset 0.1875) rather than storing anything
  (`TileEntityConveyorBelt.java:328-341`). Extraction always returns empty.

### How an item gets onto a belt

There are three entry points:
1. **Dropped into the world** - any `EntityItem` that falls onto the belt is caught by the
   collision callback below.
2. **Pushed by a pipe/hopper into the capability** - `ConveyorInventoryHandler.insertItem`
   spawns an `EntityItem` on the belt (`TileEntityConveyorBelt.java:328`).
3. **Extracted from an adjacent inventory** - the `ConveyorExtract` subtype pulls items and
   spawns them (see below).

## How items move each tick

Movement is **not** driven by the tile's `update()` and it does **not** scan for nearby
entities. It is driven by Minecraft's vanilla per-entity block-collision callback:

```
World entity tick
  -> Entity.doBlockCollisions()              (every entity, every tick)
     -> Block.onEntityCollision(world, pos, state, entity)
        -> BlockIETileProvider.onEntityCollision   (BlockIETileProvider.java:730)
           -> TileEntityIEBase.onEntityCollision(world, entity)
              -> TileEntityConveyorBelt.onEntityCollision   (line 70)
                 -> IConveyorBelt.onEntityCollision(tile, entity, facing)
```

So the conveyor only does work when an entity's bounding box actually overlaps the belt
block. The core mover is the default `IConveyorBelt.onEntityCollision`
(`ConveyorHandler.java:333`):

1. Bail out if the belt is inactive, or the entity is dead, a sneaking player, or outside
   the belt's vertical band (height limit `0.25` flat / `1.0` for diagonals,
   `ConveyorHandler.java:339-340`).
2. Compute the target velocity via `getDirection(...)` and overwrite the entity's
   `motionX/Y/Z` (`ConveyorHandler.java:342-347`). `getDirection`
   (`ConveyorHandler.java:297`) pushes along `facing` at a base speed of `0.1 * 1.15`
   blocks/tick, recentres the entity toward the belt's lateral midline, and sets a vertical
   component for `UP` (`+0.17*vBase`) / `DOWN` (`-0.07*vBase`) belts.
3. Zero fall damage while riding (`fallDistance = 0`).
4. Decide `contact` - whether the entity has reached the far edge of the block
   (`ConveyorHandler.java:348-351`).
5. Magnet suppression: items mid-belt get `applyMagnetSupression`; once leaving the belt
   chain, `revertMagnetSupression` restores them (`ConveyorHandler.java:357-364`). This stops
   item-magnet mods from yanking items off belts.
6. For `EntityItem`s older than 1 tick: mid-belt items get their despawn timer pushed to 10
   minutes via `setNoDespawn()`; at the far edge `handleInsertion(...)` runs
   (`ConveyorHandler.java:367-374`).

`handleInsertion` (`ConveyorHandler.java:385`) computes the inventory position one block past
`facing` (offset up/down for diagonal belts), and if a non-conveyor tile with an item handler
is there, calls `ApiUtils.insertStackIntoInventory`; the `EntityItem` is killed if fully
consumed, otherwise its stack is shrunk. This only runs server-side.

### Entity transport

The same `onEntityCollision` path moves *any* entity, not just items - players, mobs, and
minecarts get their motion overwritten, which is how players ride belts. Diagonal belts set
`entity.onGround = false` (`ConveyorHandler.java:312-313`) and `UP` belts give a small
upward nudge / teleport at the lip to help entities climb off
(`ConveyorHandler.java:352-356`).

## Subtype catalogue

### ConveyorBasic
`conveyors/ConveyorBasic.java` - the reference implementation. Supports all three
directions (cycled by `changeConveyorDirection`, `ConveyorBasic.java:32-36`), can be dyed,
and is redstone-gated: `isActive` returns false whenever the block receives any redstone
power (`ConveyorBasic.java:46-49`). All other subtypes extend this.

### ConveyorUncontrolled
Like basic but ignores redstone (always active); used inside multiblocks.

### ConveyorDrop (dropper)
`conveyors/ConveyorDrop.java`. Overrides `handleInsertion` (`ConveyorDrop.java:31`): when the
item reaches the centre it tries to insert *downward* into the tile below; if that space is
empty (air, another conveyor, or an open trapdoor - `isEmptySpace`, `ConveyorDrop.java:65`)
it drops the item straight down through the belt instead of carrying it forward.

### ConveyorVertical
`conveyors/ConveyorVertical.java`. Carries entities straight up a wall. It overrides
`getDirection` (`ConveyorVertical.java:135`) and `onEntityCollision`
(`ConveyorVertical.java:181`) with wall-distance logic: an entity is only lifted while close
to the wall in `facing`. Living entities get a higher base speed (`1.5` vs `1.15`), and a
`2.25x` vertical boost at the top to help dismount (`ConveyorVertical.java:175-176`). At the
top it inserts items into the tile directly above (`ConveyorVertical.java:227-244`).
`sigTransportDirections` reports `{UP, facing}` (`ConveyorVertical.java:129`), and it renders
a "bottom belt" piece when fed by inward horizontal conveyors (`renderBottomBelt`,
`ConveyorVertical.java:76`).

### ConveyorSplit (splitter)
`conveyors/ConveyorSplit.java`. Alternates output between the two perpendicular directions
(`sigTransportDirections` = `{rotateY, rotateYCCW}`, `ConveyorSplit.java:131`). To keep an
item committed to one side, it stamps a per-belt redirect direction onto the item's
`getEntityData()` NBT under a position-hashed key (`ConveyorSplit.java:74`, `:93-99`), then
nudges the item sideways in `getDirection` (`ConveyorSplit.java:137`). The chosen side flips
each time, and it validates the side actually has a downstream conveyor
(`ConveyorSplit.java:100-108`). The redirect tag is removed once the item clears the belt
(`ConveyorSplit.java:118-120`).

### ConveyorExtract (extract)
`conveyors/ConveyorExtract.java`. The only *natively ticking* subtype:
`isTicking` returns true (`ConveyorExtract.java:210`) and `onUpdate`
(`ConveyorExtract.java:216`) pulls items from an adjacent inventory's item handler and spawns
them onto the belt. It enforces a `transferCooldown`/`transferTickrate` (default 8 ticks,
adjustable 4/8/16/20 with a wirecutter, `ConveyorExtract.java:267-278`), is disabled while
powered by redstone (`isPowered`, `ConveyorExtract.java:204`), and renders an "extension"
that visually reaches into the neighbour block (`getExtensionIntoBlock`,
`ConveyorExtract.java:157`).

### Covered variants
`ConveyorCovered`, `ConveyorDropCovered`, `ConveyorVerticalCovered`,
`ConveyorExtractCovered` - behavioural clones of their base types that add a solid top cover
(reflected in `TileEntityConveyorBelt.getFaceShape`, `TileEntityConveyorBelt.java:300-302`)
so entities can walk over them and items are enclosed.

### Chutes
`ConveyorChute` (+ Iron/Steel/Aluminum/Copper subclasses) - vertical drop chutes registered
per metal (`IEContent.java:506-509`).

## Ticking and the "logic dummy" optimisation

Most conveyors do nothing on `update()`; only `ConveyorExtract` opts in via `isTicking`.
IE avoids paying the per-tick cost for the rest:

- `TileEntityConveyorBelt.isLogicDummy()` returns true whenever the subtype is not ticking
  (`TileEntityConveyorBelt.java:145-148`).
- `update()` calls `ApiUtils.checkForNeedlessTicking(this)` first
  (`TileEntityConveyorBelt.java:151-156`), which - for a logic dummy on the server - queues
  the tile into `EventHandler.REMOVE_FROM_TICKING` (`ApiUtils.java:948-952`).
- At the end of each world tick, `EventHandler` bulk-removes those tiles from
  `world.tickableTileEntities` (`EventHandler.java:443-447`), so non-extract belts stop being
  ticked entirely after their first tick.

The cost trade-off: even a logic-dummy belt is still an `ITickable`, so it ticks once before
being removed, and is re-added whenever the chunk reloads.

## Rotation, dyeing, and interaction

- Hammer (non-sneak) rotates the belt facing; hammer + sneak calls
  `changeConveyorDirection` to cycle HORIZONTAL/UP/DOWN (`TileEntityConveyorBelt.java:159`).
  `afterRotation` lets subtypes re-derive state (e.g. splitter output)
  (`TileEntityConveyorBelt.java:138`).
- Right-clicking with a dye recolours dyeable belts; otherwise the click is forwarded to
  `playerInteraction` (`TileEntityConveyorBelt.java:182-203`).
- Wall rendering between adjacent belts is decided by `renderWall`
  (`ConveyorHandler.java:248`), driven by neighbours' `sigOutputDirections()`.

## Adding a new conveyor subtype

1. Implement `IConveyorBelt` (or extend `ConveyorBasic` for sane defaults).
2. Override behaviour: `getDirection` / `onEntityCollision` for movement, `handleInsertion`
   for end-of-belt output, `isTicking` + `onUpdate` if you need server logic.
3. Provide `writeConveyorNBT` / `readConveyorNBT` and the client texture getters.
4. Register it in `IEContent` with
   `ConveyorHandler.registerConveyorHandler(key, YourClass.class, tile -> new YourClass(...))`.
5. Optionally register multiblock substitutes with `ConveyorHandler.registerSubstitute`.
