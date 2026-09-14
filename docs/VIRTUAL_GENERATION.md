# Virtual Generation

A fork-only feature: a power plant that keeps supplying its wire network (or its Virtual Grid Feed
Unit) while its chunks are **unloaded** — with no chunk loader and nothing kept loaded.

This is not stock Immersive Engineering.

---

## The problem it solves

A generator is a tile entity, and a tile entity only ticks while its chunk is loaded. IE's wire
network already carries power *through* unloaded chunks (connectors leave an `IICProxy` behind when
their chunk unloads), but nothing can put power *in* from an unloaded plant. So a town at the far end
of a long line has power only while somebody is standing near the plant — which in practice means
somebody is standing near the plant *and* somebody is in the town.

The usual answer is a chunk loader at the plant. On a large map that is a poor trade: a loaded chunk
ticks every entity, block update and tile entity in it, forever, whether or not anybody wants the
power.

Virtual generation keeps the plant's **measured output** flowing instead. Only consumers that are
loaded anyway receive it.

---

## The Generation Meter

`grid_device` meta 4, `TileEntityGenerationMeter`. It is part of the grid block family for its
styling and asset tests, but **needs nothing from the grid**: no segment, no Feed or Service Unit, and
it works with `enableVirtualGrid=false`.

**Placement.** Against the generator's output face — the meter faces the block it was placed against,
exactly as a connector does — with the wire connector bolted onto its front:

```
[generator] -> [Generation Meter] -> [wire connector] ==wire==> ...
```

**Pass-through.** The generator pushes into the meter's back; the meter hands every insert straight on
out of its front and holds no energy of its own. A meter in the line changes nothing about how much
reaches the wire.

**What it measures.** What the generator *offers* on real (non-simulated) inserts, not what is
accepted. IE's generators hand their whole production to the neighbouring block on every insert, so
the offer is the production — and a plant whose consumers are all full still measures at full
strength instead of reading zero. The figure is the **rolling peak of per-second averages** over
`virtualGenMeasureWindowSeconds` (default 60), which is what the plant sustains while running. See
`OutputMeter`.

**Warm-up.** A meter's window starts empty whenever its chunk loads. Until a whole window has been
observed, a new reading may only *raise* the recorded output — otherwise a player flying past would
load the plant for a second, reset it to zero, and leave the town dark behind them
(`OutputMeter.chooseRate`). A missing generator zeroes the record immediately.

**Readout.** Right-click: the generator and whether it burns fuel, what the meter feeds, the measured
output, and what the plant will supply while unloaded — or why it will not.

---

## When a plant runs virtually

Every server tick (`VirtualGenTickHandler`, after the worlds have ticked), for each metered plant:

1. **Real or virtual.** If the generator, the meter and the thing it feeds are *all* loaded, the plant
   is generating for real and nothing extra happens. If any one is unloaded, the real path is broken
   and the plant runs virtually. Never both — supply is never doubled.
2. **Qualification.**
   - Normal mode: only **fuel-free** generators — `TileEntityDynamo` (kinetic) and
     `TileEntityThermoelectricGen`, plus any block listed in `virtualGenFreeSources`.
   - City Mode (`CityMode.wires()`): **every** generator qualifies, and a fuel-burning plant burns
     nothing while unloaded. Its tank drains normally whenever it is loaded.
3. **The push.** From the connector's position, exactly as that connector's own tick would:
   `WireNetTransfer.city` in City Mode, simulate-then-commit `WireNetTransfer.transfer` (with loss)
   in normal mode. Capped at the connector's own rate and `virtualGenMaxRate`. Meters feeding one
   connector push once, summed.
4. **Reach.** The route search walks unloaded poles through their proxies as it always has; an
   unloaded *consumer* resolves to a proxy whose `outputEnergy` is 0. Nothing is ever loaded.

The model (`api/energy/virtualgen`) is pure logic behind an `IVirtualGenWorld` port and is unit-tested
against a fake world (`VirtualGenEngineTest`, `OutputMeterTest`).

---

## Virtual Grid Feed Units

A meter in front of a **Feed Unit** instead of a connector keeps that unit supplying its segment:

```
[generator] -> [Generation Meter] -> [Feed Unit]  ~~segment~~>  [Service Unit]
```

The record is flagged `feedsGrid` and is not pushed onto wires. The grid engine asks, through the
`IVirtualFeedSupply` seam, what the plant in front of each enabled feed can still supply, and counts
it only while that plant's real path is broken — so a Feed Unit that is itself loaded, fed by a plant
across a chunk border that is not, is covered too.

- Normal mode: added to the segment after the live feeds, under the same input budget, buffer room,
  loss and per-device cap. A loaded feed that already drew something real gets only its remaining cap.
- City Mode: counts as the feed's proof of a live source, so the segment stays energized.
- The console lists such a feed as **virtual** rather than offline.

---

## Configuration

`[virtualGeneration]` in `immersiveengineering.cfg`, mirrored into `VirtualGenConfig`:

| Key | Default | Meaning |
|---|---|---|
| `enableVirtualGeneration` | true | Master switch. Off: an unloaded plant simply stops, as in stock IE. |
| `virtualGenMeasureWindowSeconds` | 60 | How far back the rolling peak looks. |
| `virtualGenMaxRate` | 32768 | Ceiling on one meter's virtual output, IF/t, on top of the connector's rate. |
| `virtualGenFreeSources` | (empty) | Registry names of other mods' fuel-free generators, for normal mode. |

The recipe (steel plates, an Engineer's Voltmeter, copper coils, an electron tube) can be changed or
removed with CraftTweaker on packs that want to restrict who builds always-on plants.

---

## Tools and commands

- **`/ie virtualgen`** (permission 2) — every metered plant: position, measured rate, virtual cap,
  fuel-free or not, grid or wire, and whether it is running for real, virtually (with what it delivered
  last tick), or idle.
- **`/ie demo plant [length] [grid]`** (permission 4) — builds a thermoelectric plant, a meter, and either
  a copper relay line `length` blocks long ending in a capacitor, or (`grid`) a Feed Unit and a distant
  Service Unit on the segment "demo plant". Build it **away from world spawn** — spawn chunks never
  unload, so a rig there is always running for real.

---

## Persistence

Records live in their own save file, `data/ImmersiveEngineering-VirtualGenData.dat`
(`VirtualGenSaveData`), keyed by dimension and meter position. Breaking a meter removes its record;
because world edits and `/setblock` skip `breakBlock`, a sweep every 200 ticks also drops records whose
meter position is loaded but no longer holds a meter.

---

## Performance

- The tick handler returns immediately when no meters exist.
- One cached route walk per metered connector per tick, the same walk that connector's own tick does
  while loaded. Grid feeds cost a scan of the (few) records per enabled Feed Unit.
- Nothing loads, nothing is kept loaded; the whole point is to replace chunk loaders.

---

## Source map

| Area | Location |
|---|---|
| Model, engine, config mirror | `api/energy/virtualgen/` |
| Grid seam | `api/energy/grid/IVirtualFeedSupply.java`, `GridEngine.collectVirtual` |
| Tick driver, save data | `common/util/virtualgen/` |
| The meter | `common/blocks/grid/TileEntityGenerationMeter.java` |
| Commands | `common/util/commands/CommandVirtualGen.java`, `CommandDemo` (`plant`) |
