# Client Rendering System

Technical documentation for the client-side rendering subsystem of Immersive Engineering
(Forge 1.12.2). Covers the custom model pipeline, dynamic procedural models (connectors,
wires, conveyors), the `TileEntitySpecialRenderer` (TESR) family, entity renderers, and
particle effects.

All client rendering code lives under
`src/main/java/blusunrize/immersiveengineering/client/`:

- **`models/`** — `IModel` / `IBakedModel` implementations and custom model loaders.
- **`models/obj/`** — IE's wrapper around Forge's OBJ loader.
- **`models/smart/`** — connector / wire / feedthrough / configurable-side smart models.
- **`models/multilayer/`** — multi-render-layer composite model.
- **`render/`** — every TESR and entity `Render`, plus item/shader render helpers.
- **`fx/`** — particle effects.
- **`ClientProxy.java`** — all client-side registration (loaders, TESRs, entity renderers, colour handlers).

---

## 1. Registration Overview (`ClientProxy.java`)

Everything client-side is wired up in `ClientProxy`. Key registration sites:

| What | Location |
|---|---|
| `IEOBJLoader` registered as a Forge model loader | `ClientProxy.java:178` |
| Forge OBJ loader domain enabled for `immersiveengineering` | `ClientProxy.java:179` |
| IE OBJ loader domain enabled | `ClientProxy.java:180` |
| Entity renderers (`registerEntityRenderingHandler`) | `ClientProxy.java:279-324` |
| `ConnLoader` (connectors/wires) | `ClientProxy.java:327` |
| `FeedthroughLoader` | `ClientProxy.java:328` |
| `ModelConfigurableSides.Loader` | `ClientProxy.java:329` |
| `MultiLayerLoader` | `ClientProxy.java:330` |
| `ConveyorChute.clientInit()` | `ClientProxy.java:331` |
| TESR bindings (`bindTileEntitySpecialRenderer`) | `ClientProxy.java:503-527` |
| Item/block colour handlers | `ClientProxy.java:562, 565` |

Several legacy connector/lantern/pipe TESR bindings are commented out
(`ClientProxy.java:487-502, 529-536`); those subsystems now render through baked smart
models instead of TESRs.

---

## 2. The Model Pipeline

IE plugs into Forge's model system at three levels: a **custom `ICustomModelLoader`**
recognises model resource locations, returns an **`IModel`** that resolves dependencies and
textures, and that model **bakes** into an **`IBakedModel`** whose `getQuads` produces the
geometry the chunk compiler and item renderer consume.

### 2.1 OBJ models — `IEOBJLoader` / `IEOBJModel` / `IESmartObjModel`

IE ships its machines as Wavefront OBJ models and renders them through a customised OBJ
pipeline that supports per-group visibility, shader layers, dynamic texture replacement, and
animated sub-groups.

**`IEOBJLoader`** (`models/obj/IEOBJLoader.java`) is the `ICustomModelLoader`. It accepts any
location in an enabled domain whose path ends in `.obj.ie`
(`IEOBJLoader.java:38-41`). `loadModel` delegates to Forge's `OBJLoader` to parse the file,
then wraps the resulting `OBJModel`'s material library in an `IEOBJModel`
(`IEOBJLoader.java:45-60`). Parsed models are cached in a `Map<ResourceLocation, IEOBJModel>`
(`IEOBJLoader.java:27`); the cache is cleared on resource reload
(`IEOBJLoader.java:62-68`).

**`IEOBJModel`** (`models/obj/IEOBJModel.java`) extends Forge's `OBJModel`. Its `bake` calls
`super.bake` to produce a vanilla `OBJBakedModel`, then wraps that in an `IESmartObjModel`
(`IEOBJModel.java:37-42`). `process` and `retexture` return new `IEOBJModel` instances so the
model stays immutable through the pipeline (`IEOBJModel.java:44-56`). Forge's
`OBJModel.modelLocation` / `customData` fields are private, so `IEOBJModel` reaches them by
reflection (`IEOBJModel.java:58-113`).

**`IESmartObjModel`** (`models/IESmartObjModel.java`) is the workhorse baked model. It extends
`OBJBakedModel` and implements the dynamic behaviour:

- **`getQuads`** (`IESmartObjModel.java:204-268`) is the hot path. For block rendering it reads
  an animation state (`Properties.AnimationProperty` → `OBJState`) and a texture remap
  (`IEProperties.OBJ_TEXTURE_REMAP`) off the extended blockstate, then keys a static
  `modelCache` (`IESmartObjModel.java:65`) on an `ExtBlockstateAdapter`
  (`IESmartObjModel.java:241-261`). On a cache miss it builds quads via `buildQuads` and stores
  the result. The final list is wrapped in `Collections.synchronizedList(Lists.newArrayList(...))`
  (`IESmartObjModel.java:262`).
- **`buildQuads`** (`IESmartObjModel.java:270-317`) resolves a shader (from the item's
  `CapabilityShader` or the blockstate's shader property) and an `IOBJModelCallback`, then emits
  quads group-by-group through `addQuadsForGroup`.
- **`addQuadsForGroup`** (`IESmartObjModel.java:319-452`) is where shader passes, per-group
  visibility, ARGB tinting, texture replacement, transforms, and shader-layer UV remapping are
  all applied. Vertex data is written through `putVertexData` (`IESmartObjModel.java:454-496`),
  which also computes Forge diffuse lighting per vertex.
- **Item models** are produced by the `ItemOverrideList` at `IESmartObjModel.java:137-201`,
  which builds a per-stack textured model (resolving shader sprites and callback texture
  replacements) and caches it in a Guava cache keyed by `ComparableItemStack`
  (`IESmartObjModel.java:63-64`, max 100 entries, 60-second idle expiry).

**`IOBJModelCallback`** (`models/IOBJModelCallback.java`) is the interface a TileEntity (passed
through the extended blockstate as the unlisted `PROPERTY`, `IOBJModelCallback.java:31-56`) or
an `Item` (passed as the stack) implements to customise OBJ rendering: texture replacement,
group visibility, group transforms, render colour, quad post-processing, fullbright groups,
animated "special groups", and a `getCacheKey` hook that feeds the OBJ model cache.

**`ItemRendererIEOBJ`** (`render/ItemRendererIEOBJ.java`) is the built-in item renderer used for
OBJ items that need animated "special groups" and shader layers (revolver, drill, etc.). It
renders the special groups with per-group transform matrices first
(`ItemRendererIEOBJ.java:77-106`), then the remaining visible groups with shader-layer passes
(`ItemRendererIEOBJ.java:130-163`).

### 2.2 MultiLayer models

The multilayer model composites several sub-models, each assigned to a specific
`BlockRenderLayer`, into one block.

- **`MultiLayerLoader`** (`models/multilayer/MultiLayerLoader.java`) accepts the single location
  `immersiveengineering:models/block/multilayer` and returns the `MultiLayerModel.INSTANCE`
  singleton (`MultiLayerLoader.java:26-40`).
- **`MultiLayerModel`** (`models/multilayer/MultiLayerModel.java`) holds a
  `Map<BlockRenderLayer, List<ModelData>>`. `process` parses per-layer sub-model JSON and returns
  a new instance only when the data changes (`MultiLayerModel.java:107-144`). `bake` bakes each
  sub-model into a `Map<BlockRenderLayer, List<IBakedModel>>` wrapped in a `BakedMultiLayerModel`
  (`MultiLayerModel.java:92-104`).
- **`BakedMultiLayerModel`** (`models/multilayer/BakedMultiLayerModel.java`) returns, for the
  current `MinecraftForgeClient.getRenderLayer()`, the concatenated quads of that layer's
  sub-models; when the render layer is `null` (item/inventory render) it concatenates *all*
  layers (`BakedMultiLayerModel.java:50-70`). `MultiLayerModel.LAYERS_BY_NAME`
  (`MultiLayerModel.java:36-44`) is the shared name→layer table that `ConnLoader` also uses.

### 2.3 ConfigurableSides models

`ModelConfigurableSides` (`models/ModelConfigurableSides.java`) gives machines per-side textures
that change with an I/O `SideConfig` (NONE / INPUT / OUTPUT). Its inner `Loader`
(`ModelConfigurableSides.java:216-259`) is an `ICustomModelLoader` matching
`models/block/smartmodel/conf_sides_*`; the suffix selects a layout variant (`all6_`, `s_`,
`hud_`, etc.). It bakes a `TextureAtlasSprite[6][3]` (6 faces × 3 config values). `getQuads`
selects the per-face sprite from the unlisted `IEProperties.SIDECONFIG` values, builds a 6-digit
config key, and caches the resulting 6 quads in a static `modelCache`
(`ModelConfigurableSides.java:119-145`). The cache is cleared on resource reload
(`ModelConfigurableSides.java:218-222`).

### 2.4 Other baked models

- **`ModelCoresample`** (`models/ModelCoresample.java`) builds the core-sample item: a thin core
  with stacked ore bands sized by a `MineralMix`'s ore weights. Quads are baked lazily into the
  instance field `bakedQuads` and the per-mineral instance is cached in a static `modelCache`
  (`ModelCoresample.java:62, 64-154`).
- **`ModelItemDynamicOverride`** (`models/ModelItemDynamicOverride.java`) wraps an item model and
  rebuilds its quads from a stack-specific list of layer textures (for NBT-dependent items). It
  pre-builds the quads per instance and caches instances by `getModelCacheKey(stack)`
  (`ModelItemDynamicOverride.java:117-141`). Inner `BakedGuiItemModel` filters to SOUTH-facing
  quads for flat GUI rendering (`ModelItemDynamicOverride.java:143-178`).
- **`ModelData`** (`models/ModelData.java`) is a helper that wraps a deferred Forge `IModel`
  reference plus texture overrides and custom JSON. `attemptToLoad` drives the Forge
  `getModel → retexture → process` pipeline (`ModelData.java:45-56`); it implements `equals`/
  `hashCode` so it can serve as a cache key.
- **`SmartLightingQuad`** (`models/SmartLightingQuad.java`) is a `BakedQuad` subclass that
  overrides `pipe` so each vertex samples world light at its own *relative* block position — used
  for geometry (wires, conveyors) that spans multiple blocks. It reaches into Forge's lighting
  pipeline by reflection to write directly to the parent vertex consumer
  (`SmartLightingQuad.java:68-119`).
- **Armor / worn equipment** all extend `ModelIEArmorBase` (`models/ModelIEArmorBase.java`), a
  `ModelBiped` subclass that handles armor-stand / zombie / skeleton posing. Concrete models:
  `ModelManeuverGear`, `ModelEarmuffs`, `ModelPowerpack` (the most complex — it also draws
  catenary wires from the pack to held flux items, `ModelPowerpack.java:212-261`), and
  `ModelShaderMinecart` (extends `ModelMinecart`, applies shader-case multi-pass rendering).
  Each uses a lazily-created singleton model instance.
- **`ImmersiveModelRegistry`** (`ImmersiveModelRegistry.java`) hooks `ModelBakeEvent` to swap in
  custom item models and inject the coresample / conveyor / feedthrough inventory models
  (`ImmersiveModelRegistry.java:52-93`). Its OBJ item replacement loads and bakes during the bake
  event only.

---

## 3. Dynamic Models: Connectors, Wires & Conveyors

### 3.1 Connectors and wires (`models/smart/`)

A connector block is rendered as a base model (the connector itself) plus procedurally generated
**wire** geometry (catenary curves) to each of its connections.

**`ConnLoader`** (`models/smart/ConnLoader.java`) is the `ICustomModelLoader`. It accepts the
data-driven location `immersiveengineering:models/block/smartmodel/connector` and any
`models/block/smartmodel/conn_*` location (`ConnLoader.java:38-54`). `loadModel` resolves a
registered base model and texture replacements (registered via the static `baseModels` /
`textureReplacements` maps) and returns a `ConnModelBase` `IModel`
(`ConnLoader.java:58-78`). `ConnModelBase.bake` bakes the base model and wraps it in a
`ConnModelReal` (`ConnLoader.java:152-158`). On resource reload, the loader invalidates the
`ConnModelReal` cache (`ConnLoader.java:45-48`).

**`ConnModelReal`** (`models/smart/ConnModelReal.java`) is the connector's `IBakedModel`. Its
`getQuads` (`ConnModelReal.java:66-101`) only does wire work for `side==null` and an
`IExtendedBlockState`. It reads the connection set off the TileEntity (via
`IEProperties.TILEENTITY_PASSTHROUGH` → `ICacheData`), computes an `(x,z) mod 16` chunk-relative
sub-key, builds an `ExtBlockstateAdapter` cache key, and looks up an `AssembledBakedModel` in a
static Guava cache (`ConnModelReal.java:51-54`; max 100, 2-minute idle expiry).
**`AssembledBakedModel`** (`ConnModelReal.java:142-205`) lazily generates the wire geometry via
`ClientUtils.convertConnectionFromBlockstate` and merges it with the base model's quads.

**`ExtBlockstateAdapter`** (`ConnModelReal.java:207-345`) is the shared cache-key mechanism used
across the smart-model and OBJ pipelines. It wraps an `IExtendedBlockState` and makes it
hashable/equatable while *ignoring* irrelevant unlisted properties (two ignore sets:
`ONLY_OBJ_CALLBACK` and `CONNS_OBJ_CALLBACK`, `ConnModelReal.java:209-211`). Equality folds in the
render layer, an `extraCacheKey` derived from any `IOBJModelCallback`, and the listed/unlisted
property values (`ConnModelReal.java:316-344`). Debug-only self-consistency assertions are
guarded by `Config.IEConfig.enableDebug`.

**Wire geometry generation** lives in `ClientUtils.convertConnectionFromBlockstate`
(`ClientUtils.java:1400-1480`). For each `Connection` it walks the precomputed
`catenaryVertices`, and for each segment builds a box of four `SmartLightingQuad`s (two
horizontal, two vertical) sized by the cable's render diameter and tinted by cable colour
(`ClientUtils.java:1436-1477`). It splits geometry at chunk boundaries so each chunk renders only
its own portion of a wire (`ClientUtils.java:1426-1435`). The "fading" final segment uses a
partial-alpha colour array and a tiny offset (`ClientUtils.java:1438-1447`). The result is two
lists keyed by render layer (solid index 0, translucent index 1).

**Feedthroughs** (`models/smart/FeedthroughLoader.java`, `FeedthroughModel.java`) render a wire
passed through a wall: the host block in the middle plus a connector + wire stub on each side,
selected by which slice of the 3-block structure is being drawn. `FeedthroughModel.getQuads`
reads the `TileEntityFeedthrough` off the blockstate, builds a `FeedthroughCacheKey` (wire type,
base state, offset, facing, render layer), and caches a `SpecificFeedthroughModel` in a static
Guava cache (`FeedthroughModel.java:65-68, 82-115`; max 100, 2-minute idle). The item form is
cached separately (`FeedthroughModel.java:171-174`).

**`IEConnectionModel`** (`models/IEConnectionModel.java`) is a thin delegating `IBakedModel`
retained from earlier versions; `getQuads` forwards to the wrapped base model
(`IEConnectionModel.java:76-79`) and its internal cache is unused.

### 3.2 Conveyors (`models/ModelConveyor.java`)

`ModelConveyor` is a fully hand-built `IBakedModel` — no JSON/OBJ source; every quad is generated
in code. `getQuads` (`ModelConveyor.java:69-121`):

1. Computes a cache key from `conveyor.getModelCacheKey(tile, facing)` (block) or the registry
   name (item) (`ModelConveyor.java:73-91`). The key encodes conveyor type, facing, wall flags,
   dye, and active state.
2. Looks the key up in the static `modelCache` (`ModelConveyor.java:53`); a hit returns the cached
   quads.
3. On a miss, it resolves the facing rotation matrix, lets the conveyor modify it, determines the
   conveyor direction (UP / DOWN / HORIZONTAL) and wall configuration, selects active/inactive +
   dye-stripe textures, and calls `getBaseConveyor` to build ~30+ quads
   (`ModelConveyor.java:100-118`). The conveyor's `modifyQuads` post-processes the result, which is
   then stored.

Each (type, facing, state) combination therefore bakes once and is reused. Item models are cached
separately in `itemModelCache` (`ModelConveyor.java:368`), keyed by conveyor type, and routed via
the `ItemOverrideList` reading the `conveyorType` NBT (`ModelConveyor.java:369-383`).

---

## 4. TileEntitySpecialRenderers (TESRs)

Every machine TESR feeds quads through the shared `Tessellator` / `BufferBuilder` in immediate
mode each frame; none use GL display lists or VBOs. The shared helpers
`ClientUtils.renderModelTESRFast` (`ClientUtils.java:1923-1947`) and
`renderModelTESRFancy` (`ClientUtils.java:1813-1885`) stream a `List<BakedQuad>` into the buffer;
the "fancy" variant computes per-quad neighbour lighting (eight light vectors normalised from the
six neighbour brightnesses), with a `useCached` flag to reuse that computation within a tick.
`renderModelTESRFancy` falls back to the fast path when `Config.IEConfig.disableFancyTESR` is set.

The TESRs fall into two patterns:

- **Quad-list cached** — the model's `BakedQuad` list is built once into a static field and only
  re-streamed (plus a GL transform) per frame. These are the well-behaved renderers.
- **Per-frame model fetch** — `getBlockState` / `getActualState` / `getModelForState` and a full
  `renderModel` (or `getQuads`) run every frame. These are the heavier renderers; see
  performance notes.

### Cached-quad TESRs

| TESR | Draws |
|---|---|
| `TileRenderWatermill` | Rotating waterwheel; quads cached in a static field, `reset()` clears on reload (`TileRenderWatermill.java:29, 36-44, 61`). |
| `TileRenderWindmill` | Rotating windmill, one cached quad list per sail count (`TileRenderWindmill.java:36, 45-62, 81`). |
| `TileRenderBelljar` | Garden cloche glass shell + growing plant; glass quads cached per facing, plant quads cached per blockstate, with `reset()` (`TileRenderBelljar.java:39-40, 49-59, 94-102, 129-133`). |

### Per-frame model TESRs (machine bodies & animated parts)

| TESR | Draws |
|---|---|
| `TileRenderArcFurnace` | Body with toggled electrode parts; animated pouring-metal fluid stream (`TileRenderArcFurnace.java:43-66, 84-139`). |
| `TileRenderAutoWorkbench` | Largest TESR: animated blueprint/lift/drill/press timeline, items along the conveyor, and a cached procedural blueprint line-drawing overlay (`TileRenderAutoWorkbench.java:84-310, 335-537`). |
| `TileRenderBottlingMachine` | Animated `lift` part, tank fluid box, item sliding through, stencil-buffer partial-fill on the item (`TileRenderBottlingMachine.java:115-225`). |
| `TileRenderBucketWheel` | Rotating excavator wheel; the baked model is cached but `getQuads` is re-run per frame to retexture buckets to the dug block (`TileRenderBucketWheel.java:41, 59-106`). |
| `TileRenderCrusher` | Body re-tessellated twice for two counter-rotating barrels (`TileRenderCrusher.java:63-77`). |
| `TileRenderDieselGenerator` | Body with a spinning fan (`TileRenderDieselGenerator.java:64-71`). |
| `TileRenderMetalPress` | Body translated by an animated piston, mold item, process items (`TileRenderMetalPress.java:54-133`). |
| `TileRenderMixer` | Body with a rotating agitator + stacked multi-fluid tank layers (`TileRenderMixer.java:55-110`). |
| `TileRenderSampleDrill` | Spinning/bobbing drill bit while mining (`TileRenderSampleDrill.java:39-76`). |
| `TileRenderSqueezer` | Animated (smoothstepped) piston part (`TileRenderSqueezer.java:50-89`). |
| `TileRenderTurret` | Gun part + recoiling action part, oriented by yaw/pitch (`TileRenderTurret.java:45-100`). |

### Lightweight / specialised TESRs

| TESR | Draws |
|---|---|
| `TileRenderChargingStation` | Held item floating above the station (`TileRenderChargingStation.java:42-48`). |
| `TileRenderCoresample` | A core-sample item rotated on the wall (`TileRenderCoresample.java:26-33`). |
| `TileRenderSheetmetalTank` | Four fluid windows on the tank's top face, scaled by fill (`TileRenderSheetmetalTank.java:55-71`). |
| `TileRenderSilo` | Stored-item icon + count text on four sides (`TileRenderSilo.java:39-74`). |
| `TileRenderTeslaCoil` | Lightning arcs as `GL_LINE_STRIP` (glow + core) per active animation in a client-side effect map (`TileRenderTeslaCoil.java:33-89`). |
| `TileRenderWorkbench` | Items on the workbench, or a blueprint recipe line-drawing (distance-gated) (`TileRenderWorkbench.java:48-155`). |
| `TileRenderShaderBanner` | Vanilla `ModelBanner` with a shader-composited texture, swaying by world time; composite texture cached per shader (`TileRenderShaderBanner.java:36-107`). |
| `TileRenderGasPump` | The price and the lifetime meter on an assembled Gas Station Pump's display panel — text only. The pump's body is a baked OBJ, not drawn here (`TileRenderGasPump.java`). |

### Render support classes

- **`BakedModelTransformer`** (`render/BakedModelTransformer.java`) runs every quad of a model
  through an `IVertexTransformer` (e.g. for shaders) and produces a pre-baked `TransformedModel`.
  Intended to run at bake time, not per frame.
- **`IEShaderLayerCompositeTexture`** (`render/IEShaderLayerCompositeTexture.java`) is a procedural
  `AbstractTexture` that composites shader layers onto a canvas at texture-load time (per-pixel
  colour × noise) and uploads the result; runs only on texture load / resource reload.
- **`IEBipedLayerRenderer`** (`render/IEBipedLayerRenderer.java`) is an entity armor layer that
  draws earmuffs on the head and a powerpack on the chest for tracked players
  (`IEBipedLayerRenderer.java:49-81`).

---

## 5. Entity Renderers (`render/`)

Registered in `ClientProxy.java:279-324`. All use raw immediate-mode `Tessellator` draws.

| Renderer | Entity | Draws |
|---|---|---|
| `EntityRenderRevolvershot` | `EntityRevolvershot` | Small bullet: three textured cross-planes from `bullet.png` (`EntityRenderRevolvershot.java:30, 70-89`). |
| `EntityRenderChemthrowerShot` | `EntityChemthrowerShot` | Camera-facing billboard textured with the fluid's still sprite, fluid-tinted (`EntityRenderChemthrowerShot.java:34-75`). |
| `EntityRenderRailgunShot` | `EntityRailgunShot` | 3D slug prism coloured per-cell from the ammo's `colourMap` grid (`EntityRenderRailgunShot.java:34-144`). |
| `EntityRenderIEExplosive` | `EntityIEExplosive` | Primed explosive block via `renderBlockBrightness`, scaling + white flash on the fuse (`EntityRenderIEExplosive.java:30-73`). |
| `EntityRenderFluorescentTube` | `EntityFluorescentTube` | OBJ tube model (lit/unlit, RGB-tinted) + two iron-block bracket boxes (`EntityRenderFluorescentTube.java:56-100`). |
| `EntityRenderNone` | `EntitySkylineHook` | Nothing — invisible entity (`EntityRenderNone.java:24`). |

---

## 6. Particle Effects (`fx/`)

Spawned through `ClientProxy` proxy methods (`ClientProxy.java:1496-1532`).

| Particle | Effect |
|---|---|
| `ParticleSparks` | Spark fading yellow→red→transparent over a 16-tick life, full-bright (`ParticleSparks.java:33-42`). |
| `ParticleFluidSplash` | Gravity-affected fluid droplet textured with the fluid's still sprite; dies on hitting a surface (`ParticleFluidSplash.java:49-95`). |
| `ParticleIEBubble` | Rising bubble with damped upward drift (`ParticleIEBubble.java:38-51`). |
| `ParticleFractal` | Jagged lightning bolt drawn as two `GL_LINE_STRIP` + two `GL_POINTS` passes. Uses a **deferred-render** pattern: `renderParticle` enqueues into a static deque (`ParticleFractal.java:74`) that `ClientEventHandler` flushes once per frame on `RenderWorldLastEvent` (`ClientEventHandler.java:1276-1300`). |

---

## 7. Cache Inventory

| Cache | Location | Key | Bound / eviction |
|---|---|---|---|
| OBJ parsed-model cache | `IEOBJLoader.java:27` | `ResourceLocation` | cleared on reload |
| OBJ block-quad cache | `IESmartObjModel.java:65` | `ExtBlockstateAdapter` | unbounded `HashMap`; reload-cleared via loader |
| OBJ item-model cache | `IESmartObjModel.java:63` | `ComparableItemStack` | 100, 60 s idle |
| Connector model cache | `ConnModelReal.java:51` | `Pair<Byte, ExtBlockstateAdapter>` | 100, 2 min idle; reload-invalidated |
| Feedthrough model cache | `FeedthroughModel.java:65` | `FeedthroughCacheKey` | 100, 2 min idle; reload-invalidated |
| Feedthrough item cache | `FeedthroughModel.java:171` | `ItemStack` | 100, 60 s idle |
| Conveyor model cache | `ModelConveyor.java:53` | type+facing+state string | unbounded `HashMap` |
| Conveyor item cache | `ModelConveyor.java:368` | conveyor type string | unbounded `HashMap` |
| ConfigurableSides cache | `ModelConfigurableSides.java:108` | name + side-config digits | unbounded `HashMap`; reload-cleared |
| Coresample model cache | `ModelCoresample.java:62` | mineral name | unbounded `HashMap` |
| DynamicOverride item cache | `ModelItemDynamicOverride.java:117` | `getModelCacheKey(stack)` | unbounded `HashMap` |
| Powerpack catenary cache | `ModelPowerpack.java:266` | formatted arm-angle string | unbounded, 5 min idle |
| Watermill / Windmill / Belljar quads | per file | facing / sail count / blockstate | static fields, reload-cleared |
