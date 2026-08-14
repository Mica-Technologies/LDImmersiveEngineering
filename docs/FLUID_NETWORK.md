# The Virtual Fluid Network

A fork-only feature: named, individually switchable **mains** that move fluid between registered
endpoints with no pipe between them at all.

Physical pipe does not scale to a city. A gas main under every road of a large map is thousands of
pipe blocks — thousands of block entities, thousands of BFS nodes, and a route cache that a single
edit invalidates. This is the same answer the **virtual power grid** already gives for electricity
in this fork, applied to millibuckets.

**Status: implemented, not playtested.** The engine, the three fittings, the console, persistence,
the tick handler, city mode, the config group and `/ie fluidnet` are all in the tree, and the model
is covered the way the virtual grid's is -- engine, main, registry, device, policy, stats, console
geometry and assets. No number in it has been judged in a game.

Line numbers in this document are a reading aid, not a contract; the tree moves under them.

---

## The deliberate pairing with the virtual grid

**This feature is a mirror of `api/energy/grid/`, on purpose, and that was a decision rather than an
accident.**

Generalising the shipped grid into a resource-agnostic engine would have left one copy of failover,
shut-offs, schedules, breakers and the console to maintain instead of two. It would also have meant
reworking a feature that is shipped, working, and has world save data behind it — and a migration
bug there costs somebody their power network. The duplication is the cheaper mistake.

The mitigation is that the pairing is documented on both sides and the fluid engine is a deliberate
mirror rather than a re-invention: phase for phase, guard for guard, name for name where the names
still make sense.

| Virtual grid | Fluid network |
|---|---|
| `GridSegment` | `FluidMain` — plus one fluid type |
| `GridDevice` / `GridDeviceType` | `FluidDevice` / `FluidDeviceType` |
| `GridPolicy` (caps, buffer, loss) | `FluidPolicy` (caps, line pack, leakage) |
| `GridStats` | `FluidStats` |
| `GridEngine` | `FluidNetEngine` |
| `VirtualGrid` | `VirtualFluidNet` |
| `IGridEndpoint` | `IFluidEndpoint` |
| Grid Feed Unit / Service Unit | Fluid Inlet / Fluid Outlet |
| Grid Signal Unit | Main Valve |
| Grid Management Console | Fluid Control Console |
| `GridSaveData` / `GridTickHandler` | `FluidNetSaveData` / `FluidNetTickHandler` |
| `/ie grid` | `/ie fluidnet` |

**A fix to one is an obvious candidate for the other.** If you change a guard in `GridEngine`, look
at `FluidNetEngine` before you close the tab.

---

## What is genuinely different

Electricity is fungible and millibuckets are not. That is the whole of the difference, and it comes
to exactly three things:

1. **A main carries one fluid.** `FluidMain.fluid` is a registry name, nullable while the main has
   never carried anything.
2. **Every transfer names what is moving.** `extractForMain(String fluid, int max, boolean simulate)`
   rather than `extractForGrid(int max, boolean simulate)`.
3. **A backup main can only cover a shortfall if it carries the same fluid**, and the fluid a
   failover delivers is always the *covered* main's, never the backup's. Covering a diesel outage
   out of a water main would be worse than not covering it at all.

   > That third rule shipped broken once. The engine took its failover fluid from whichever backup
   > could supply, and a real `TileEntityFluidOutlet` does not check what it is handed -- it fills
   > its neighbours with whatever the engine names. It survived review because the test suite's
   > fake endpoint refuses a fluid it does not hold, so the assertion passed while the bug sat
   > underneath it. There is now a deliberately promiscuous fake for exactly this case; use it
   > whenever the thing under test is the engine's own fluid discipline.

Everything else — the collect/serve phase ordering, per-device caps, per-main caps, loss on intake,
priority ordering, critical-load shedding, the failover walk with its cycle guard and depth bound,
city-mode presence semantics, the endpoint result clamp — is the grid's, unchanged in shape.

### How a main gets its type

An untyped main takes its fluid from the **first Inlet with something to offer**, in priority order,
during the collect phase. Once typed, `getOfferedFluid()` is never called again on it.

That last sentence is the point. If a typed main re-read its inlets, one fitting plumbed to the
wrong tank could quietly re-type a live main and start feeding every machine on it something else —
and the failure would look like the network being broken rather than like a mistake in one place.

Changing the fluid by hand goes through `FluidMain.setFluid`, which **refuses while the line pack is
non-empty**. Re-typing a main that still holds something would either destroy it silently or start
delivering the wrong fluid; draining first is one extra step and it is the honest one. Both the
console and `/ie fluidnet fluid` surface the refusal rather than swallowing it.

---

## The blocks

`common/blocks/fluidnet/`.

| Block | What it does |
|---|---|
| **Fluid Inlet** | Takes fluid out of the world and into its main. Draws from the block it is bolted to, and accepts a pipe run into any face. |
| **Fluid Outlet** | Takes fluid out of a main and delivers it to the world, trying the mount face first. |
| **Main Valve** | Carries no fluid. A redstone shut-off one way round, a pressure indicator the other. |
| **Fluid Console Housing** | Four in a 2×2 wall, hammered, become a Fluid Control Console. |

**None of them is `ITickable`.** All fluid movement happens in `FluidNetTickHandler`'s single
server-tick pass over the devices that are actually online. That is the whole performance argument
for a virtual network over a pipe run, and it only holds if it is true of every block in the feature.

### The Inlet

Buffers exactly one tick of intake — it is a doorway, not a tank. It drains the mounted block **by
fluid stack rather than by amount**, so a fitting bolted to the wrong tank cannot quietly empty
somebody's water supply.

Its buffer is **guarded against the main's fluid** while the main is typed, and dumped if the main
is ever re-typed. Without that, a pipe run plumbed to the wrong Inlet fills the buffer with water
on a diesel main, `extractForMain` never drains it, and the fitting jams shut permanently with no
way out -- it exposes no drain path by design.

Its capability is **fill-only**. There is deliberately no drain path: an Outlet feeding an Inlet
through a pipe would launder fluid straight back into the main it left, and the ledger would show a
network doing infinite work.

### The Outlet

Has **no fluid capability at all**. A second, unbudgeted path into the ledger would let a pump
bypass the per-tick caps the engine enforces, and those caps are the only throughput rule the
network has. Delivery is push-only, exactly like the Grid Service Unit's.

Neither fitting will talk to another fitting, for the same loop reason.

### The Valve

One block doing both jobs is more natural here than on the grid: on a real network the thing that
closes a branch and the thing that tells you the branch is live are the same fitting.

- **Shut-off (input):** a redstone signal holds the main closed. Inverted, it demands a keep-open
  signal — a dead-man's switch for a branch that must not outlive its controller.
- **Indicator (output):** emits while the main is flowing; inverted, emits while it is *not*, which
  is a low-pressure alarm.

Both directions are change-gated, so a steady network issues no block updates at all.

---

## The Fluid Control Console

`common/blocks/fluidnet/TileEntityFluidConsole.java`, GUI in `client/gui/GuiFluidConsole.java`.

A 2×2 wall panel, four tabs: **Overview**, **Mains**, **Fittings**, **Stats**. The grid's console
has six; failover chains and the long-form stats page stay on the command for now, and what a main
carries earns a place on the main editor instead — it is the one question the grid never has to ask.

Two behaviours worth knowing:

- **Losing power darkens the screen and makes the window read-only** rather than refusing to open.
  Being locked out of a gas network because the lights are off would be a trap, not a challenge.
- **The console spends its standby draw through `modifyEnergyStored`, not `extractEnergy`.** Its
  storage has an extract limit of zero so nothing can siphon it, and that limit applies to the
  console itself — the grid's console shipped that bug and reported "NO POWER" forever no matter
  how much energy it was fed.

Every control round-trips through `MessageFluidNetAction` and the panel redraws from the next
`MessageFluidNetSync`; nothing is applied optimistically, so the screen can never disagree with the
world. The handler re-checks that the sender has a console open, that the main exists, and that they
are allowed to edit it.

---

## City mode

Identical to the grid's, which is the strongest argument for the shared shape.

A main is **pressurised** when it is open and at least one of its Inlets has recently proved its
source is live. Proving it costs `fluidNetSipAmount` every `fluidNetSipIntervalTicks` — one
millibucket per five seconds by default — and Outlets on a pressurised main then deliver freely.

A city-wide gas network costs a boolean per main per tick.

One fluid-specific wrinkle: **the sip is also how an untyped main gets typed in city mode.** There
is no collect phase there, so if the sip did not do it a city-mode network would never decide what
it carries and would never deliver anything.

Gated on `CityMode.petroleum()`, the same flag the rest of the petroleum feature uses.

---

## Persistence

`common/util/fluidnet/FluidNetSaveData.java`, its own `WorldSavedData` on the overworld's map
storage — server-wide rather than per-dimension, like the grid's.

Its own file rather than a tag inside `IESaveData` for the same two reasons the grid has one: the
existing save path is unrelated and already large, and keeping this separate means the whole feature
can be removed, or its data deleted after a mishap, without touching anything else.

Devices carry an **NBT mirror** of their record on the tile entity. `FluidNetSaveData` is
authoritative, but if the network save file is lost or hand-deleted, reloading the chunk
re-registers each fitting with its old settings instead of silently producing an unconfigured one.

`writeToNBT(nbt, live)` takes a flag: the world save writes only what is worth persisting, while a
GUI sync additionally writes what is online, what moved and the stats history — which is most of
what the console actually displays.

---

## The Fluid Linker

The mirror of the [Grid Linker](VIRTUAL_GRID.md#tools-and-commands), and the same item at metadata
1. Rightclick a fitting with it: an empty linker opens a compact chooser listing every main with its
colour, its fluid and what it is doing, and picking one loads the tool *and* links the fitting that
opened it. Every plain rightclick after that links the fitting you clicked. Sneak-rightclick a
fitting to choose again; sneak-rightclick the air to empty the tool.

Main locks are re-checked on both ends of every move, in the same `LinkerLogic` the grid side uses.
There is one decision table, so the two tools cannot drift into disagreeing about who may move what.

This also closes a small asymmetry that had been there since the fluid network was written: the grid
had the voltmeter's quick-assign and this side had nothing, so a hundred fittings meant a hundred
walks to a console.

**The wire seam has no counterpart here, and did not need one.** A Fluid Inlet has always exposed a
fill-only handler on every face, so an IE fluid pipe run plumbs straight into a fitting — which is
exactly what the power side could not do until Feed and Service Units became wire endpoints. The
mirror-symmetry check for that change comes out "nothing to do", and it is worth writing down that
it was checked.

---

## Commands

`/ie fluidnet ...`, permission level 4. Not a substitute for the console: these exist so a network
can be diagnosed without one, repaired when one has been destroyed, or wired up in a test world
before the console is built.

| Subcommand | What it does |
|---|---|
| `list` | Every main: state, fluid, fitting counts, delivery |
| `info <main>` | Caps, line pack, throughput, lifetime, schedule, failover chain |
| `create <name>` / `delete <main>` | |
| `open <main>` / `close <main>` | `open` is also the overpressure reset |
| `fluid <main> [<fluid>\|clear]` | The one subcommand the grid has no counterpart for |
| `assign <main> [x y z]` / `unassign [x y z]` | Defaults to where you are standing |
| `link <main> <backup>` / `unlink <main> <backup>` | Warns when the two carry different fluids |
| `devices [main]` | Omit the main to list unlinked fittings |

---

## Configuration

`IEConfig.FluidNetwork`, pushed into `api/fluid/network/FluidNetConfig.java` on load — the model in
`api` never reaches back into `common`, the same arrangement the grid and the reservoir handler use.

Deliberately a **separate group** from `VirtualGrid` rather than shared knobs: the two networks carry
very different quantities, and a pack that wants a generous power grid and a stingy gas main should
be able to say so.

Notable defaults:

| Key | Default | Why |
|---|---|---|
| `fluidNetDefaultDeviceCap` | 1000 mB/t | A pressurised Fluid Pipe's worth; feeds three Steam Turbine Halls |
| `fluidNetMaxMainIO` | 32,768 mB/t | Times `packTicks` this lands exactly on `packCapMax` |
| `fluidNetPackTicks` | 2 | Enough to smooth collect-then-serve, far too little to be a tank |
| `fluidNetPackCapMax` | 65,536 mB | The anti-tank clamp — the buried tanks are what storage is for |
| `fluidNetDefaultLeakPct` | 0.0 | The network is a convenience feature by default |
| `fluidNetTripsEnabled` | false | With it off, demand above the ceiling is simply clamped |

---

## Testing

`src/test/java/blusunrize/immersiveengineering/api/fluid/network/`.

The engine is world-free and **registry-free** — fluids are registry names, not `Fluid` objects —
which is what makes all of it directly testable: `FluidNetEngineTest` exercises typing, the phases,
caps, leakage, priorities, load shedding, valves, failover cycles and city-mode presence against
`FakeFluidEndpoint`.

The mirror extends to the tests: `FluidNetTestSupport` is `GridTestSupport` with millibuckets.

---

## Source map

| Concern | File |
|---|---|
| Config mirror | `api/fluid/network/FluidNetConfig.java` |
| Device kinds | `api/fluid/network/FluidDeviceType.java` |
| The engine's only view of the world | `api/fluid/network/IFluidEndpoint.java` |
| One registered fitting | `api/fluid/network/FluidDevice.java` |
| Per-main transfer rules + schedule | `api/fluid/network/FluidPolicy.java` |
| Throughput bookkeeping | `api/fluid/network/FluidStats.java` |
| A main | `api/fluid/network/FluidMain.java` |
| The per-tick pass | `api/fluid/network/FluidNetEngine.java` |
| Server-wide registry | `api/fluid/network/VirtualFluidNet.java` |
| Block, metas, fittings | `common/blocks/fluidnet/` |
| Console multiblock shape | `common/blocks/multiblocks/MultiblockFluidConsole.java` |
| Console GUI / container | `client/gui/GuiFluidConsole.java`, `common/gui/ContainerFluidConsole.java`, `ContainerFluidNetBase.java` |
| GUI packets | `common/util/network/MessageFluidNetSync.java`, `MessageFluidNetAction.java` |
| Client-side copy of the network | `common/util/fluidnet/ClientFluidNetCache.java` |
| Persistence / tick driver | `common/util/fluidnet/FluidNetSaveData.java`, `FluidNetTickHandler.java` |
| Commands | `common/util/commands/CommandFluidNet.java` |
| Linker tool (shared with the grid) | `common/items/ItemNetworkLinker.java`, `common/util/link/`, `client/gui/GuiNetworkLinker.java`, `common/gui/ContainerNetworkLinker.java`, `common/util/network/MessageLinkerSelect.java` |
| Tests | `src/test/java/blusunrize/immersiveengineering/api/fluid/network/`, `common/util/link/LinkerLogicTest.java`, `common/items/NetworkLinkerAssetsTest.java` |
