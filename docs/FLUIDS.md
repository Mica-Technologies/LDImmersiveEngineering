# Fluid System

Technical documentation for Immersive Engineering's fluids — the fluid definitions, the
custom potion fluid, the `IFluidPipe` contract, and how fluid pipes transport and balance
fluid across a network. Citations are `path:line` against real source.

## Overview

IE registers nine process fluids and ships a pressurized pipe network for moving them. Fluids
are plain Forge `Fluid`s (one custom subclass for potions), registered in
`common/IEContent.java`. Transport is handled by `TileEntityFluidPipe`, which discovers
endpoints with a cached breadth-first search and load-balances inserted fluid across them.

---

## Fluid definitions

All IE fluids are declared as static fields (`:214`-`:223`) and registered in the static
initializer of `common/IEContent.java` (`:227`-`:238`) via the helper `setupFluid` (`:504`):

```java
public static Fluid setupFluid(Fluid fluid) {
    FluidRegistry.addBucketForFluid(fluid);
    if(!FluidRegistry.registerFluid(fluid))
        return FluidRegistry.getFluid(fluid.getName());  // already registered by another mod
    return fluid;
}
```

`setupFluid` also creates a universal bucket entry, and gracefully yields to an existing
registration if another mod already owns that fluid name (so IE's recipes still work
against a shared fluid) — see the `ie_crude_oil` note below for why that matters for a new
fluid's *own* properties, not just compatibility.

| Fluid | Registry name | Class | Density | Viscosity | Block / notes |
|---|---|---|---|---|---|
| Creosote | `creosote` | `Fluid` | 1100 | 3000 | Coke Oven byproduct. Block flammable 40/400 (`:303`); chemthrower-flammable (`:929`) |
| Plant oil | `plantoil` | `Fluid` | 925 | 2000 | Squeezed from seeds (`:464`-`:468`); refined to biodiesel |
| Ethanol | `ethanol` | `Fluid` | 789 | 1000 | Fermented from crops (`:474`-`:477`). Block flammable 60/600 (`:305`); chemthrower-flammable (`:932`) |
| Biodiesel | `biodiesel` | `Fluid` | 789 | 1000 | Refined plantoil+ethanol (`:483`); Diesel Generator fuel (`:823`) |
| Propane | `propane` | `Fluid` | 493 | 800 | Pressurised liquid (LPG). Fractionated from natural gas in the Refinery (`:485`); Diesel Generator fuel (`:828`); block flammable 80/400 (`:307`), chemthrower-flammable (`:933`) |
| Natural gas | `natural_gas` | `Fluid` | 450 | 600 | Pressurised liquid (LNG). Fermented from rotten flesh/leaves (`:479`-`:480`); Diesel Generator fuel (`:827`); block flammable 80/500 (`:308`), chemthrower-flammable (`:934`) |
| Crude oil | `ie_crude_oil` | `Fluid` | 1050 | 3500 | `ie_`-prefixed — see note below. Drawn from oil reservoirs (`api/petroleum/`, below); Diesel Generator fuel at a deliberately punitive rate (`:831`); block flammable 30/400 (`:310`) |
| Concrete | `concrete` | `Fluid` | 2400 | 4000 | Mixed from water+sand+clay+gravel (`:487`); hardens into blocks |
| Potion | `potion` | `IEFluid.FluidPotion` | default | default | Potion-carrying fluid; effects stored in NBT |

Declarations (`IEContent.java:227`-`:238`; texture `ResourceLocation`s omitted for brevity):

```java
fluidCreosote  = setupFluid(new Fluid("creosote", ..., ...).setDensity(1100).setViscosity(3000));    // :227
fluidPlantoil  = setupFluid(new Fluid("plantoil", ..., ...).setDensity(925).setViscosity(2000));     // :228
fluidEthanol   = setupFluid(new Fluid("ethanol",  ..., ...).setDensity(789).setViscosity(1000));     // :229
fluidBiodiesel = setupFluid(new Fluid("biodiesel",..., ...).setDensity(789).setViscosity(1000));     // :230
fluidPropane   = setupFluid(new Fluid("propane",  ..., ...).setDensity(493).setViscosity(800));      // :232
fluidNaturalGas= setupFluid(new Fluid("natural_gas",..., ...).setDensity(450).setViscosity(600));    // :233
fluidCrudeOil  = setupFluid(new Fluid("ie_crude_oil",..., ...).setDensity(1050).setViscosity(3500)); // :236
fluidConcrete  = setupFluid(new Fluid("concrete", ..., ...).setDensity(2400).setViscosity(4000));    // :237
fluidPotion    = setupFluid(new FluidPotion("potion", ..., ...));                                     // :238
```

The corresponding fluid blocks (`BlockIEFluid`, plus `BlockIEFluidConcrete` for the hardening
concrete) are created at `:303`-`:311`. Flammability and chemthrower effects are registered
later in init (`:823`-`:946`).

> **Why `ie_crude_oil` is prefixed and the other eight aren't:** `setupFluid` yields to
> whoever registered a name first (`:507`-`:508`), so claiming a bare, generic name like
> `crude_oil` risks silently adopting *another* mod's registration — and inheriting its
> density, viscosity and texture — instead of IE's own (`:234`-`:236`). The eight older
> fluids stay unprefixed because they're already in existing saves; renaming them would
> orphan placed fluid blocks and stored buckets.

> Note: all eight base process fluids (creosote, plantoil, ethanol, biodiesel, propane,
> natural gas, crude oil, concrete) are plain `Fluid` instances; only the potion fluid uses
> the custom `IEFluid` subclass. Molten-metal compat fluids (uranium, constantan) are
> registered separately by the TConstruct integration when that mod is present.

### Diesel Generator fuel values

`api/energy/DieselHandler.java` is the fuel registry for the Diesel Generator (and, via
`registerDrillFuel`, the excavator's drill). `registerFuel(fluid, time)` (`:33`-`:37`) maps a
fluid to "total burn time gained from 1000 mB" — **higher is better**: it burns longer per
bucket, i.e. more efficiently. Registered in `IEContent.java` (`:823`-`:831`):

| Fluid | Burn time (per 1000 mB) |
|---|---|
| `"fuel"` (another mod's) | 375 |
| `"diesel"` (another mod's) | 175 |
| Propane | 250 |
| Natural gas | 200 |
| Biodiesel | 125 |
| Crude oil | 50 |

Crude's value is deliberately low — well under half of biodiesel's — so burning it raw always
reads as the wasteful option next to refining it into propane. `"fuel"` and `"diesel"` are
resolved via `FluidRegistry.getFluid(...)` (`:824`-`:825`) rather than an IE fluid field,
since they come from other mods when present.

### Oil reservoirs (source of crude oil)

Crude oil isn't crafted or fermented — it's drawn from **oil reservoirs**, a virtual
per-cell deposit system in `api/petroleum/`. Modelled on `ExcavatorHandler`: nothing is
placed underground and nothing runs at chunk-gen time, so a cell is rolled deterministically
from the world seed the first time anything asks about it (`ReservoirHandler.getReservoir`,
`api/petroleum/ReservoirHandler.java:174`-`:188`). That means existing worlds get oil for
free, with no retrogen step.

- **`ReservoirHandler`** owns the type registry and the per-cell map. Deposits roll per
  `PetroleumConfig.cellChunkSize`-chunk cell (default 8×8), not per chunk, so a field covers
  a believable area. `registerDefaults()` (`:103`-`:108`) installs the single built-in type,
  named `crude_oil`, that yields `ie_crude_oil`.
- **`ReservoirModel`** is the pure decline-curve math (`api/petroleum/ReservoirModel.java`):
  capacity rolls log-distributed between `PetroleumConfig.minCapacity`/`maxCapacity` so modest
  fields are common (`:52`-`:60`); a deposit above `freeFlowThreshold` (default 60%) flows at
  `peakFlowRate` unpumped, below it a pump is required and the rate declines linearly toward a
  `residualFlowRate` floor rather than stopping dead (`:70`-`:109`).
- **`PetroleumConfig`** holds the tunable defaults (`api/petroleum/PetroleumConfig.java`), and
  a master `enabled` switch.
- City mode (`CityMode.petroleum()`) makes extraction lossless: the flow rate is still
  computed from the deposit's pressure, but nothing is actually removed from the pool
  (`ReservoirModel.extract`, `:126`-`:171`).

As of this snapshot there is no in-world extraction block wired up to `ReservoirHandler` —
the doc comments in `ReservoirModel` describe "extraction blocks" as thin callers, but the
only caller in the source tree is `common/util/commands/CommandReservoir.java`, the
`/ie reservoir` admin/debug command (`info`, `types`, `deplete`, `refill`) used to inspect and
recover deposits. Treat the reservoir system as backend-complete but not yet exposed to
survival play.

---

## IEFluid and the potion fluid

`common/util/IEFluid.java:38` is a thin `Fluid` subclass that adds one client hook:

```java
public class IEFluid extends Fluid {
    @SideOnly(Side.CLIENT)
    public void addTooltipInfo(FluidStack fluidStack, EntityPlayer player, List<String> tooltip) {}
}
```

`IEFluid.FluidPotion` (inner class, `:50`) is the only fluid that carries data. The potion
type and effects live in the **FluidStack's NBT tag**, and the fluid renders/labels itself
from that tag:

- `addTooltipInfo` (`:59`) reads `PotionUtils.getEffectsFromTag(fluidStack.tag)` and lists
  each effect (red for harmful, blue for beneficial), plus the source-mod note via
  `PotionUtils.getPotionTypeFromNBT`.
- `getLocalizedName(FluidStack)` (`:95`) derives the display name from the potion type in
  the tag.
- `getColor`/colour is taken from the potion's effect list.

### Constructing a potion fluid stack

`common/crafting/MixerPotionHelper.java` builds potion fluid stacks and registers the
brewing recipes. `getFluidStackForType(PotionType, amount)`:

- Water potions collapse to plain `FluidRegistry.WATER`.
- Otherwise a `FluidStack(IEContent.fluidPotion, amount)` is created and its `tag` gets a
  `"Potion"` string key set to the potion type's registry name.

`registerPotionRecipe(...)` wires this into a `MixerRecipe` (to brew the potion fluid) and a
`BottlingMachineRecipe` (to bottle it back into a potion item). So the Mixer "brews" by
producing a `fluidPotion` stack tagged with the target potion, and the Bottling Machine
reverses it. See CRAFTING_AND_RECIPES.md for the recipe types themselves.

---

## IFluidPipe contract

`api/fluid/IFluidPipe.java:13`:

```java
public interface IFluidPipe {
    boolean canOutputPressurized(boolean consumePower);
    boolean hasOutputConnection(EnumFacing side);
}
```

- `hasOutputConnection(side)` — is this side an enabled output? Used during network
  traversal to decide which neighbours to follow.
- `canOutputPressurized(consumePower)` — may this pipe push at the higher pressurized
  transfer rate (optionally consuming power to do so)? Pumps implement this to grant
  pressurization to a sub-network.

The implementation is `TileEntityFluidPipe`
(`common/blocks/metal/TileEntityFluidPipe.java:65`).

---

## TileEntityFluidPipe — transport & network discovery

`common/blocks/metal/TileEntityFluidPipe.java`.

### State

- `sideConfig` — `int[6]` per-side connection mode (`:138`).
- `connections` — `private byte` bitmask of which sides are physically connected (`:140`).
- `pipeCover`, `color` — cosmetic / colour-filtering.
- `indirectConnections` — a **static** `ConcurrentHashMap<DimensionBlockPos, Set<DirectionalFluidOutput>>`
  (`:74`): the cache mapping a pipe position to all downstream fluid-handler endpoints
  reachable from it. Keyed by `DimensionBlockPos`, not plain `BlockPos` — the map is static
  and shared by every world, so a bare position let a pipe at the same coordinates in two
  dimensions collide on one cache entry, with whichever dimension populated it first winning
  and the other routing its fluid into the wrong world's tile entities (`:69`-`:73`).

### Endpoint discovery (BFS) — `getConnectedFluidHandlers`

`:144`. Given a starting pipe `node`, returns the set of `DirectionalFluidOutput`s (an
`IFluidHandler` + the tile + the facing into it) reachable through the connected pipe
network.

1. **Cache hit:** if `indirectConnections` has an entry for this position+dimension, return it
   immediately (`:146`-`:149`).
2. Otherwise BFS outward with an `openList`/`closedList` (`:151`-`:189`), bounded to
   **1024 pipe blocks** (`:155`):
   - For the current pipe, iterate all 6 facings; if `hasOutputConnection(facing)` (`:168`):
     - a neighbouring pipe is queued for further traversal (`:174`),
     - a neighbouring non-pipe tile that exposes
       `CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY` with at least one tank is recorded
       as an endpoint (`:175`-`:184`).
3. **Cache fill (server only):** the discovered endpoint set is stored under the
   `DimensionBlockPos` key in `indirectConnections` (`:190`-`:198`). The BFS walk itself isn't
   side-gated — only the caching of its result is — so a hypothetical client-side call would
   always re-walk instead of populating a cache.

**Invalidation is a batched full clear, not a per-entry one.** A pipe's `invalidate()`
(`:220`-`:225`) and the engineer's hammer (`hammerUseSide`, `:1085`) both just flip a static
dirty flag via `markIndirectConnectionsDirty()` (`:82`-`:85`); they do not touch
`indirectConnections` directly. `EventHandler.onWorldTick` calls
`TileEntityFluidPipe.flushDirtyCache()` once per server tick (`common/EventHandler.java:378`),
which drops the *entire* map if the flag is set (`:87`-`:94`). This coalesces a burst of pipe
edits (placement, breaking, hammer toggles, chunk streaming) into at most one re-flood per
tick instead of a targeted clear per edit — simpler than per-position invalidation, at the
cost of every pipe's cache being dropped even when only one changed. Worst case a route is one
tick stale, which self-corrects on the next fill.

### Per-side fluid handler & transfer

Each pipe side exposes a `PipeFluidHandler` (inner class, `:383`). `fill(...)` (`:400`-`:459`)
is the transport entry point:

1. Calls `getConnectedFluidHandlers` to get all endpoints (cached after the first call)
   (`:410`).
2. Filters out the source pipe itself and any endpoints in unloaded chunks (`:421`).
3. **Dry-run pass:** simulate-fills (`doFill=false`) each endpoint to learn how much each can
   accept (`:423`-`:430`).
4. **Load-balancing:** if the summed dry-run capacity exceeds the offered amount, distributes
   it proportionally to each endpoint's tested capacity (`prio = amount / sum`, `:439`-`:446`).
5. **Real pass:** fills each endpoint with the computed amount, passing through the caller's
   `doFill` (`:448`).

**Transfer rate / pressurization** — `getTranferrableAmount(resource, output)` (`:461`-`:466`):

```java
return (resource.tag!=null&&resource.tag.hasKey("pressurized"))||
        pipe.canOutputPressurized(output.containingTile, false)
    ?IEConfig.Machines.pipe_transferrate_pressurized: IEConfig.Machines.pipe_transferrate;
```

A fluid stack tagged `"pressurized"`, or an output fed by a pump that returns
`canOutputPressurized`, uses the higher `pipe_transferrate_pressurized` cap; everything else
uses the base `pipe_transferrate`. Both are config values (`common/Config.java:348` and
`:351`), defaulting to 50 and 1000 respectively.

---

## Performance characteristics (summary)

- **Steady state is cheap:** pipes do **not** scan neighbours every tick. Endpoint
  discovery runs only on a cache miss (first fill after a topology change), and subsequent
  fills are an O(1) `indirectConnections` lookup plus the per-endpoint balancing pass.
- **Discovery cost:** the uncached BFS is bounded at 1024 pipe blocks. Its `openList`/
  `closedList` are `ArrayList`s using linear `contains` checks inside the loop, so a single
  uncached discovery over a large network is super-linear in pipe count — but it only runs
  on cache miss, and the result is cached.
- **Invalidation is coalesced, not targeted:** a pipe edit or hammer toggle doesn't clear its
  own cache entry — it sets a dirty flag, and `EventHandler.onWorldTick` drops the *whole*
  static `indirectConnections` map at most once per server tick
  (`TileEntityFluidPipe.flushDirtyCache`, `common/blocks/metal/TileEntityFluidPipe.java:87`-`:94`).
  That means one pipe changing forces every pipe network in every loaded world to re-flood on
  its next fill, not just the affected one — a correctness-safe but blunt trade for avoiding a
  clear-per-edit. A network changed without triggering `invalidate()`/the hammer path could
  still serve stale endpoints until something else marks the cache dirty.
- The potion fluid's tooltip/name work is client-only and runs only when rendering tooltips.
