# Immersive Engineering — Architecture Overview

Technical documentation for the bootstrap, registration lifecycle, coremod, configuration and
event-handling layers of Immersive Engineering (Minecraft 1.12.2 / Forge).

## Overview

Immersive Engineering is a large Forge content mod. This document covers the *plumbing* that ties
the mod together: the `@Mod` entry point, the sided proxy split, the content registration class, the
coremod (ASM transformer + access transformer), the configuration system, the central event hub, and
a top-level map of where each subsystem lives. The packet/networking layer is documented separately
in `NETWORKING.md`.

The package root is `blusunrize.immersiveengineering`. Top-level split:

- **`api/`** — public-facing API: crafting recipe types, the wire/energy network
  (`api/energy/wires`), Immersive Flux storage (`api/energy/immersiveflux`), shaders (`api/shader`),
  and tool handler registries (`api/tool`).
- **`client/`** — everything `@SideOnly(CLIENT)`: `ClientProxy`, renderers (`client/render`), models
  (`client/models`), GUIs (`client/gui`), the manual (`client/manual`), and particle FX (`client/fx`).
- **`common/`** — server/common logic: the content registry (`IEContent`), blocks
  (`common/blocks`), items (`common/items`), tile-entity containers (`common/gui`), entities
  (`common/entities`), world gen (`common/world`), the coremod (`common/asm`), and a large grab-bag of
  helpers in `common/util` (including `common/util/network`).
- **`ImmersiveEngineering.java`** — the `@Mod` class at the package root.

## Code organization

| Path | Responsibility |
|---|---|
| `ImmersiveEngineering.java` | `@Mod` entry point, lifecycle event handlers, packet channel + registration, creative tab |
| `common/CommonProxy.java` | Server/common proxy; also the `IGuiHandler` for all GUIs |
| `client/ClientProxy.java` | Client proxy: model/renderer/keybinding/TESR registration |
| `client/ClientEventHandler.java` | Client-only `@SubscribeEvent` hub (rendering, input) |
| `common/IEContent.java` | Block/item/tile-entity/fluid registration and content tables |
| `common/IERecipes.java` | Recipe registration |
| `common/EventHandler.java` | The main server/common `@SubscribeEvent` hub |
| `common/Config.java` | Forge config definition (`IEConfig`) + validation/mapping |
| `common/IESaveData.java` | `WorldSavedData` for wire-network persistence |
| `common/NameRemapper.java` | Missing-mapping remap for renamed blocks/items |
| `common/asm/IELoadingPlugin.java` | `IFMLLoadingPlugin` coremod entry |
| `common/asm/IEClassTransformer.java` | Bytecode transformers |
| `src/main/resources/META-INF/ImmersiveEngineering_at.cfg` | Access transformer |
| `common/util/network/` | All packets + `SimpleNetworkWrapper` setup (see `NETWORKING.md`) |

## Bootstrap and the `@Mod` lifecycle

`ImmersiveEngineering` (`ImmersiveEngineering.java:51`) is the mod entry point. Identity constants:
`MODID = "immersiveengineering"`, version is templated, and the class is signed
(`certificateFingerprint` at `ImmersiveEngineering.java:48`). The static initializer enables Forge's
universal bucket (`ImmersiveEngineering.java:65`).

Two singletons are wired at class load:

```java
@Mod.Instance(MODID)                                        // :58
public static ImmersiveEngineering instance = ...;
@SidedProxy(clientSide = "...client.ClientProxy",          // :60
            serverSide = "...common.CommonProxy")
public static CommonProxy proxy;
public static final SimpleNetworkWrapper packetHandler =   // :63
        NetworkRegistry.INSTANCE.newSimpleChannel(MODID);
```

### Lifecycle event flow

```
FML lifecycle           ImmersiveEngineering             proxy (Common/Client)
─────────────           ────────────────────             ─────────────────────
preInit         ──►  Config.preInit()              ──►   proxy.preInit()
                     IEContent.preInit()                 (client: register models/loaders)
                     IEAdvancements.preInit()
                     ore/prefix maps, compat
                     IEContent.preInitEnd()
init            ──►  proxy.preInitEnd()             ──►   proxy.init()
                     IEContent.init()                     (client: TESRs, keybinds, colours)
                     register IEWorldGen
                     register new EventHandler()          proxy.initEnd()
                     register GUI handler
                     packetHandler.registerMessage ×N
                     IEIMCHandler
postInit        ──►  IEContent.postInit()           ──►   proxy.postInit()/postInitEnd()
                     ExcavatorHandler.recalc
loadComplete    ──►  compat loadComplete
serverStarting  ──►  proxy.serverStarting()
                     register CommandHandler
serverStarted   ──►  load IESaveData, clear stale wire connections
```

Key call sites: `preInit` at `ImmersiveEngineering.java:71`, `init` at `:101`, `postInit` at `:146`,
`serverStarting` at `:187`, `serverStarted` at `:194`.

`serverStarted` (`ImmersiveEngineering.java:194`) is where the wire network is rebound to the loaded
world: it clears any stale connections from a previous world via
`ImmersiveNetHandler.INSTANCE.clearAllConnections(dim)` (`:204`) and loads/creates the `IESaveData`
instance (`:206`).

### Contributor downloader

`preInit` spins up `ThreadContributorSpecialsDownloader` (`ImmersiveEngineering.java:271`), a daemon
thread that fetches contributor revolver definitions from GitHub. It is off the main thread and does
not affect tick performance, but it does perform a blocking network read at startup.

## Proxies

`CommonProxy` (`common/CommonProxy.java:54`) is the base proxy and doubles as the mod's
`IGuiHandler`. Most lifecycle hooks (`preInit`, `init`, `postInit`, …) are empty stubs that
`ClientProxy` overrides; the substantive server-side responsibilities are:

- `getServerGuiElement` (`CommonProxy.java:102`) — maps a GUI id to a `Container`. IDs `>= GUIID_Base_Item`
  are item GUIs (revolver/toolbox/maintenance kit) keyed by equipment slot; lower IDs are tile-entity
  GUIs dispatched by `instanceof` checks against the tile at the clicked position.
- `getClientGuiElement` (`CommonProxy.java:169`) returns `null` on the server; the client override
  provides the GUI screens.
- `openGuiForTile` / `openGuiForItem` (`CommonProxy.java:88`, `:93`) — helpers that call
  `EntityPlayer.openGui`.
- `getNameFromUUID` (`CommonProxy.java:243`) — resolves a profile name from the session service
  (used for contributor cosmetics).

The remaining methods are rendering/particle hooks that are no-ops on the server and implemented in
`ClientProxy`.

`ClientProxy` (`client/ClientProxy.java:159`) extends `CommonProxy` and is `@Mod.EventBusSubscriber(Side.CLIENT)`.
It registers: OBJ/custom model loaders and item-model overrides (`preInit`,
`ClientProxy.java:171`), entity renderers, model resource locations (`registerModels`,
`ClientProxy.java:336`), TESRs / keybindings / colour handlers (`init`, `ClientProxy.java:443`), and
the in-game manual content (`postInit`).

## Registration lifecycle (`IEContent`)

`common/IEContent.java` is the content table and the `@Mod.EventBusSubscriber` registry handler.
Blocks and items push themselves into `IEContent.registeredIEBlocks` / `registeredIEItems` as they
are constructed; `ClientProxy.registerModels` (`ClientProxy.java:339`) and the `init` colour-handler
loops (`ClientProxy.java:560`) iterate those lists. This is the canonical "where do I register a new
block/item/tile" location. World generation is registered separately in `init`
(`ImmersiveEngineering.java:105`) via `IEWorldGen`.

## Coremod / ASM

The coremod is declared in the jar manifest (`FMLCorePlugin` →
`blusunrize.immersiveengineering.common.asm.IELoadingPlugin`).

### `IELoadingPlugin` (`common/asm/IELoadingPlugin.java:21`)

A minimal `IFMLLoadingPlugin`. `@SortingIndex(1001)` (`:20`) runs it after the deobfuscation
transformer so it sees mapped names. It declares a single ASM transformer
(`getASMTransformerClass`, `:24`) and **no** access-transformer class here (`:49` returns `null`) —
the access transformer is wired through the manifest's `FMLAT` entry instead (see below).

### `IEClassTransformer` (`common/asm/IEClassTransformer.java:25`)

An `IClassTransformer` driven by a static `transformerMap` of `transformedName → MethodTransformer[]`.
Only two vanilla classes are patched (`transform`, `:77` only rewrites classes present in the map, so
the cost on every other class is a single hash lookup):

1. **`net.minecraft.client.model.ModelBiped#setRotationAngles`** (`IEClassTransformer.java:31`) —
   inserts a call to `ClientUtils.handleBipedRotations(model, entity)` before every `RETURN`. This
   makes IE items (revolver, drill, etc.) appear correctly held in third person. Client-only cosmetic;
   gated by the `fancyItemHolding` config (`Config.java:107`).

2. **`net.minecraft.entity.Entity#doBlockCollisions`** (`IEClassTransformer.java:49`) — after each
   `Block.onEntityCollision` call, inserts a call to
   `ImmersiveNetHandler.handleEntityCollision(pos, entity)` (`:65`). This is how an entity walking into
   a powered wire takes shock damage without IE needing a real block at every catenary voxel. The
   injected static (`ImmersiveNetHandler.handleEntityCollision`, `ImmersiveNetHandler.java:606`)
   bails out immediately on the client, when `enableWireDamage` is off, or for non-living/invulnerable
   entities, so the per-collision overhead is a few branch checks plus a map lookup keyed by block
   position.

Both transformers rewrite via `ClassWriter(COMPUTE_MAXS | COMPUTE_FRAMES)` (`IEClassTransformer.java:91`).

### Access transformer (`META-INF/ImmersiveEngineering_at.cfg`)

Widens visibility of ~30 vanilla fields/methods IE needs to reach by reflection-free direct access.
Notable entries: `Entity.fire` (`:1`), `FontRenderer` internals for the nixie/item fonts (`:5`–`:9`),
`Minecraft.timer` (`:10`), `PlayerControllerMP.isHittingBlock`/`curBlockDamageMP` (`:11`–`:12`),
all `Explosion` fields (`:14`, used by IE's custom explosions), particle motion fields (`:16`–`:18`),
`PotionEffect.duration` (`:22`), and `BlockLiquid.causesDownwardCurrent` (`:24`). Most are client
rendering hooks; the gameplay-relevant ones are the `Explosion`, `PotionEffect`, and `BlockLiquid`
entries.

## Configuration (`Config` / `IEConfig`)

`common/Config.java` uses Forge's annotation-driven config. The nested static class
`IEConfig` (`Config.java:51`) is annotated `@net.minecraftforge.common.config.Config(modid = MODID)`,
so Forge populates its static fields from `config/immersiveengineering.cfg`. `Config` itself is
`@Mod.EventBusSubscriber` (`Config.java:39`) so it receives `ConfigChangedEvent`.

Structure:

- **Top-level wire/render/world fields** directly on `IEConfig` (`Config.java:57`–`:119`):
  `validateConnections`, `wireTransferRate`, `wireLossRatio`, `enableWireDamage`,
  `blocksBreakWires`, render toggles, `preferredOres`, villager toggles, `fancyItemHolding`, etc.
- **`@SubConfig` groups** (`Config.java:121`): `Machines` (connector/capacitor/generator/machine
  energy values, `Config.java:129`), `Ores` (vein generation + retrogen, `Config.java:380`), and
  `Tools` (bullet damage, durabilities, tool whitelists, `Config.java:429`).

### Custom annotations and the sync pipeline

`Config` defines two custom annotations:

- `@Mapped(mapClass, mapName)` (`Config.java:608`) — after load, copies the field's value into a named
  static `Map` so other systems (and the in-game manual) can read it. Wiring happens in
  `validateAndMapValues` (`Config.java:545`).
- `@SubConfig` (`Config.java:617`) — marks a nested config group; `validateAndMapValues` recurses into it.

`validateAndMapValues` (`Config.java:545`) also enforces `@RangeInt` / `@RangeDouble` clamps at load
time. `onConfigUpdate` (`Config.java:506`) is the central "apply config to runtime" routine — it pushes
energy/time modifiers into the recipe classes, sets the festive-season flag, and copies the wire
arrays into `WireType` static fields (`Config.java:538`–`:542`). It runs on `preInit`
(`Config.java:501`) and again whenever `onConfigChanged` fires (`Config.java:621`).

Performance-relevant config knobs:
`validateConnections` (default **false**, `Config.java:57`) gates an expensive world-tick scan (see
below); `enableWireDamage` (`Config.java:71`) gates the coremod collision hook; `blocksBreakWires`
(`Config.java:74`) decides whether the `ImmersiveNetHandler.LISTENER` world listener is attached.

## Event handling (`common/EventHandler`)

`common/EventHandler.java:119` is the central server/common `@SubscribeEvent` hub, instantiated and
registered in `init` (`ImmersiveEngineering.java:109`). It holds several static collections used as
cross-system work queues:

- `interdictionTiles` (`EventHandler.java:121`) — registered spawn-blockers, scanned on spawn/teleport.
- `currentExplosions` (`:123`) — IE explosions ticked from the world-tick handler.
- `requestedBlockUpdates` (`:124`) — a queue drained on world tick (fed by `MessageRequestBlockUpdate`).
- `REMOVE_FROM_TICKING` (`:125`) — tiles to detach from the world ticking list.

### Notable handlers

| Handler | Event | Purpose |
|---|---|---|
| `onLoad` (`:127`) | `WorldEvent.Load` | Attaches `ImmersiveNetHandler.LISTENER` if `blocksBreakWires` |
| `onSave`/`onUnload` (`:159`/`:165`) | `WorldEvent.Save/Unload` | Mark `IESaveData` dirty |
| `onCapabilitiesAttachEntity` (`:171`) | `AttachCapabilitiesEvent<Entity>` | Shader cap on minecarts, skyhook cap on players |
| `onCapabilitiesAttachItem` (`:182`) | `AttachCapabilitiesEvent<ItemStack>` | Fluid-handler cap on potions |
| `onMinecartUpdate` (`:298`) | `MinecartUpdateEvent` | Shader trail FX every 3rd tick |
| `lootLoad` (`:323`) | `LootTableLoadEvent` | Injects IE loot into vanilla tables (reflection on `LootPool`) |
| `onWorldTick` (`:367`) | `WorldTickEvent` | Wire validation, over-transfer burnout, explosion/block-update queues |
| `onLogin` (`:475`) | `PlayerLoggedInEvent` | Sends full mineral list to the joining client |
| `onLivingHurt`/`onLivingDrops`/… | living events | Potion effects, crusher drops, shader bags |
| `onEnderTeleport`/`onEntitySpawnCheck` (`:634`/`:665`) | teleport/spawn | Spawn-interdiction enforcement |
| `digSpeedEvent` (`:699`) | `PlayerEvent.BreakSpeed` | Drill/razor-wire/entity-proof mining rules |
| `remap` (`:773`) | `RegistryEvent.MissingMappings` | Delegates to `NameRemapper` |

### The world-tick handler in detail

`onWorldTick` (`EventHandler.java:367`) is the single most performance-sensitive `@SubscribeEvent` in
the mod and runs once per dimension per tick. It does three things:

1. **START phase, server, gated by `validateConnsNextTick`** (`:370`) — a one-shot full scan of every
   wire connection and proxy in every relevant dimension, dropping invalid ones. Only runs the tick
   after `validateConnsNextTick` is set, and only does real work if `validateConnections` is enabled.
2. **END phase, server** (`:425`) — iterates the per-dimension `transferPerTick` map; any connection
   that exceeded its cable transfer rate is burned out (particles + `removeConnection`), then
   `getTransferedRates(dim).clear()` resets the map for next tick. Also flushes `REMOVE_FROM_TICKING`.
3. **START phase, any side** (`:449`) — ticks active `currentExplosions` and drains
   `requestedBlockUpdates`, issuing a `notifyBlockUpdate` per queued position.

Client-only events (rendering, key input, failed-connection overlays) live in
`client/ClientEventHandler.java`, registered from `ClientProxy.init` (`ClientProxy.java:446`).

## Persistence (`IESaveData`)

`common/IESaveData.java` is the `WorldSavedData` that serializes the wire network
(`ImmersiveNetHandler` connections and proxies). It is loaded in `serverStarted`
(`ImmersiveEngineering.java:206`) and marked dirty from many network-mutating call sites via
`IESaveData.setDirty(dim)` (e.g. `ImmersiveNetHandler.java:110`).

## Top-level package map

```
blusunrize.immersiveengineering
├── ImmersiveEngineering.java        @Mod entry, lifecycle, packet channel
├── api/                             public API
│   ├── crafting/                    recipe types (ArcFurnace, Crusher, Mixer, …)
│   ├── energy/
│   │   ├── wires/                   wire network: ImmersiveNetHandler, Connection, IIC
│   │   └── immersiveflux/           Immersive Flux storage + interfaces
│   ├── fluid/                       IFluidPipe
│   ├── shader/                      shader registry + capability
│   └── tool/                        handler registries (Excavator, Belljar, Conveyor, …)
├── client/                          CLIENT-only
│   ├── ClientProxy.java             model/renderer/keybind/TESR registration
│   ├── ClientEventHandler.java      client @SubscribeEvent hub
│   ├── fx/  gui/  manual/  models/  render/
├── common/                          server/common
│   ├── CommonProxy.java             base proxy + IGuiHandler
│   ├── IEContent.java               block/item/tile/fluid registration
│   ├── IERecipes.java   IESaveData.java   NameRemapper.java
│   ├── Config.java                  IEConfig
│   ├── EventHandler.java            main @SubscribeEvent hub
│   ├── asm/                         coremod (IELoadingPlugin, IEClassTransformer)
│   ├── blocks/                      blocks + tile entities (machines, wires, multiblocks)
│   ├── crafting/  datafixers/  entities/  gui/  items/  world/
│   └── util/                        helpers
│       └── network/                 packets + SimpleNetworkWrapper (see NETWORKING.md)
└── (resources) META-INF/ImmersiveEngineering_at.cfg   access transformer
```

## How to extend

- **Add a block/item/tile**: construct it in `IEContent` so it lands in `registeredIEBlocks` /
  `registeredIEItems`; the client model/colour loops pick it up automatically.
- **Add a GUI**: assign a `GUIID_*` constant in `api/Lib`, branch on it in
  `CommonProxy.getServerGuiElement` (`CommonProxy.java:102`) and the client override.
- **Add a config option**: add a static field to `IEConfig` (or a `@SubConfig` group) with `@Comment`;
  if other systems must read it, add `@Mapped` and wire the target map in `onConfigUpdate`
  (`Config.java:506`).
- **Add an event hook**: add an `@SubscribeEvent` method to `EventHandler` (server/common) or
  `ClientEventHandler` (client). Avoid heavy work in high-frequency events — see the performance notes
  on `onWorldTick`.
- **Add a coremod patch**: append a `MethodTransformer` to the `transformerMap` static block in
  `IEClassTransformer` (`IEClassTransformer.java:29`). Keep the patched-class set minimal — every
  loaded class pays a map lookup.
- **Add a packet**: see `NETWORKING.md`.
