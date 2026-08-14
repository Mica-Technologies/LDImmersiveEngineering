# Mod Compatibility (Compat)

Technical documentation for Immersive Engineering's cross-mod integration layer
(Forge 1.12.2), in
`src/main/java/blusunrize/immersiveengineering/common/util/compat/`.

## Overview

Every integration is an **`IECompatModule`** gated on a mod id. Modules are discovered
from a static registry, instantiated only if the target mod is loaded **and** enabled in
config, and driven through a fixed lifecycle. Every lifecycle call is wrapped in
try/catch so a broken compat module can never crash IE.

Larger integrations live in subpackages (`jei/`, `opencomputers/`, `waila/`,
`crafttweaker/`, `mts/`); smaller ones are single `*Helper` files in the compat root.

---

## Framework: IECompatModule

`compat/IECompatModule.java`.

### Module registry

A static `HashMap<String, Class<? extends IECompatModule>> moduleClasses` (`:27`) maps
**modid → module class**, populated in a static initializer (`:30-78`). `modules`
(`:28`) is the set of instantiated active modules. (Commented-out entries `:60-77` mark
legacy/disabled integrations.)

### Discovery & gating — `doModulesPreInit()` (`:80-100`)

For each `(modid, class)` a module is activated only if **all** hold:

1. `Loader.isModLoaded(modid)` (`:83`);
2. the special case: `ic2` is skipped when `ic2-classic-spmod` is also present
   (`:87-88`);
3. `Config.IEConfig.compat.get(modid)` is non-null and true (`:90-92`).

When activated, the class is instantiated, added to `modules`, and `preInit()` is called.

### Lifecycle dispatchers

Called from IE's main proxy in order:

| Dispatcher | Calls |
|---|---|
| `doModulesPreInit()` (`:80`) | `preInit()` |
| `doModulesRecipes()` (`:102`) | `registerRecipes()` |
| `doModulesInit()` (`:114`) | `init()` |
| `doModulesPostInit()` (`:126`) | `postInit()` |
| `doModulesLoadComplete()` (`:141`) | `loadComplete()` (once, guarded by `serverStartingDone`, `:139`) |

Abstract methods (`:157-163`): `preInit`, `registerRecipes`, `init`, `postInit`.
Optional: `loadComplete()` (`:165`), and client-only `clientPreInit`/`clientInit`/
`clientPostInit` (`:169-182`, `@SideOnly(CLIENT)`).

> `GeneralComputerHelper` is **not** a registered module — it is a plain static helper
> invoked from `OCHelper.postInit` to add shared CC/OC manual entries.

---

## JEI (`jei/`) — client only

Entry: `jei/JEIHelper.java`, annotated `@JEIPlugin` (`:50`), `implements IModPlugin`
(`:51`); discovered by JEI's classpath annotation scan.

- `registerItemSubtypes` (`:59-72`) — NBT subtypes for conveyors / bullets.
- `registerCategories` (`:81-103`) — populates a `LinkedHashMap<Class, IERecipeCategory>`
  and calls `registry.addRecipeCategories`.
- `register` (`:105-158`) — blacklist, the assembler **recipe transfer handler**, ghost
  (drag-to-slot) handlers, catalysts, `addRecipes` per category, and recipe click areas.

Registered categories (UID = `"ie." + name`): coke oven, alloy smelter, blast furnace
(+ fuel), metal press, crusher, workbench, squeezer, fermenter, refinery, arc furnace
(+ recycling), bottling machine, mixer. `IERecipeCategory` is the abstract base;
`MultiblockRecipeWrapper` the generic wrapper. Support classes:
`AssemblerRecipeTransferHandler`, `FluidSorterGhostHandler` / `IEGhostItemHandler`,
`IEFluidTooltipCallback`. No server-side or per-tick cost.

---

## OpenComputers (`opencomputers/`)

Entry: `OCHelper.java`. `init()` (`:21-38`) registers 15 drivers via
`API.driver.add(...)`; `postInit()` adds shared manual content via
`GeneralComputerHelper`.

Framework: `ManagedEnvironmentIE.java` is the abstract driver-environment base wrapping
an IE `TileEntityIEBase`; `getTileEntity()` (`:33-39`) re-fetches the TE from the world
on every call. The nested `ManagedEnvMultiblock` adds the `enableComputerControl` /
`setEnabled` callbacks (`:49-67`). Each concrete `*Driver` is a
`DriverSidedTileEntity` whose `createEnvironment` checks `master()` + `isRedstonePos()`
and returns the environment under a `preferredName` (the OC component name) at priority
1000.

Components (`preferredName`): `ie_excavator`, `ie_arc_furnace`, `ie_assembler`,
`ie_bottling_machine`, `ie_crusher`, `ie_diesel_generator`, `ie_current_transformer`
(energy meter), `ie_fermenter`, `ie_floodlight`, `ie_mixer`, `ie_refinery`,
`ie_sample_drill`, `ie_squeezer`, `ie_tesla_coil`, and `ie_lv/mv/hv/creative_capacitor`.

Each driver exposes `@Callback` Lua methods — mostly O(1) energy/active/tank/slot
getters plus `enableComputerControl` / `setEnabled`. The heavier ones (assembler
ingredient check, queue inspectors) are noted in the performance docs.

`GeneralComputerHelper.java` (shared CC/OC logic) only adds manual entries on the client
(`getEffectiveSide()==CLIENT`, `:25`), guarded by a static `added` flag.

---

## Waila / Hwyla (`waila/`)

Entry: `WailaHelper.java` — `init()` (`:29`) sends an `FMLInterModComms` "register"
message pointing at `IEWailaDataProvider.callbackRegister`.

`IEWailaDataProvider.java` registers (`:33-45`): body providers for `BlockIECrop`,
`TileEntityWoodenBarrel`, `IFluxReceiver`, `IFluxProvider`; NBT providers for the barrel
and flux storage; and a stack provider for `TileEntityMultiblockPart`. Tooltips show crop
growth %, barrel fluid level, IF energy bars, and Tesla Coil redstone/power mode.
`getNBTData` (`:118-148`) runs server-side per look — see performance docs.

---

## TheOneProbe — `OneProbeHelper.java`

`preInit()` (`:44`) sends an IMC function message; `apply(ITheOneProbe)` (`:66-77`)
registers six providers: `EnergyInfoProvider` (IF bar + an `IProbeConfigProvider` to
suppress vanilla RF), `ProcessProvider` (per-process progress bars), `TeslaCoilProvider`,
`SideConfigProvider`, `FluidInfoProvider`, and `MultiblockDisplayOverride`. The
`addProbeInfo` methods run server-side per look — see performance docs.

---

## CraftTweaker (`crafttweaker/`) — load-time recipe scripting

Entry: `CraftTweakerHelper.java` — `preInit()` registers 16 ZenClasses via
`CraftTweakerAPI.registerClass` (`:27-42`); the other lifecycle methods are empty. Every
mutation is a private `IAction` submitted with `CraftTweakerAPI.apply(...)`; none
implement `undo()` (fire-and-forget).

ZenClasses (`mods.immersiveengineering.*`): AlloySmelter, ArcFurnace, BlastFurnace
(+ fuels), Blueprint, BottlingMachine, CokeOven, Crusher, DieselHandler, Excavator +
`Excavator.MTMineralMix`, Fermenter, MetalPress, Mixer, Refinery, Squeezer,
Thermoelectric. Each wraps the corresponding IE recipe/handler list with add/remove/
removeAll. Removal scans are O(n) full-list iterations but **load-time only**.

> Cosmetic note: `Mixer.java:61` `describe()` mislabels itself as "Fermenter".

---

## Immersive Vehicles (`mts/`) — fork-only

Entry: `mts/MTSHelper.java`, gated on modid **`mts`** (Immersive Vehicles was Minecraft
Transport Simulator long before it was renamed, and the id never followed).

Makes this fork's petroleum fuels usable in MTS vehicles. MTS holds what an engine will
burn in `ConfigSystem.settings.fuel.fuels`, a `Map<fuelType, Map<fluidRegistryName,
potency>>` seeded once during MTS's own pre-init from a hardcoded default table of *bare*
fluid names (`gasoline`, `diesel`, `ethanol`, …). This fork prefixes its cuts `ie_`, so
none of them appear in any entry and a fuel pump rejects them on contact.

`postInit` reaches that live map by **reflection** — no MTS class is named at compile
time, and the jar is not in this dev environment — and folds in the fork's fluids. Post-init
is the moment because MTS populates the map in its own pre-init, and MTS writes
`mtsconfig.json` in its own init, so the injection lands after both and is never persisted:
it is rebuilt from scratch every launch. It never overwrites a potency already in the file
and never invents a fuel type MTS does not have.

The decision itself — which fuel type takes which fluid at what potency, and how a content
pack's invented fuel type name is classified — lives in `mts/MTSFuelTable.java`, which
imports nothing from Minecraft or Forge and is unit-tested in
`src/test/java/.../common/util/compat/mts/MTSFuelTableTest.java`. **The mapping table for
each fuel type, and what is deliberately excluded, is documented in `docs/PETROLEUM.md`.**

Load-time only; no runtime cost and no event subscribers.

---

## Single-file helpers (compat root)

All gate on the modid in `IECompatModule.moduleClasses`. Unless noted, all are
**load-time only** (recipes / handler registries / ore-dict / IMC / Belljar crops) with
no runtime server cost.

| Helper | modid | Integration |
|---|---|---|
| `ActuallyAdditionsHelper` | `actuallyadditions` | squeezer canola recipe, Belljar crop, `HempFarmBehavior` for AA's farmer |
| `AlbedoHelper` | `albedo` | **client-only** `GatherLightsEvent` for Tesla Coil dynamic lighting |
| `AttainedDropsHelper` | `attaineddrops2` | Belljar `IPlantHandler` |
| `BaublesHelper` | `baubles` | sets `Lib.BAUBLES`; attaches `IBauble` cap to the powerpack |
| `BetterWithModsHelper` | `betterwithmods` | Belljar hemp crop + fertilizer |
| `BloodMagicHelper` | `bloodmagic` | homing "crystalwill" bullet + blueprint recipe |
| `BotaniaHelper` | `botania` | terrasteel homing bullet, blueprint, shader/relic, conveyor-magnet blacklist; **client-gated** `LivingDropsEvent` |
| `ChiselHelper` | `chisel` | IMC `add_variation` messages |
| `ChiselsAndBitsHelper` | `chiselsandbits` | on-demand `Function` adding IE blockstates to C&B compat list |
| `CoFHHelper` | `cofhcore` | assembler fluid-ingredient query converter |
| `DenseOresHelper` | `denseores` | postInit: generate dense crusher/arc variants (O(ores×recipes), one-time) |
| `EnderIOHelper` | `enderio` | arc-alloying recipes, chemthrower effects, conveyor-magnet suppression |
| `ExtraUtilsHelper` | `extrautils2` | Belljar plant handlers (ender lily, red orchid) |
| `ForestryHelper` | `forestry` | squeezer/chemthrower/fertilizer + backpack IMC |
| `FoundryHelper` | `foundry` | chemthrower effects on Foundry fluids |
| `HarvestcraftHelper` | `harvestcraft` | assembler water/milk converters + reflected Belljar crops |
| `IC2Helper` | `ic2` (excl. IC2-Classic) | assembler recipe adapter + fertilizer |
| `InspirationsHelper` | `inspirations` | iterate `PotionType.REGISTRY` for bottling recipes (one-time) |
| `MysticalAgricultureHelper` | `mysticalagriculture` | postInit Belljar crop (inferium tiers, reflected) |
| `RailcraftHelper` | `railcraft` | railgun projectile props (server) + **client** reflective minecart model swap |
| `TConstructHelper` | `tconstruct` | fluids/materials/traits/alloys/arc recipes/IMC; `TraitThermalInversion` is tool-driven |
| `ThaumcraftHelper` | `thaumcraft` | IMC, chemthrower effects, `ExternalHeaterHandler` (heater-TE driven, cached reflection) |
| `ThermalFoundationHelper` | `thermalfoundation` | ore-dict, chemthrower effects, fertilizer |
| `XLFoodHelper` | `xlfoodmod` | Belljar crops + fertilizer |

`GeneralComputerHelper` is the shared OC/CC manual-content helper described under
OpenComputers (not a module).

### Runtime event subscribers

Only three Forge event subscribers exist across all helpers, and none run unconditionally
each tick:

- `BaublesHelper.java:57` — `AttachCapabilitiesEvent<ItemStack>` (both sides). Fires on
  every stack-capability attachment but the body is a single `==` reference check.
- `AlbedoHelper.java:38` — `GatherLightsEvent`, `@SideOnly(CLIENT)` (render thread only).
- `BotaniaHelper.java:82-83` — `LivingDropsEvent`, registered **only** when
  `getEffectiveSide()==CLIENT`.

No helper registers `WorldTickEvent`, `PlayerTickEvent`, or `LivingUpdateEvent`.

---

## Adding a new compat module

1. Create a class extending `IECompatModule` implementing the four abstract lifecycle
   methods (and any client-side overrides).
2. Register it in `IECompatModule`'s static `moduleClasses` map keyed by the target
   mod's id.
3. Ensure a `Config.IEConfig.compat` entry exists for that modid (default true) so it
   can be toggled.
4. Keep any runtime hook off hot paths; do recipe/registry work in `registerRecipes` /
   `postInit`.
