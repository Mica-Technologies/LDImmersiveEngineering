# Fluid System

Technical documentation for Immersive Engineering's fluids — the fluid definitions, the
custom potion fluid, the `IFluidPipe` contract, and how fluid pipes transport and balance
fluid across a network. Citations are `path:line` against real source.

## Overview

IE registers a small set of process fluids and ships a pressurized pipe network for moving
them. Fluids are plain Forge `Fluid`s (one custom subclass for potions), registered in
`common/IEContent.java`. Transport is handled by `TileEntityFluidPipe`, which discovers
endpoints with a cached breadth-first search and load-balances inserted fluid across them.

---

## Fluid definitions

All IE fluids are declared as static fields and registered in
`common/IEContent.java` (`:207`-`:222`) via the helper `setupFluid` (`:476`):

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
against a shared fluid).

| Fluid | Registry name | Class | Density | Viscosity | Block / notes |
|---|---|---|---|---|---|
| Creosote | `creosote` | `Fluid` | 1100 | 3000 | Coke Oven byproduct. Block flammable 40/400 (`:285`); chemthrower-flammable (`:885`) |
| Plant oil | `plantoil` | `Fluid` | 925 | 2000 | Squeezed from seeds (`:442`-`:446`); refined to biodiesel |
| Ethanol | `ethanol` | `Fluid` | 789 | 1000 | Fermented from crops (`:452`-`:455`). Block flammable 60/600 (`:287`) |
| Biodiesel | `biodiesel` | `Fluid` | 789 | 1000 | Refined plantoil+ethanol (`:457`); diesel-generator fuel (`:790`) |
| Concrete | `concrete` | `Fluid` | 2400 | 4000 | Mixed from water+sand+clay+gravel (`:459`); hardens into blocks |
| Potion | `potion` | `IEFluid.FluidPotion` | default | default | Potion-carrying fluid; effects stored in NBT |

Declarations (`IEContent.java:217`-`:222`):

```java
fluidCreosote  = setupFluid(new Fluid("creosote", still, flow).setDensity(1100).setViscosity(3000));   // :217
fluidPlantoil  = setupFluid(new Fluid("plantoil", still, flow).setDensity(925).setViscosity(2000));    // :218
fluidEthanol   = setupFluid(new Fluid("ethanol",  still, flow).setDensity(789).setViscosity(1000));    // :219
fluidBiodiesel = setupFluid(new Fluid("biodiesel",still, flow).setDensity(789).setViscosity(1000));    // :220
fluidConcrete  = setupFluid(new Fluid("concrete", still, flow).setDensity(2400).setViscosity(4000));   // :221
fluidPotion    = setupFluid(new FluidPotion("potion", still, flow));                                    // :222
```

The corresponding fluid blocks are created at `:285`-`:289` (`BlockIEFluid`, plus
`BlockIEFluidConcrete` for the hardening concrete). Flammability and chemthrower effects are
registered later in init (`:804`-`:888`).

> Note: in this version the base process fluids (creosote, plantoil, ethanol, biodiesel,
> concrete) are plain `Fluid` instances; only the potion fluid uses the custom subclass.
> Molten-metal compat fluids (uranium, constantan) are registered separately by the
> TConstruct integration when that mod is present.

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
- `getLocalizedName(FluidStack)` (`:94`) derives the display name from the potion type in
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
(`common/blocks/metal/TileEntityFluidPipe.java:64`).

---

## TileEntityFluidPipe — transport & network discovery

`common/blocks/metal/TileEntityFluidPipe.java`.

### State

- `sideConfig` — `int[6]` per-side connection mode (`:112`).
- `connections` — `byte` bitmask of which sides are physically connected (`:114`).
- `pipeCover`, `color` — cosmetic / colour-filtering.
- `indirectConnections` — a **static** `ConcurrentHashMap<BlockPos, Set<DirectionalFluidOutput>>`:
  the cache mapping a pipe position to all downstream fluid-handler endpoints reachable from
  it.

### Endpoint discovery (BFS) — `getConnectedFluidHandlers`

`:118`. Given a starting pipe `node`, returns the set of `DirectionalFluidOutput`s (an
`IFluidHandler` + the tile + the facing into it) reachable through the connected pipe
network.

1. **Cache hit:** if `indirectConnections.containsKey(node)`, return it immediately (`:120`).
2. Otherwise BFS outward with an `openList`/`closedList` (`:123`-`:161`), bounded to
   **1024 pipe blocks** (`:127`):
   - For the current pipe, iterate all 6 facings; if `hasOutputConnection(facing)` (`:140`):
     - a neighbouring pipe is queued for further traversal (`:145`),
     - a neighbouring non-pipe tile that exposes
       `CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY` with at least one tank is recorded
       as an endpoint (`:147`-`:154`).
3. **Cache fill (server only):** the discovered endpoint set is stored under `node` in
   `indirectConnections` (`:162`-`:168`).

Cache entries are invalidated when a pipe's tile is invalidated (removed/changed) and when a
player toggles a side with the engineer's hammer, so the network re-discovers on the next
fill. Discovery is server-side only.

### Per-side fluid handler & transfer

Each pipe side exposes a `PipeFluidHandler` (inner class). `fill(...)` is the transport
entry point:

1. Calls `getConnectedFluidHandlers` to get all endpoints (cached after the first call).
2. Filters out the source pipe itself and any endpoints in unloaded chunks.
3. **Dry-run pass:** simulate-fills each endpoint to learn how much each can accept.
4. **Load-balancing:** distributes the incoming fluid proportionally to each endpoint's
   tested capacity (`prio = amount / sum`).
5. **Real pass:** performs the actual fills with the computed amounts.

**Transfer rate / pressurization** — `getTranferrableAmount(resource, output)`:

```java
return (resource.tag!=null && resource.tag.hasKey("pressurized"))
        || pipe.canOutputPressurized(output.containingTile, false)
    ? IEConfig.Machines.pipe_transferrate_pressurized
    : IEConfig.Machines.pipe_transferrate;
```

A fluid stack tagged `"pressurized"`, or an output fed by a pump that returns
`canOutputPressurized`, uses the higher `pipe_transferrate_pressurized` cap; everything else
uses the base `pipe_transferrate`. Both are config values.

---

## Performance characteristics (summary)

- **Steady state is cheap:** pipes do **not** scan neighbours every tick. Endpoint
  discovery runs only on a cache miss (first fill after a topology change), and subsequent
  fills are an O(1) `indirectConnections` lookup plus the per-endpoint balancing pass.
- **Discovery cost:** the uncached BFS is bounded at 1024 pipe blocks. Its `openList`/
  `closedList` are `ArrayList`s using linear `contains` checks inside the loop, so a single
  uncached discovery over a large network is super-linear in pipe count — but it only runs
  on cache miss, and the result is cached.
- **Cache correctness depends on invalidation:** the cache is keyed per pipe position and
  cleared on tile invalidate / hammer toggle. A network changed without triggering those
  paths could serve stale endpoints until reloaded; this is a correctness consideration more
  than a per-tick cost.
- The potion fluid's tooltip/name work is client-only and runs only when rendering tooltips.
