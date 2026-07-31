# Wire and Energy Network System

Technical documentation for Immersive Engineering's wire/connection network and energy
transfer subsystem (Forge 1.12.2).

## Overview

Immersive Engineering models electricity as a graph of **connectors/relays** (nodes) joined by
**wires** (edges). Unlike Forge Energy (FE) capability blocks, which only ever talk to their six
neighbours, IE wires can span up to 32 blocks and energy is routed across the whole graph each
tick. Energy itself is **Immersive Flux (IF)**, a unit numerically identical to FE/RF; the
boundary between an IE wire and the outside world (machines, capacitors) is bridged through
Forge's `CapabilityEnergy` and IE's own `IFlux*` interfaces.

There are three voltage tiers — **LV** (copper), **MV** (electrum), **HV** (steel) — each with a
matching connector, relay, and transformer. The entire net is held in process-global static maps
on a single singleton, `ImmersiveNetHandler.INSTANCE`, keyed by dimension. Connections are
bidirectional but stored as two directed `Connection` records (one per endpoint).

Key source packages:

- `blusunrize.immersiveengineering.api.energy.wires` — the network model, the global handler,
  the connectable interface, the catenary/`Connection` data, the offline `IICProxy`.
- `blusunrize.immersiveengineering.api.energy.immersiveflux` — the IF storage/wrapper interfaces.
- `blusunrize.immersiveengineering.common.blocks.metal` — the connector/relay/transformer tile
  entities that actually tick and move energy.
- `blusunrize.immersiveengineering.common.util.EnergyHelper` — the IF <-> FE bridge.
- `blusunrize.immersiveengineering.common.IESaveData` — world-save persistence of the net.

---

## Network model: nodes and edges

### Nodes — `IImmersiveConnectable`

`api/energy/wires/IImmersiveConnectable.java:33` is the interface every node implements. A node is
either:

- An online tile entity, `TileEntityImmersiveConnectable`
  (`api/energy/wires/TileEntityImmersiveConnectable.java:45`), the base class for every connector,
  relay, and transformer; or
- An offline placeholder, `IICProxy` (`api/energy/wires/IICProxy.java:23`), which stands in for a
  connector whose chunk is unloaded so that energy can still be routed *through* (but not into/out
  of) that position.

Important node methods:

- `canConnect()` / `canConnectCable(type, target, offset)` — whether wires may attach.
- `isEnergyOutput()` — whether the node delivers energy to a machine on its attached face
  (`TileEntityConnectorLV.java:157` checks the block in front for an FE receiver).
- `outputEnergy(amount, simulate, energyType)` — push energy into the attached machine.
- `allowEnergyToPass(con)` — whether energy may transit this node (relays/connectors return
  `true`; an `IICProxy` returns its cached `canEnergyPass`).
- `onConnectivityUpdate(pos, dim)` — invalidates the indirect-connection cache for this node
  (`IImmersiveConnectable.java:182`).

### Edges — `ImmersiveNetHandler.Connection`

`api/energy/wires/ImmersiveNetHandler.java:672`. A `Connection` is one directed edge:

```
start : BlockPos      end : BlockPos
cableType : WireType  length : int (block distance, used for loss)
catenaryVertices : Vec3d[17]   // lazily computed sag geometry, render + collision
catOffsetX/Y, catA, horizontalLength, across, vertical   // catenary curve params
```

`hasSameConnectors` (`:705`) treats A->B and B->A as the same physical wire. `equals`/`hashCode`
(`:811`,`:819`) key on `(start, end, cableType)`. `writeToNBT`/`readFromNBT` (`:756`,`:768`)
persist only `start`, `end`, `cableType`, `length` — catenary geometry is recomputed on demand.

### `AbstractConnection` — a collapsed multi-hop path

`api/energy/wires/ImmersiveNetHandler.java:837`. Where a `Connection` is a single wire,
`AbstractConnection` represents an entire *route* from a source node to a reachable energy output,
flattening the intermediate relays into `subConnections : Connection[]`. It carries
`isEnergyOutput` and caches `avgLoss`. These are produced by the path-finder
(`getIndirectEnergyConnections`) and are what the per-tick distributor actually iterates.

### `WireType`

`api/energy/wires/WireType.java:39`. Abstract descriptor of a wire's electrical and visual
properties. IE's built-ins are created in `WireType.init()` (`:143`) as the inner `IEBASE` class.
Per-tier values come from config arrays `wireTransferRate`, `wireLossRatio`, `wireLength`
(`:125`–`:128`). Categories LV/MV/HV/STRUCTURE/REDSTONE (`:41`–`:45`) gate which connector accepts
which wire (`TileEntityImmersiveConnectable.canConnectCable`, `:104`). `getBaseLoss`
(`ImmersiveNetHandler.java:825`) = `(length / maxLength) * lossRatio`.

---

## Code organization: the global handler

`ImmersiveNetHandler` (`api/energy/wires/ImmersiveNetHandler.java:51`) is a process-wide
singleton holding **all** wire state for **all** dimensions:

| Field | Type | Purpose |
|---|---|---|
| `directConnections` (`:55`) | `Map<Integer, ConcurrentHashMap<BlockPos, Set<Connection>>>` | The authoritative adjacency list: per-dim, per-node set of outgoing wires. |
| `indirectConnections` (`:56`) | `TIntObjectMap<Map<BlockPos, Set<AbstractConnection>>>` | **Cache** of fully-resolved routes from each node to every energy output. |
| `indirectConnectionsIgnoreOut` (`:58`) | same | Same cache, but routes computed ignoring `isEnergyOutput` (used to advertise *available* energy to pull-consumers). |
| `transferPerTick` (`:60`) | `Map<Integer, HashMap<Connection, Integer>>` | Per-wire energy that flowed this tick; used to detect overload and burn wires. |
| `proxies` (`:61`) | `Map<DimensionBlockPos, IICProxy>` | Offline-node placeholders for routing through unloaded chunks. |
| `blockWireMap` (`:63`) | `IntHashMap<Map<BlockPos, BlockWireInfo>>` | Reverse index: which wires pass through a given block (for entity shock damage + block-placement wire cutting). |

`directConnections` is the source of truth; everything else is derived. `getMultimap(dim)`
(`:65`) lazily creates the per-dimension map.

### ASCII: data structures

```
ImmersiveNetHandler.INSTANCE  (one per server process)
│
├─ directConnections : dim → { BlockPos(node) → Set<Connection> }   ← graph adjacency (truth)
│        e.g. dim0 → { (10,64,10) → [ Conn(10,64,10 → 18,70,10, COPPER, 14) ] ,
│                       (18,70,10) → [ Conn(18,70,10 → 10,64,10, COPPER, 14) ] }
│
├─ indirectConnections : dim → { node → Set<AbstractConnection> }   ← route CACHE (derived)
│        e.g. dim0 → { (10,64,10) → [ AbsConn → (50,64,10), subs=[c1,c2,c3], avgLoss=.18 ] }
│
├─ transferPerTick : dim → { Connection → int }                     ← per-tick flow (overload)
├─ proxies : DimBlockPos → IICProxy                                 ← offline routing nodes
└─ blockWireMap : dim → { BlockPos → BlockWireInfo(in,near) }        ← spatial reverse index
```

---

## Adding / removing connections

`addAndGetConnection` (`ImmersiveNetHandler.java:87`) inserts **both** directed `Connection`s
(`node->connection` and `connection->node`), fires a block event on each loaded endpoint to
trigger a model/render refresh, and — via `addConnection(World,...)` (`:99`) — calls
`resetCachedIndirectConnections`. `IESaveData.setDirty` (`:110`) marks the world-save dirty.

`removeConnection` (`:153`) removes both directed records (`removeIf(con::hasSameConnectors)`),
strips the wire out of `blockWireMap` by ray-tracing the catenary again, notifies both endpoint
IICs (`removeCable`), fires block events, and resets the cache.

`resetCachedIndirectConnections(world, start)` (`:286`) is the cache-invalidation routine. From
`start` it does a **breadth-first flood** across `directConnections`, calling
`onConnectivityUpdate` on every reachable node, which evicts that node's entry from both indirect
caches. This guarantees the route cache is dropped for the entire connected component whenever any
wire in it changes.

---

## Per-tick energy transfer algorithm

Only connectors tick. `TileEntityConnectorLV.update()`
(`common/blocks/metal/TileEntityConnectorLV.java:60`) is the hot path (MV/HV subclass it and only
override limits/offsets — `TileEntityConnectorMV.java:16`, `TileEntityConnectorHV.java:16`).
Relays (`TileEntityRelayHV.java:19` etc.) extend the connector but `isRelay()` returns true, so
they never source/sink — they exist purely as routing nodes. Transformers
(`TileEntityTransformer.java:38`) have **no** `update()` and do not tick at all; they are passive
routing/category-bridging nodes.

Per server tick, for each connector holding energy:

```
TileEntityConnectorLV.update()                         [LV.java:60]
└─ if energyStorage > 0:
   ├─ transferEnergy(stored, simulate=true, 0)         [LV.java:333]  ← compute distribution
   ├─ transferEnergy(temp,   simulate=false, 0)        [LV.java:75]   ← apply it (deducts stored)
   ├─ addAvailableEnergy(-1, null)                      ← refresh "own energy" source list
   └─ notifyAvailableEnergy(stored, null)              [LV.java:414]  ← advertise to pull-consumers
```

### `transferEnergy` (`TileEntityConnectorLV.java:333`)

1. `getIndirectEnergyConnections(thisPos, world, ignoreIsEnergyOutput=true)` — fetch (or build &
   cache) the set of `AbstractConnection` routes from this connector (`:338`).
2. First pass (`:350`): for every route whose far node `isEnergyOutput`, *simulate*
   `end.outputEnergy(min(power, cableTransferRate), true)` and record what each output can accept
   in a **`TreeMap<AbstractConnection,Integer>`** (`:349`). The TreeMap orders by
   `AbstractConnection.compareTo`, i.e. by **average loss** then distance — closest/cheapest
   outputs are served first.
3. Second pass (`:367`): distribute `powerLeft` proportionally to each output's simulated demand
   (`prio = demand/sum`), call `outputEnergy(..., simulate)` for real, apply per-route loss
   (`getPreciseLossRate`, `ImmersiveNetHandler.java:855`), and for each sub-wire on the route:
   - accumulate `intermediaryLoss`,
   - add the flow into `transferPerTick` for overload tracking (`:390`),
   - fire `onEnergyPassthrough` on every intermediate connector (energy-meter hook).

```
                    distribute proportionally to simulated demand, nearest-loss first
  [Connector A] ──route1──► [Output X]   demand 40  →  gets 40
   stored=100  ──route2──► [Output Y]   demand 80  →  gets 60  (powerLeft exhausted)
               ──route3──► [Output Z]   demand 30  →  gets 0
                            each route applies cumulative wire loss along its subConnections
```

### Pull-side: `addAvailableEnergy` / `notifyAvailableEnergy`

Some consumers *pull* rather than being pushed. `notifyAvailableEnergy`
(`TileEntityConnectorLV.java:414`) walks the *ignore-output* routes and calls
`end.addAvailableEnergy(amount, consumer)` so a downstream node can draw from this connector's
buffer within the same tick. `TileEntityImmersiveConnectable.addAvailableEnergy`
(`TileEntityImmersiveConnectable.java:149`) rebuilds its `sources` list once per world-time tick
(`lastSourceUpdate` guard). This same `sources` list is what wire-shock damage draws on
(`getDamageAmount`, `:171`).

### Overload / wire burning

At `WorldTickEvent` END (`EventHandler.java:425`), the handler scans `transferPerTick` for the
dimension; any wire whose accumulated flow exceeded its `cableType.getTransferRate()` is removed
(`removeConnection`) with a flame particle effect (`:428`–`:440`). The map is then cleared
(`:441`) so each tick starts fresh.

---

## Path-finding and route caching

`getIndirectEnergyConnections` (`ImmersiveNetHandler.java:491`) is the graph traversal. It is a
**Dijkstra-style least-loss search** from the source node:

- A `PriorityQueue<Pair<IIC,Float>>` ordered by accumulated loss (`:501`).
- Seeded with the source's direct neighbours (`:507`).
- Pops the lowest-loss node; if it is an energy output (or `ignoreIsEnergyOutput`), backtracks via
  `backtracker` to assemble the full `subConnections[]` and `minimumType`, and emits an
  `AbstractConnection` (`:557`).
- Relaxes neighbours that `allowEnergyToPass` (`:563`).
- Bounded by `closedListMax = 1200` (`:520`).

**Caching:** results are stored into `indirectConnections` (or `indirectConnectionsIgnoreOut`)
keyed by source node, but **only on the server** (`:580`). On a cache hit (`:494`) the stored set
is returned directly without traversal. The cache is invalidated per connected-component by
`resetCachedIndirectConnections` (`:286`) and per-node by `onConnectivityUpdate`
(`IImmersiveConnectable.java:182`) whenever a wire is added/removed.

So steady-state energy distribution does **not** re-run Dijkstra every tick — it reuses the cached
`AbstractConnection` set. Traversal cost is paid only when the topology changes.

---

## IF <-> FE bridging (api/energy/immersiveflux)

`FluxStorage` (`api/energy/immersiveflux/FluxStorage.java:18`) is the buffer every connector
holds (`TileEntityConnectorLV.java:56`, sized to one tick of `getMaxInput()`). It implements
`IFluxStorage` with `receiveEnergy`/`extractEnergy`/`modifyEnergyStored`.

`EnergyHelper` (`common/util/EnergyHelper.java:27`) is the bridge between IE's IF interfaces and
Forge's `CapabilityEnergy`:

- `isFluxReceiver(tile, facing)` (`:110`) and `insertFlux(tile, facing, energy, simulate)`
  (`:121`) accept *either* an IE `IFluxReceiver` *or* a Forge `IEnergyStorage` capability —
  this is how IE wires deliver to any FE machine.
- `IIEInternalFluxHandler` (`:132`) is implemented by connectors; it routes
  `receive/extractEnergy` through the connector's `FluxStorage` gated by `getEnergySideConfig`
  (only the attached `facing` is `INPUT`, `TileEntityConnectorLV.java:272`).
- `IEForgeEnergyWrapper` (`:189`) wraps a connector as a Forge `IEnergyStorage` so neighbouring FE
  machines can push *into* the wire net. `TileEntityConnectorLV.getCapabilityWrapper`
  (`:256`) lazily builds one per face and exposes it through the capability system.

Flow into the net: machine -> `IEForgeEnergyWrapper.receiveEnergy` -> connector
`receiveEnergy` (`TileEntityConnectorLV.java:286`) -> `FluxStorage`. Flow out of the net:
connector `transferEnergy` -> `end.outputEnergy` (`TileEntityConnectorLV.java:168`) ->
`EnergyHelper.insertFlux` into the target machine.

---

## Persistence across chunk (un)load and world save

### World save — `IESaveData`

`common/IESaveData.java:27` is a `WorldSavedData`. On `writeToNBT` (`:95`) it serializes, per
relevant dimension, every `Connection` from `getAllConnections(dim)` plus every `IICProxy` in
`proxies`. On `readFromNBT` (`:39`) it rebuilds `directConnections` via `addConnection(dim, ...)`
and re-registers proxies, then sets `EventHandler.validateConnsNextTick = true` (`:61`) so the
next server tick prunes any connections/proxies whose endpoints no longer exist
(`EventHandler.java:386`–`:422`). `setDirty` (`:146`) is called from every mutation in the handler
so the save is flushed.

### Chunk unload — the `IICProxy` mechanism

When a connector's chunk unloads, `TileEntityImmersiveConnectable.onChunkUnload`
(`TileEntityImmersiveConnectable.java:372`) registers an `IICProxy` of itself
(`ImmersiveNetHandler.addProxy`, `:382`). The proxy is a lightweight `(dim, pos, canEnergyPass)`
record (`IICProxy.java:29`). `ApiUtils.toIIC(pos, world, allowProxies=true)`
(`ApiUtils.java:340`) returns the proxy when the real TE isn't loaded, so the path-finder can
still route energy *through* an unloaded connector — it just can't be a source or output
(`isEnergyOutput()` -> false, `IICProxy.java:83`). Removing a wire whose other end is unloaded
goes through `IICProxy.removeCable` (`:61`), which force-loads the chunk for one tick to notify the
real TE.

### Chunk (re)load — `onTEValidated`

When a connector validates (loads), `TileEntityImmersiveConnectable.validate`
(`:380`) schedules `ImmersiveNetHandler.onTEValidated(this)` on the next server task
(`ApiUtils.addFutureServerTask`). `onTEValidated` (`ImmersiveNetHandler.java:113`) re-registers the
wire geometry into `blockWireMap` (`addBlockData`), resets the indirect cache for the component,
and clears the proxy at this position (`setProxy(..., null)`) since the real TE is back.

---

## Client sync

Connectors send their connection list to clients via the tile-entity description packet.
`TileEntityImmersiveConnectable.getUpdatePacket` (`:221`) writes NBT including
`writeConnsToNBT` (`:322`), which serializes the server's `getConnections` set into
`connectionList`. `onDataPacket` (`:230`) reads it and calls `loadConnsFromNBT` (`:302`), which —
**only on a dedicated-server client, not singleplayer** — clears and rebuilds the client's local
copy of `directConnections` for that node so the wire renders. `writeCustomNBT` only embeds the
connection list when `descPacket` is true (`:288`), so connections are *not* duplicated into the
chunk save (they live solely in `IESaveData`).

Block events (`world.addBlockEvent(pos, block, -1, 0)`) fired on add/remove
(`ImmersiveNetHandler.java:92`) reach the client through `receiveClientEvent`
(`TileEntityImmersiveConnectable.java:238`) to refresh the smart/connection model cache and
re-render.

---

## Wire rendering: the two halves, and where they meet

A wire is drawn **twice**, once from each end, and each half is baked into the chunk section of the
connector that drew it (`ClientUtils.convertConnectionFromBlockstate`). That is what stops a whole
wire disappearing when the chunk at one end is culled: each end owns the part of the wire nearest
itself. The halves meet on a chunk boundary, so that a half whose far end never arrives fades out at
a chunk edge rather than in mid-air — hence the `fading` alpha on the last segment.

A catenary is always **17 segments regardless of length** (`Connection.vertices`), so on a long run
each segment covers several blocks.

### The bug this had (fixed 2026-07-31)

**Both ends have to choose the same meeting point, and they used to choose it independently.** Each
counted the chunk boundaries crossed by the curve *it* had computed and took the middle crossing.

That is only consistent if both ends compute the same curve, and they do not always. Each end builds
the whole catenary itself, which needs the attachment offset of the connector at **both** ends —
and `ApiUtils.getVecForIICAt` quietly answers `Vec3d.ZERO` when the tile entity it is asking about is
not loaded, because `toIIC` bails on `!world.isBlockLoaded`. An end that bakes while the far end's
chunk is absent therefore computes a curve displaced by about half a block, counts a different
number of crossings, and picks a different meeting point from the one the far end picked. Everything
between the two choices was drawn by neither.

The symptom was a long wire with a hole in the middle of it. Nothing logged, nothing to see but
missing wire, and it appeared to fix itself whenever anything forced the near chunk to bake again —
because by then the far end had usually loaded. Measured over several thousand endpoint geometries,
a disagreement cost up to **eight of the seventeen segments**.

**The fix** is `CatenarySplit.drawUpTo`, which chooses the meeting point from the two **block
positions** and the slack — all of which both ends have exactly and identically — rather than from
the curve either end drew. The half-block attachment offsets are deliberately left out: they are the
one input that can differ, and the meeting point does not need to be accurate, only identical. Each
end still draws its own vertices and simply stops at matching indices, so even when one end has
guessed an endpoint the two halves still meet. The worst remaining symptom is a wire drawn about
half a block off until the far end loads.

Using integer positions also keeps the catenary maths off its own degenerate case: the horizontal
distance is now either zero, which is handled separately, or at least one whole block.

`CatenarySplitTest` sweeps a few thousand geometries and asserts the invariant that matters — **the
two halves together cover every segment** — and is verified to fail against the old behaviour rather
than merely agree with the new.

---

## How to extend

### Add a new connector tile entity

1. Extend `TileEntityImmersiveConnectable` (or subclass an existing connector to inherit
   `transferEnergy`). Implement `ITickable.update()` following the `TileEntityConnectorLV.update()`
   pattern: simulate `transferEnergy`, then apply it, then `notifyAvailableEnergy`.
2. Override `canTakeLV/MV/HV()` to declare accepted wire categories
   (`TileEntityImmersiveConnectable.java:49`).
3. Implement `isEnergyOutput()` / `outputEnergy()` to deliver to the adjacent machine via
   `EnergyHelper.insertFlux`.
4. Hold a `FluxStorage` and implement `IIEInternalFluxHandler` so FE machines can push into the
   wire (expose `IEForgeEnergyWrapper` from `getCapability`).
5. Persist the buffer in `writeCustomNBT`/`readCustomNBT` (`energyStorage.writeToNBT(nbt)`).

### Add a new wire type

Subclass `WireType` (`api/energy/wires/WireType.java:39`) and supply `getTransferRate`,
`getLossRatio`, `getMaxLength`, `getCategory`, and the render fields. Register it with
`WireApi.registerWireType`. The category string controls which connectors will accept it.

### Things to be careful with

- Any change to topology **must** invalidate the route cache. Use
  `resetCachedIndirectConnections(world, node)` (it floods the whole component), not the deprecated
  no-arg variant.
- The handler is global static state shared across all worlds/dimensions; always key by
  `world.provider.getDimension()`.
- `isEnergyOutput()` is queried during traversal *and* during distribution; keep it cheap (it does
  a neighbour-TE lookup, `TileEntityConnectorLV.java:157`).
