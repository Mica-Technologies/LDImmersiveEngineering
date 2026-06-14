# Items and Tools

Technical documentation for the item and tool subsystem of Immersive Engineering (Forge 1.12.2).

## Overview

All items live in `src/main/java/blusunrize/immersiveengineering/common/items/` with a small
`tools/` sub-package for the vanilla-style hand tools. Every IE item registers itself into
`IEContent.registeredIEItems` from its constructor (see `ItemIEBase.java:40`), which is how the
mod collects items for deferred registration.

The package is organized around a short class hierarchy plus a set of capability-based
integrations (energy, fluids, shaders, item inventories). The interesting behavior is concentrated
in the upgradeable tools (drill, revolver, chemthrower, railgun, skyhook, shield) and the powered
equipment (powerpack, faraday/steel armor, earmuffs).

## Class Hierarchy

```
Item (vanilla)
 └─ ItemIEBase ........................ base for almost every IE item (ItemIEBase.java:21)
     ├─ ItemInternalStorage ........... items with a capability-backed internal inventory
     │   ├─ ItemToolbox               (ItemInternalStorage.java:30)
     │   ├─ ItemSpeedloader
     │   └─ ItemUpgradeableTool ....... internal inventory + upgrade slots (ItemUpgradeableTool.java:24)
     │       ├─ ItemDrill
     │       ├─ ItemChemthrower
     │       ├─ ItemRailgun
     │       ├─ ItemRevolver
     │       ├─ ItemSkyhook
     │       └─ ItemIEShield
     ├─ ItemIETool .................... multi-tool: hammer/wirecutter/voltmeter/manual (ItemIETool.java:73)
     ├─ ItemDrillhead, ItemToolUpgrade, ItemWireCoil, ItemShader, ItemShaderBag,
     │   ItemCoresample, ItemJerrycan, ItemMaterial, ItemBullet, ItemGraphiteElectrode,
     │   ItemFluorescentTube, ItemMaintenanceKit, ItemEngineersBlueprint, ItemIESeed
ItemArmor (vanilla)
 ├─ ItemPowerpack ..................... energy-storing chest armor (ItemPowerpack.java:50)
 ├─ ItemFaradaySuit ................... electric-damage armor (ItemFaradaySuit.java:28)
 ├─ ItemSteelArmor
 └─ ItemEarmuffs ..................... sound-suppressing head armor (ItemEarmuffs.java:46)
ItemTool (vanilla)
 └─ ItemToolBase ..................... steel pickaxe/axe/shovel/sword/hoe (tools/ItemToolBase.java:19)
```

### ItemIEBase

The shared base (`ItemIEBase.java:21`) implements `IColouredItem` and handles:

- Sub-type metadata (multiple variants on one item ID) via `subNames` and per-meta translation keys
  (`getTranslationKey`, `ItemIEBase.java:64`).
- Per-meta hiding from the creative tab (`setMetaHidden`, `ItemIEBase.java:75`).
- Per-meta furnace burn time (`getItemBurnTime`, `ItemIEBase.java:109`).

### ItemInternalStorage

`ItemInternalStorage.java:30` gives an item a real Forge `IItemHandler` inventory backed by NBT,
exposed through `initCapabilities` returning an `IEItemStackHandler` (`ItemInternalStorage.java:42`).
It also contains a one-time legacy migration in `onUpdate` (`ItemInternalStorage.java:82`) that
converts the old `"Inv"` NBT list into the capability inventory and re-syncs the slot to the client.

### ItemUpgradeableTool

`ItemUpgradeableTool.java:24` adds the upgrade system on top of the internal inventory. The upgrade
contract is the `IUpgradeableTool` API. Key methods:

- `recalculateUpgrades` (`ItemUpgradeableTool.java:58`) iterates the internal inventory, finds every
  `IUpgrade` item whose `getUpgradeTypes` contains the tool's `upgradeType` string, validates it with
  `canApplyUpgrades`, and folds the result into an `"upgrades"` NBT compound. **Server-side only** —
  it early-returns on the client.
- `getUpgrades` (`ItemUpgradeableTool.java:41`) reads the cached `"upgrades"` compound. Tool code
  reads this compound to apply stat changes (e.g. drill `getDestroySpeed` adds `upgrades.getFloat("speed")`).

## Energy Integration (Immersive Flux / FE)

IE has its own energy unit (Immersive Flux, IF) that is 1:1 with Forge Energy and exposes the
standard `CapabilityEnergy.ENERGY` capability. The glue lives in
`common/util/EnergyHelper.java`.

### Item energy storage

Powered items implement `EnergyHelper.IIEEnergyItem` (`EnergyHelper.java:261`), which is an
`IFluxContainerItem` whose default methods store the energy as a plain integer in item NBT under the
key `"energy"`:

- `receiveEnergy` → `ItemNBTHelper.insertFluxItem` → `setInt(container, "energy", …)` (`ItemNBTHelper.java:175`)
- `extractEnergy` → `ItemNBTHelper.extractFluxFromItem` (`ItemNBTHelper.java:187`)
- `getEnergyStored` → `getInt(container, "energy")` (`ItemNBTHelper.java:199`)

To expose this as a standard FE capability, each energy item's `initCapabilities` returns an
`EnergyHelper.ItemEnergyStorage` (`EnergyHelper.java:282`), an `IEnergyStorage` adapter that simply
forwards to the item's `IIEEnergyItem` methods on the held `ItemStack`. The railgun
(`ItemRailgun.java:128`), shield (`ItemIEShield.java:68`) and powerpack (`ItemPowerpack.java:140`)
all wire this up; the upgradeable tools stack it onto their `IEItemStackHandler` so the item is both
an inventory and a battery.

`EnergyHelper` also provides static convenience helpers (`isFluxItem`, `insertFlux`, `extractFlux`,
`EnergyHelper.java:29–80`) that transparently handle both IF items and generic FE capability items,
plus `forceExtractFlux` (`EnergyHelper.java:84`), which discovers via a per-item cache whether an item
can only be charged in reverse (negative insert) and remembers the answer.

| Energy item | Max storage (IF) | Source |
|---|---|---|
| Railgun | 1600 | `ItemRailgun.java:307` |
| Shield (with flash/shock upgrade) | 1600 | `ItemIEShield.java:205` |
| Powerpack | 100000 | `ItemPowerpack.java:129` |

The powerpack is also a charger: its `onArmorTick` (`ItemPowerpack.java:91`) pushes up to 256 IF per
tick into every other worn flux item (any slot, excluding other powerpacks).

## Fluid Integration

Three items hold fluids via Forge's fluid capability:

- **Drill** and **Chemthrower** implement `IAdvancedFluidItem` (`IEItemInterfaces.java:42`) and expose
  an `IEItemFluidHandler` with a base capacity of 2000 mB through `initCapabilities`
  (`ItemDrill.java:583`, `ItemChemthrower.java:269`). `getCapacity` adds the `"capacity"` upgrade value
  (`ItemDrill.java:608`), and the chemthrower's `"multitank"` upgrade gives it three independent tanks
  cycled by `switchTank` (`ItemChemthrower.java:199`).
- **Jerrycan** (`ItemJerrycan.java:39`) is a simple 10000 mB `FluidHandlerItemStack` container that can
  also place fluid blocks in the world (`ItemJerrycan.java:60`).

The drill only accepts valid biodiesel-type fuels, gated by `DieselHandler.isValidDrillFuel`
(`ItemDrill.java:614`). Drilling consumes 1 mB of fuel per block broken (`ItemDrill.java:395`).

## Upgrade and Perk Systems

### Tool upgrades (`ItemToolUpgrade`)

`ItemToolUpgrade.java:30` is a single item with one metadata per upgrade, defined declaratively in the
`ToolUpgrades` enum (`ItemToolUpgrade.java:33`). Each enum constant carries:

- a tool-type set (e.g. `"DRILL"`, `"REVOLVER"`) controlling where it can be installed,
- an optional max stack size (so e.g. drill damage can stack to 3),
- an optional `applyCheck` (e.g. capacity and multitank are mutually exclusive),
- a `BiConsumer<ItemStack, NBTTagCompound>` that writes the effect into the `"upgrades"` compound.

Representative upgrades:

| Upgrade | Tools | Effect (NBT written) |
|---|---|---|
| `DRILL_WATERPROOF` | drill | `waterproof=true` (use underwater) |
| `DRILL_LUBE` | drill | `oiled=true` (head wears 1/4 as fast, +speed) |
| `DRILL_DAMAGE` (x3) | drill | `speed += 2/level`, `damage += level` |
| `DRILL_CAPACITY` | drill, chemthrower | `capacity += 2000` mB |
| `REVOLVER_BAYONET` | revolver | `melee += 6` |
| `REVOLVER_MAGAZINE` | revolver | `bullets += 6` |
| `REVOLVER_ELECTRO` | revolver | `electro=true` (electrified shots) |
| `CHEMTHROWER_FOCUS` | chemthrower | `focus=true` (tighter, longer stream) |
| `CHEMTHROWER_MULTITANK` | chemthrower | `multitank=true` (3 tanks) |
| `RAILGUN_SCOPE` | railgun | `scope=true` (zoom) |
| `RAILGUN_CAPACITORS` | railgun | `speed=1` (faster charge) |
| `SHIELD_FLASH / SHOCK / MAGNET` | shield | enables the matching active effect |

The full list and exact formulas are in `ItemToolUpgrade.java:33–51`.

### Drill heads (`ItemDrillhead`)

`ItemDrillhead.java:40` implements `IDrillHead` and ships two variants (steel, iron) whose stats are
hard-coded `DrillHeadPerm` records (`ItemDrillhead.java:47`): mining size, depth, level, speed, attack
damage and max durability. The drill reads these through the `IDrillHead` interface — head durability
is tracked in the head's own NBT under `"headDamage"` (`ItemDrillhead.java:146`). The head's
`getExtraBlocksDug` (`ItemDrillhead.java:196`) computes the area-of-effect block list used by the
drill's multi-block break.

### Revolver perks (`ItemRevolver.RevolverPerk`)

Separate from installable upgrades, revolvers can carry randomly-rolled **perks**
(`ItemRevolver.java:811`): `COOLDOWN`, `NOISE`, and the always-bad `LUCK`. Perks are stored in a
`"perks"` NBT compound and combined with installed upgrades when reading effective values
(`getUpgradeValue_d`, `ItemRevolver.java:545`). Perk generation, tier calculation and the
rarity-colored display name live in the enum (`ItemRevolver.java:892–938`). "Special" / elite
revolvers tied to specific player UUIDs are applied in `onCreated` (`ItemRevolver.java:554`).

## Weapons

| Weapon | Fire mechanism | Ammo / fuel |
|---|---|---|
| Revolver | `onItemRightClick` spawns `EntityRevolvershot` per bullet, rotates cylinder (`ItemRevolver.java:319`) | 8 internal bullet slots (+magazine upgrade); reload via speedloader |
| Railgun | charge-then-release: `onItemRightClick` starts charge, `onPlayerStoppedUsing` fires `EntityRailgunShot` if charged (`ItemRailgun.java:225`) | IF energy + any `RailgunHandler` projectile item as ammo |
| Chemthrower | continuous `onUsingTick` sprays `EntityChemthrowerShot` from the tank fluid (`ItemChemthrower.java:119`) | tank fluid; sneak-right-click toggles ignition (`ItemChemthrower.java:104`) |

The revolver supports a rich first-person render rig (reload animation, cylinder open/close) in
`handlePerspective` / `getTransformForGroups` (`ItemRevolver.java:697`, `:755`). Speedloaders
(`ItemSpeedloader.java`) are 8-slot bullet carriers used to reload the revolver in one action
(`ItemRevolver.java:344`).

## Tools

| Tool | Class | Notable behavior |
|---|---|---|
| Engineer's Hammer | `ItemIETool` meta 0 | Forms multiblocks, rotates blocks/entities, custom NBT durability (`ItemIETool.java:247`) |
| Wirecutter | `ItemIETool` meta 1 | Cuts wire connections at a connector (`ItemIETool.java:311`) |
| Voltmeter | `ItemIETool` meta 2 | Reads energy stored in a tile; sneak-links two connectors to report average loss (`ItemIETool.java:338`) |
| Engineer's Manual | `ItemIETool` meta 3 | Opens the manual GUI (`ItemIETool.java:404`) |
| Drill | `ItemDrill` | Fueled AoE miner, drill-head + upgrades, shader-capable |
| Buzzsaw/Drill heads | `ItemDrillhead` | Swappable drill heads (see above) |
| Skyhook | `ItemSkyhook` | Rides wire connections; sneak toggles speed limit (`ItemSkyhook.java:113`) |
| Toolbox | `ItemToolbox` | 23-slot portable storage, placeable as a block (`ItemToolbox.java:88`) |
| Maintenance Kit | `ItemMaintenanceKit` | Opens a GUI to repair/maintain machines (`ItemMaintenanceKit.java:22`) |

The `ItemIETool` multitool is *not* damageable in the vanilla sense (`canRepair = false`,
`ItemIETool.java:83`, to avoid issue #2990); it stores damage in NBT (`Lib.NBT_DAMAGE`) and reports it
through the `IItemDamageableIE` interface plus `getDurabilityForDisplay`.

### Vanilla-style hand tools (`tools/`)

`ItemToolBase` (`tools/ItemToolBase.java:19`) extends vanilla `ItemTool` and is subclassed by
`ItemIEPickaxe`, `ItemIEAxe`, `ItemIEShovel`, `ItemIESword`, `ItemIEHoe`. These are ordinary steel
tools with IE's tool material and an ore-dictionary repair material; they predefine per-class
effective-block sets (`tools/ItemToolBase.java:21–23`).

## Equipment / Armor

| Item | Class | Behavior |
|---|---|---|
| Steel Armor | `ItemSteelArmor` | Plain armor set, repairable with steel (`ItemSteelArmor.java:21`) |
| Faraday Suit | `ItemFaradaySuit` | Implements `IElectricEquipment`; a full set negates low-power electric damage, high-power damages/destroys the suit (`ItemFaradaySuit.java:44`) |
| Powerpack | `ItemPowerpack` | Chest-slot battery (100k IF) that charges other worn flux items each tick (`ItemPowerpack.java:91`) |
| Earmuffs | `ItemEarmuffs` | Head-slot armor implementing `IConfigurableTool`; reduces volume of configurable sound categories (`ItemEarmuffs.java:183`) |
| Shader Bag | `ItemShaderBag` | Right-click opens a random shader of the bag's rarity (`ItemShaderBag.java:85`) |

The powerpack, earmuffs and the unfinished IE armor all implement Forge's `ISpecialArmor` but return
zero armor properties — they are functional, not protective.

### Electric equipment dispatch

`IElectricEquipment.applyToEntity` (`IElectricEquipment.java:35`) is the entry point. When an electric
source (tesla coil, HV wire) is about to strike a living entity, it builds a shared `cache` map and
calls `onStrike` on every worn `IElectricEquipment` piece, letting the pieces cooperate (e.g. all four
faraday pieces set a bit in a shared `"faraday"` mask, and only a complete set zeroes the damage,
`ItemFaradaySuit.java:52`). The fluorescent tube also implements this purely for the visual "lit"
effect (`ItemFluorescentTube.java:201`).

## Capabilities Used by Items

| Capability | Items | Provider |
|---|---|---|
| `CapabilityEnergy.ENERGY` | railgun, shield, powerpack | `EnergyHelper.ItemEnergyStorage` |
| `ITEM_HANDLER_CAPABILITY` | all `ItemInternalStorage` subclasses | `IEItemStackHandler` |
| `FLUID_HANDLER_ITEM_CAPABILITY` | drill, chemthrower, jerrycan | `IEItemFluidHandler` / `FluidHandlerItemStack` |
| `CapabilityShader.SHADER_CAPABILITY` | drill, chemthrower, railgun, revolver, shield | `ShaderWrapper_Item` |

Items that carry NBT-heavy inventories or bullets override `getNBTShareTag` to ship a compacted view
to the client (e.g. `ItemDrill.java:621` ships only the head; `ItemRevolver.java:125` ships the
bullet list).

## Full Item Catalog

| Item | Class | Category | Notable behavior |
|---|---|---|---|
| Base item | `ItemIEBase` | base | Subtypes, per-meta names/burn time, item color |
| Material | `ItemMaterial` | material | Crafting components (many subtypes) |
| IE Seed | `ItemIESeed` | material | Hemp seed, plants hemp crop |
| Graphite Electrode | `ItemGraphiteElectrode` | material | Arc-furnace consumable, NBT durability |
| Wire Coil | `ItemWireCoil` | wiring | `IWireCoil`; right-click places/links wire connections (`ItemWireCoil.java:86`) |
| Toolbox | `ItemToolbox` | storage | 23-slot inventory, placeable block |
| Speedloader | `ItemSpeedloader` | ammo | 8-slot revolver reloader |
| Bullet | `ItemBullet` | ammo | Many bullet types via `BulletHandler` |
| Drill | `ItemDrill` | tool/weapon | Fueled AoE miner; head + upgrades; FE + fluid + shader caps |
| Drillhead | `ItemDrillhead` | upgrade | Swappable head stats, own durability NBT |
| Tool Upgrade | `ItemToolUpgrade` | upgrade | All installable tool/weapon upgrades (enum-driven) |
| Chemthrower | `ItemChemthrower` | weapon | Fluid sprayer, multitank/focus upgrades, ignition toggle |
| Railgun | `ItemRailgun` | weapon | FE-charged, scope/speed upgrades, item ammo |
| Revolver | `ItemRevolver` | weapon | 8+ bullets, upgrades + random perks, elite variants |
| Skyhook | `ItemSkyhook` | tool | Wire traversal, speed-limit toggle, fall-boost upgrade |
| IE Shield | `ItemIEShield` | weapon/equipment | Blocking shield; flash/shock/magnet active upgrades (FE-powered) |
| Engineer's Tools | `ItemIETool` | tool | Hammer / wirecutter / voltmeter / manual (4 metas) |
| Maintenance Kit | `ItemMaintenanceKit` | tool | Machine repair GUI |
| Powerpack | `ItemPowerpack` | equipment | Wearable 100k IF battery, charges worn items |
| Faraday Suit | `ItemFaradaySuit` | armor | Electric damage mitigation set |
| Steel Armor | `ItemSteelArmor` | armor | Standard steel armor set |
| Earmuffs | `ItemEarmuffs` | armor | Configurable sound suppression |
| Shader | `ItemShader` | cosmetic | Applies skins to shader-capable items/blocks |
| Shader Bag | `ItemShaderBag` | cosmetic | Opens to a random shader by rarity |
| Coresample | `ItemCoresample` | utility | Records mineral vein info, placeable |
| Jerrycan | `ItemJerrycan` | fluid | 10000 mB portable fluid container, places fluid blocks |
| Fluorescent Tube | `ItemFluorescentTube` | utility | Placeable light entity; lights up near electric sources |
| Engineer's Blueprint | `ItemEngineersBlueprint` | crafting | Defines blueprint crafting categories |
| Hand tools | `ItemIEPickaxe`/`Axe`/`Shovel`/`Sword`/`Hoe` | tool | Steel vanilla-style tools |

## NBT Keys Reference

Common per-item NBT keys (read/written through `ItemNBTHelper`):

| Key | Items | Meaning |
|---|---|---|
| `energy` | flux items | Stored Immersive Flux |
| `upgrades` | upgradeable tools | Cached folded upgrade effects |
| `Fluid` / `FluidN` | drill, chemthrower | Tank contents |
| `head` | drill | Client-sync copy of the installed drill head |
| `headDamage` | drill head | Head durability used |
| `bullets` | revolver, speedloader | Client-sync bullet list |
| `perks` / `baseUpgrades` / `elite` | revolver | Random perks / elite revolver data |
| `reload` / `cooldown` | revolver | Animation + fire-rate timers |
| `IE:Damage` (`Lib.NBT_DAMAGE`) | engineer's tools | Custom durability |
| `linkingPos` | voltmeter, wire coil | First-clicked link target |
