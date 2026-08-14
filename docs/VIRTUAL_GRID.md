# The Virtual Power Grid

A fork-only feature: named, switchable **segments** that move Immersive Flux between places with
no wire between them — across a mountain, across a chunk border, across dimensions.

This is not stock Immersive Engineering. It is a clean-room implementation of the *concepts*
behind Flux Networks, built natively against IE's own energy, GUI and persistence machinery, and
deliberately re-themed: no floating sci-fi cubes, only utility hardware that looks like it belongs
on a pole.

It shares no state with the wire network. `ImmersiveNetHandler` and `VirtualGrid` do not know about
each other, so neither can regress the other, and a build can use both.

---

## The parts

| Block | What it does |
|---|---|
| **Grid Feed Unit** | Takes flux out of the world and into its segment. |
| **Grid Service Unit** | Takes flux out of its segment and delivers it back to the world. |
| **Grid Signal Unit** | Carries no flux. Bridges its segment to redstone, in either direction. |
| **Grid Management Console** | 2×2 wall multiblock, built from one terminal and IE's three engineering blocks. The control room for the whole grid. |

The three units are 10×12×6 pixel sheet-metal boxes that bolt to any solid face, including IE's
wooden and steel posts — that is the pole-mount look, with no special-case code. Facing follows the
wire-connector convention: it points at whatever the box is bolted to.

**They exchange flux with the blocks they touch, not with wires.** A Service Unit bolted to a
capacitor powers that capacitor; a Service Unit bolted to a post with an LV connector against it
feeds a wire network. This is exactly how every IE machine behaves, but it is not obvious from
looking at a box, so the readout says what the box does with the world whether or not anything is
moving — grey while it is working, yellow when nothing adjacent will take what it has.

**A wire connector beside a Service Unit is fed whichever way it faces.** An IE connector accepts
flux on exactly one side, the block it is bolted to, so a connector mounted on the *wall* next to a
unit — the same gesture as far as a player is concerned, and often the only one the geometry leaves
room for — used to touch a live unit and do nothing, silently. `EnergyHelper.acceptingSide` is
where that exemption lives. Nothing but a connector gets it: on
a machine the accepting face is a real configuration choice rather than an artefact of where there
was room to put it.

The Feed and Service Units carry a terminal post on the front, so the place wiring attaches is
something you can see rather than something you have to be told. The Signal Unit deliberately does
not — it moves no flux, and a terminal on it would be a lie.

### Segments

A segment is the unit of management: a name, a colour, an on/off switch, an owner and lock, a
transfer policy, an ordered failover list, and statistics. Devices belong to exactly one segment;
an unassigned device is inert and shows an unlit lamp.

The grid is deliberately *not* one flat pool. Segments are what let you switch off the east side of
town, cap what the smelter district may draw, or give the hospital its own backup.

---

## How energy moves

### Normal mode

One pass per server tick, driven by a single `ServerTickEvent` — **no device in this feature is
`ITickable`**. Three phases, each a separate pass over all segments:

1. **Collect.** Each Feed Unit is drained into its segment's smoothing buffer, highest priority
   first, bounded by the segment's input cap, the buffer's remaining room, and the device's own
   transfer cap. Loss (`gridDefaultLossPct`, ships at 0) is applied on intake, so the statistics
   report honest *delivered* numbers.
2. **Serve.** The buffer is paid out to Service Units — **critical loads first**, then by
   priority. That two-class partition is the load-shedding rule: in a shortfall, anything marked
   critical is served before anything that is not.
3. **Failover.** Any unmet demand is offered to the segment's backup chain (below).

The phases run as three passes rather than nested inside one per-segment loop. With the nested
form, whether failover worked depended on the order segments happened to have been created in — a
backup that had not collected yet looked empty to the segment relying on it.

**The buffer is not a battery.** It defaults to two ticks of the segment's own output rate and is
hard-capped by `gridBufferCapMax`. It exists so the collect-then-serve ordering works, nothing
more.

### City mode

With `cityMode` and `cityModeVirtualGrid` both on, the accounting is replaced by presence: a
segment is energized if it is switched on and a Feed Unit recently proved its source alive with a
1-flux sip, and Service Units on an energized segment then deliver freely. See the
[Virtual Power Grid section of CITY_MODE_AND_PERF.md](CITY_MODE_AND_PERF.md#virtual-power-grid).

---

## Failover

On the console's Failover tab, give a segment an ordered list of other segments to fall back on.

- Backups always engage when a segment is **switched off, tripped, held off by a Signal Unit, or
  asleep on its schedule** — any outage, whatever caused it.
- With **top-up** enabled (`gridFailoverTopUpDefault`, per-segment toggle), backups also cover
  ordinary shortfalls, not just outright outages.
- A serving backup debits its own buffer and its own per-tick output budget. It cannot spend what
  it does not have.
- **Cycles are harmless.** The walk carries a visited set, so A→B→A terminates; `gridMaxFailoverDepth`
  bounds how far a shortfall may propagate even through an acyclic chain.

The Failover tab's "who would cover this" preview runs the same cycle-guarded walk the engine does,
so the preview cannot drift from the behaviour.

---

## Breakers and load shedding

**Load shedding** is always on and costs nothing: it is the critical-first ordering in phase B.

**Breakers** are off by default (`gridBreakersEnabled`). Switch them on and a segment held at its
output ceiling for `gridBreakerTripSeconds` consecutive seconds **trips** — it latches off and
stays off until somebody resets it. That is deliberately a physical act, on the Segments tab or via
`/ie grid on`; switching a segment back on *is* the reset, as on a real panel.

A segment that is down for any reason accumulates nothing against its breaker.

---

## Scheduling

A segment can keep its own hours. Set an **on** and an **off** time in day-time ticks (12000 is
dusk, 23000 dawn) and it runs only inside that window. The window wraps across midnight, which is
the case that matters — street lighting is on at dusk and off at dawn.

Equal endpoints mean a window that **never opens**, not one that is always open: a schedule that
runs all day is indistinguishable from having no schedule, so treating it that way would hide the
typo rather than showing it.

**The schedule is a gate, never a second switch.** It can only hold a segment down; it will never
switch on one you switched off. Otherwise the console toggle and the clock would fight every dusk
and whichever ran last would win.

All segments run on the overworld's clock, because a segment can span dimensions and exactly one
clock has to be authoritative or the same schedule would mean different things to different devices
in it.

---

## Signal Units

A Signal Unit assigns to a segment like any other device and then works in one of four ways:

| Mode | Inverted | Behaviour |
|---|---|---|
| Output | no | Emits 15 while the segment is up. A running light. |
| Output | yes | Emits 15 while the segment is **down**. A fault light. |
| Input | no | Redstone holds the segment off. An external kill switch. |
| Input | yes | *Absence* of redstone holds it off. A dead-man's switch. |

"Up" means the segment is operational and flux either moved through it this tick or is sitting in
its buffer — so a segment whose generators died reads as down even though nobody switched it off.
That is the case a fault lamp exists for. In city mode, "up" is energization.

Input units are read **before** anything moves, so a held segment never collects a tick of energy
it is not allowed to deliver. Multiple input units are wired in series: any one calling for a stop
is enough. An input unit that is disabled or whose chunk unloaded stops voting — a grid that stayed
dead because of a kill switch nobody could find would be unfixable.

Output units are change-gated: a steady grid issues no block updates at all.

The `forcedOff` state is never persisted. A kill switch that has since been removed must not keep a
segment off across a restart.

---

## The console

A 2×2 wall of four *different* blocks, struck anywhere on its front face with an Engineer's
Hammer:

|  |  |
|---|---|
| **Console Housing** (the terminal) | **Redstone Engineering Block** |
| **Light Engineering Block** | **Heavy Engineering Block** |

Read as hardware rather than as a recipe: the terminal is the screen, the redstone block is the
instrument rack beside it, and the light and heavy blocks are the desk and the power cabinet
underneath. Four copies of one housing — which is what this used to be — made a wall of identical
cabinets and a recipe that said nothing.

The formed console is **one OBJ model**, drawn entirely by its master while the other three blocks
render nothing, exactly as stock IE draws a tank or a metal press. There is no seam down the middle
because there is nothing there to seam: the desk runs the full two blocks, and so does the screen,
its bar graph and its scanlines. Losing power swaps the screen texture for a dead one rather than
changing the model.

Taking it apart — hammer or pickaxe — returns the four component blocks to where they were.

Six tabs: **Overview** (every segment at a glance), **Segments** (the editor — name, colour,
switch, caps, loss, buffer, schedule, lock), **Devices** (per-device priority, cap, critical,
chunk-load, enable, link/unlink), **Failover**, **Stats** (60-second graphs, peaks, lifetime
meters), and **Settings**.

Every mutating control round-trips through the server and re-renders from synced truth — there is
no optimistic client state that can desync. State is pushed only to players actually viewing a
window, twice a second.

The console draws almost entirely from state the engine recomputes each tick — what is online, what
moved, what is energized, the stats history — so the sync payload carries that alongside the saved
model. The world save deliberately does not: it is all derived, and a stale copy read back at load
would contradict the first tick.

Losing power darkens the screen but never locks you out; the GUI still opens read-only.

---

## Configuration

All under `Config → Immersive Engineering → VirtualGrid`, mirrored into `GridConfig` on load.

| Key | Default | Meaning |
|---|---|---|
| `enableVirtualGrid` | true | Master switch. Off = the tick engine does nothing and boxes stay inert (still placeable, so this cannot destroy a build). |
| `gridCrossDimension` | true | Whether one segment may span dimensions. |
| `gridDefaultDeviceCap` | 4096 | Default per-device throughput, HV-connector equivalent. |
| `gridMaxSegmentIO` | 131072 | Ceiling for per-segment caps and device caps. Times `gridBufferTicks` this lands exactly on `gridBufferCapMax`; raise the two together. |
| `gridDefaultLossPct` | 0.0 | Transmission loss for new segments. |
| `gridFailoverTopUpDefault` | true | Default for the per-segment top-up toggle. |
| `gridBufferTicks` | 2 | Ticks of throughput a segment buffers by default. |
| `gridBufferCapMax` | 262144 | Hard ceiling on a segment buffer. The anti-battery clamp. |
| `gridConsoleRequiresPower` | true | Whether the console needs standby power to light its screen. |
| `gridConsoleStandbyDraw` | 8 | Console standby draw, IF/t. |
| `gridAllowChunkloading` | true | Master for the per-device chunk-load toggle. |
| `gridChunkloadBudget` | 25 | Server-wide ceiling on chunks held by grid devices. |
| `gridMaxFailoverDepth` | 4 | How far a shortfall may propagate down a chain. |
| `gridBreakersEnabled` | false | Whether sustained saturation trips a segment. |
| `gridBreakerTripSeconds` | 5 | Seconds of saturation before a trip. |
| `gridSipIntervalTicks` | 100 | City mode: how often a Feed Unit proves its source live. |
| `gridSipAmount` | 1 | City mode: how much that costs. |
| `cityModeVirtualGrid` | true | City-mode sub-flag, under the city mode block. |

Lowering a ceiling takes effect immediately — every existing segment is re-clamped on config
reload.

---

## Tools and commands

**Engineer's Voltmeter — quick assign.** Sneak-rightclick a linked box and the voltmeter picks up
its segment; every sneak-rightclick after that assigns the box you clicked. Sneak-rightclick the
air to empty it. The tooltip shows what it is holding. Segment locks are re-checked on both ends,
so a tool in hand is not a way around one.

*(The plan called for an Engineer's Screwdriver. That item does not exist in 1.12 — it is 1.16+ —
and the voltmeter is already the grid's diagnostic instrument, so the gesture lives there.)*

**Sneak-rightclick bare-handed** on any box for a chat readout: segment, state, throughput, and a
hint when the box is correctly assigned but wired up wrong.

**`/ie grid`** (permission level 4) — `list`, `info`, `create`, `delete`, `on`, `off`, `assign`,
`unassign`, `link`, `unlink`, `devices`, `schedule`, `unstick`. These exist so a grid can be
diagnosed without a console, repaired when one has been destroyed, or wired up in a test world
before one is built. `unstick` force-unregisters a ghost device.

---

## Persistence

`GridSaveData` (`ImmersiveEngineering-GridData`) on the overworld's map storage, separate from
`IESaveData` — the feature stays removable and the hot wire save path stays unbloated. Versioned
via `gridDataVersion` from day one.

The save data is authoritative. Each device's tile entity **also** mirrors its own settings into
its NBT as a backup: if the grid save file is lost or hand-deleted, reloading the chunk
re-registers the box with its old settings instead of silently producing an unconfigured one.

A device whose chunk is unloaded stays **listed** in the console (greyed, "offline") and is skipped
by the tick loop. Nothing routes *through* a device, so unlike wires there is no proxy machinery
needed. Chunk-loaded devices never go offline.

---

## Performance notes

- No `ITickable` tile entities anywhere in the feature. One global pass per tick.
- `O(active devices)` with small constants; a disabled segment is skipped wholesale, and an offline
  device is not in the iterated list at all rather than filtered out per tick.
- Priority-ordered views are rebuilt only when membership or ordering actually changes.
- No steady-state allocation. The only object created per tick is the failover visited-set, and
  only on ticks where a shortfall actually occurs.
- Service Units cache which neighbouring faces accept flux, refreshed on neighbour change rather
  than polled.
- Signal Units read redstone from a cache refreshed on neighbour change, and publish their output
  only when it changes.
- Stats sync only while a window is open, twice a second.

---

## Source map

| Concern | File |
|---|---|
| Model + registry | `api/energy/grid/VirtualGrid.java`, `GridSegment`, `GridDevice`, `GridPolicy`, `GridStats` |
| Tick engine (world-free, unit-tested) | `api/energy/grid/GridEngine.java` |
| Endpoint port | `api/energy/grid/IGridEndpoint.java` |
| Config mirror | `api/energy/grid/GridConfig.java` |
| Blocks and tiles | `common/blocks/grid/` |
| Console multiblock | `common/blocks/multiblocks/MultiblockGridConsole.java`, `common/blocks/grid/GridConsoleGeometry.java` |
| Tick driver, save data, chunk tickets, quick-assign | `common/util/grid/` |
| Packets | `common/util/network/MessageGridSync.java`, `MessageGridAction.java` |
| GUIs | `client/gui/GuiGridConsole.java`, `GuiGridDevice.java` |
| Commands | `common/util/commands/CommandGrid.java` |
| Texture generator | `docs/tools/make_grid_textures.py` |
| Console model and textures | `docs/tools/make_terminal_assets.py` |
| Tests | `src/test/java/blusunrize/immersiveengineering/api/energy/grid/`, `common/blocks/grid/GridAssetsTest.java` |

The engine is expressed purely in terms of the model and `IGridEndpoint` — it never touches `World`
or `TileEntity`. That is what makes caps, buffers, loss, priorities, load shedding, failover walks,
schedules, signal handling and city-mode presence all directly unit-testable against fakes.
