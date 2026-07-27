# Immersive Engineering (1.12.2) — Developer Documentation

Technical documentation for the Forge 1.12.2 line of Immersive Engineering, organized one
file per system. It is written for developers and tooling that need to understand what code
implements which system and how those systems behave. All entries cite real source as
`path:line`.

> Original mod by **BluSunrize** & **Damien A.W. Hazard**. This documentation covers the
> 1.12.2 codebase as maintained in this fork. See the repository [`LICENSE`](../LICENSE).

## Start here

- **[ARCHITECTURE_OVERVIEW.md](ARCHITECTURE_OVERVIEW.md)** — mod bootstrap, sided proxies,
  `IEContent` registration lifecycle, the coremod (`IELoadingPlugin`) + access transformer,
  config, the `EventHandler` hub, and a top-level package map. Read this first.

## Core systems

- **[WIRE_AND_ENERGY_NETWORK.md](WIRE_AND_ENERGY_NETWORK.md)** — the connection/wire graph,
  `ImmersiveNetHandler`, per-tick energy distribution, path-finding, Immersive Flux (IF)
  energy, and connection persistence across chunk load/unload.
- **[CITY_MODE_AND_PERF.md](CITY_MODE_AND_PERF.md)** — **start here for performance.** Measured
  recommended configurations (the headline: `enableWireDamage=false` cuts IE's server CPU ~60%
  while keeping all the physics), every perf-relevant config knob, what the fork already optimises
  for free, and the full reference for this fork's config-gated "city mode".
  ([PDF summary](CITY_MODE_AND_PERF.pdf))
- **[VIRTUAL_GRID.md](VIRTUAL_GRID.md)** — this fork's virtual power grid: named segments of Feed
  and Service Units that move flux with no wire between them, failover chains, breakers, schedules,
  Signal Units, and the Grid Management Console.
- **[PETROLEUM.md](PETROLEUM.md)** — this fork's petroleum system: deterministic, retrogen-free
  oil reservoirs, the Wellhead/Drilling Derrick/Pumpjack progression, core-sample prospecting, the
  crude-oil distillation recipe, and the still-stub Distillation Tower and Industrial Burner.
- **[MULTIBLOCK_SYSTEM.md](MULTIBLOCK_SYSTEM.md)** — the multiblock structure framework
  (master/slave/mirror), formation/disassembly, and the machine catalog (crusher, blast
  furnace, arc furnace, assembler, refinery, etc.).
- **[TILE_ENTITIES_AND_DEVICES.md](TILE_ENTITIES_AND_DEVICES.md)** — block/TE base classes,
  the `IEBlockInterfaces` contracts, the ticking model, the non-multiblock device catalog,
  and the fluid-pipe network.
- **[CONVEYORS.md](CONVEYORS.md)** — the `ConveyorHandler` registry and conveyor belt types.
- **[ENTITIES.md](ENTITIES.md)** — projectiles (revolver/railgun/chemthrower), the skyhook
  hook, explosives, and other custom entities.

## Content & data

- **[CRAFTING_AND_RECIPES.md](CRAFTING_AND_RECIPES.md)** — `IngredientStack`,
  `ComparableItemStack`, and every machine recipe type and how matching works.
- **[ITEMS_AND_TOOLS.md](ITEMS_AND_TOOLS.md)** — the item/tool hierarchy, energy-item
  integration, drill heads, revolver perks, and upgrades.
- **[SHADERS.md](SHADERS.md)** — the shader registry, shader cases/layers, and application.
- **[FLUIDS.md](FLUIDS.md)** — IE fluids and the fluid-handling helpers.
- **[WORLD_GEN.md](WORLD_GEN.md)** — ore generation, the excavator mineral-vein system, and
  retrogen.

## Client & integration

- **[CLIENT_RENDERING.md](CLIENT_RENDERING.md)** — the model pipeline (OBJ/smart/multilayer),
  the TESRs, and particle effects.
- **[NETWORKING.md](NETWORKING.md)** — the packet system: every message, its direction,
  payload, and send sites.
- **[MANUAL.md](MANUAL.md)** — the in-game Engineer's Manual framework.
- **[COMPAT.md](COMPAT.md)** — cross-mod integrations (JEI, OpenComputers, CraftTweaker,
  TheOneProbe, Waila, Tinkers, IC2, etc.).
- **[UTILITIES.md](UTILITIES.md)** — shared helpers (`Utils`, `ItemNBTHelper`), commands,
  advancements, and data fixers.
