# Conduits

Surface-mounted wiring for the inside of buildings, and the third answer this fork gives to
"how does power get from there to here".

- **Wire** sags between two points. Right across a valley, wrong along a ceiling.
- **The [virtual grid](VIRTUAL_GRID.md)** removes the physical link entirely. Right across a map,
  and deliberately invisible.
- **Conduit** clips flat to a surface and turns in right angles. Right along a corridor, and the
  only one of the three you can point at.

They are complementary, not competing. If the goal is fewer wire entities on a big build, the grid
already does that; conduit is for the look and for keeping sixteen circuits tidy in one channel of
tubing.

---

## The short version

1. Craft **Conduit** (8 at a time) and place it against a wall, floor or ceiling.
2. Lengths joined along the same surface make a **run**. A run stays on one surface.
3. A run ends at a **Junction Box**. Lay conduit between two boxes and the run connects itself —
   there is no coil and no linking tool.
4. Right-click a box's face with a **dye**: the conductor of that colour leaves by that face.
5. Put an **LV / MV / HV Connector** against that face. It picks up that conductor and nothing else.

One length of conduit carries **sixteen** independent conductors, named after the sixteen dyes.

---

## Runs

A conduit clips to whatever face you clicked and runs in the plane of that face — four possible
directions, turning in right angles, forming straights, corners, tees and crosses.

**To continue a run, click the end of it.** A conduit placed against another conduit joins that
conduit's surface rather than clipping to the conduit; the first length off a junction box looks
for the surface behind it rather than clipping to the box. Clicking bare wall is unchanged, so this
is a wider rule rather than a different one. See `ConduitPlacement`, which owns it and takes the
world as an interface so the decision is tested against a drawn building.

**A run stays on one surface.** Conduit on a floor does not climb the wall by itself. That is
deliberate rather than a shortfall: a plane change goes through a junction box, which is a real
block and what you would put at a corner anyway. It is also what keeps a run to an axis-aligned
shape instead of an L-shaped transition per pair of faces.

Runs are **discovered, not drawn**. Placing the last length between two boxes creates the
connection; pulling a length out of the middle breaks it. A run ends at the *first* box it meets,
so a corridor with boxes at every corner is a chain, not a mesh.

## The junction box

A patch panel with six faces. Each face is one of three things:

| Right-click with | Effect |
|---|---|
| A **dye** | That face breaks out the conductor of that colour |
| **Redstone dust** (on a patched face) | Cycles that face: power → reads redstone → emits redstone |
| **Nothing**, while sneaking | Unpatches the face |

**A patched face wears a plate in its conductor's colour.** A box says how it is wired from across
the room rather than only under the crosshair. The plate is painted near-white and tinted per face
through `BlockConduit.getRenderColour`, so the sixteen colours cost six models rather than
ninety-six; the tint index *is* the face's `EnumFacing` ordinal, and `ConduitAssetsTest` guards that
because drift there would put the right colours on the wrong faces with nothing logged.

**A colour lives on one face at a time.** Patching it somewhere new takes it off wherever it was —
the same conductor arriving at two connectors would be a short, not a feature.

**A box passes the whole bundle through whatever is patched.** A breakout says where a conductor
*leaves*, not whether it exists, so a box dropped in purely to turn a corner or change surface
needs no configuration at all.

### Tiers

There is no tier setting. The tier of a circuit is whichever connector you hang on its breakout:
an LV connector makes it an LV circuit, an HV connector an HV one. IE's connectors already cap
throughput by tier, and a second place to say so would only be somewhere for the two to disagree.

Mixed tiers in one bundle are fine — an LV channel and an HV channel share a run happily, because
the tier belongs to the conductor rather than to the tubing.

### Capacity

Each conductor carries what a conductor carries. A bundle is **not** a shared pipe with an
allowance to divide up: sixteen circuits down one corridor get sixteen circuits' worth of
throughput. Replacing sixteen catenaries with one conduit costs you nothing but the crafting.

## Redstone

A face set to **read** puts the redstone signal arriving at it onto its conductor. A face set to
**emit** puts its conductor's signal back out as redstone. The signal reaches every box on the run,
so a lever at one end of a corridor throws a lamp at the other along the same bundle already
carrying the lighting circuit.

A face does one of the three and never two. Reading and emitting on one face is the classic way to
build a network that latches itself on and never lets go.

Signals are re-derived when something changes rather than every tick, and the whole run is
recomputed from every input on it — which is what makes switching the last lever off actually turn
the run off.

---

## Performance

The claim this whole design rests on: **a bundle must not cost more per tick than the wires it
replaces.** Two structural properties hold it up.

- **One edge per run.** Sixteen conductors down a corridor are a single `Connection` in the wire
  graph, not sixteen. Everything that walks that graph sees the graph it saw before conduits
  existed, however many circuits a corridor carries. The conduit blocks between two boxes are
  never nodes.
- **Idle is free.** A junction box carrying nothing does one integer comparison per tick. A base
  with two hundred boxes and three live circuits costs two hundred comparisons.

Energy moves as a bucket brigade: each box hands half the difference to whichever neighbour is
holding less, and drains in full into any connector on the matching breakout. Nobody walks the run
looking for sinks — a box with a machine on it empties, which opens a gradient, which pulls from
next door. IE's wires do the search-every-tick version, and with sixteen channels that would be
sixteen path walks per box per tick.

The honest cost of that choice: **one tick per hop.** A long trunk with boxes at every corner ramps
up over a handful of ticks when a load comes on.

See [`CITY_MODE_AND_PERF.md`](CITY_MODE_AND_PERF.md) for the wider performance story.

## City mode

With `cityMode` and `cityModeConduits` on, a bundle switches from accounting to presence: a
conductor is either energised or it is not, an energised breakout delivers at full rate, and no
line loss is charged. This is the same trade the virtual grid makes, for the same reason — in a
decorative build the answer to "is this corridor lit" is yes, and the arithmetic that established
it was spent proving something nobody was going to look at.

A conductor still goes dark about a second after whatever fed it stops, so a switched circuit still
visibly switches.

Turning the master `cityMode` switch off always restores stock behaviour.

---

## Recipes

| Item | Recipe |
|---|---|
| Conduit (×8) | Aluminium plate / copper wire / aluminium plate, in three rows |
| Junction Box | A conduit surrounded by four aluminium plates |

## Configuration

| Option | Default | Effect |
|---|---|---|
| `cityModeConduits` | `true` | Opt this subsystem into city mode (needs `cityMode` on as well) |

Conduit has no options of its own. The per-channel rate follows IE's steel wire and the tier of any
given circuit is set by hardware, so there is nothing left that wants a number in a file.
