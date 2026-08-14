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
4. Put an **LV / MV / HV Connector** — or a **Grid Feed / Service Unit** — against a bare face of a
   box. The box breaks a free conductor out to that face by itself, and wears its plate so you can
   see which. That is all most circuits need.
5. To choose *which* conductor instead, right-click the face with a **dye** first.
6. To get a run through a floor without a box showing, set a **Ground Feeder** into it. It wears
   whatever is around it.

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

### Auto-patching

**A bare face with power hardware bolted to it patches itself.** Put an LV, MV or HV connector — or
a Grid Feed or Service Unit — against a junction box and the box breaks the lowest free conductor
out to that face, wearing the plate as if it had been dyed by hand.

This is the whole of what "connectors should connect to conduit with ease" needed. Before it, the
block that made a connector work was a *dye*, which is not a thing anybody guesses; a connector hung
on a box did nothing, silently, and read as the feature being broken. Dyeing still works and still
overrides — it is how you choose *which* conductor rather than *whether*.

Three rules keep it from being a nuisance:

- **Only wiring hardware.** A connector (never a relay: a relay neither takes nor gives energy, and
  `EnergyHelper.acceptingSide` answers null for one) or a Feed or Service Unit. Not any neighbour
  that happens to accept flux — a box dropped beside a capacitor bank to turn a corner must not
  quietly start draining the run into it, and "connectors and grid boxes claim a face" is a rule a
  player can state.
- **Lowest free conductor, never one already broken out on that box.** The same conductor arriving
  at two connectors is a short. A box with all sixteen spoken for hands out nothing rather than
  stealing one from a working circuit. `JunctionBoxLogic.firstFreeChannel` owns that and is tested.
- **It never un-patches.** Taking a connector down leaves the breakout where it was; the alternative
  is a box that forgets a deliberate configuration the moment something is mined next to it.

It runs on neighbour change and on load. On load as well, because a box placed against hardware that
was already there hears nothing afterwards — settled blocks never fire another update.

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

## The ground feeder

A junction box is the right way to leave a surface indoors, where it reads as hardware bolted to a
wall. Outdoors it is wrong twice over: a steel box sitting in a lawn is the most conspicuous object
for a hundred blocks, and there is no surface to change *to* until the run is already underground.

The **Ground Feeder** is the other answer. It is a whole cube that fills a hole in a floor, a wall or
the ground, passes a bundle straight through along one axis, and **draws itself as whatever is around
it**.

### What it wears

Placed, or when its surroundings change, a feeder surveys the twenty-six blocks around it and puts on
the one it finds most of — but *most of* is scored rather than counted, and that distinction is the
whole rule.

The obvious implementation gets the single most important case wrong. A feeder set into a grass field
has eight grass blocks around it at its own level and nine dirt blocks underneath, so a plain count
wears **dirt, in the middle of a lawn**. The count is not wrong about the numbers; it is wrong about
which blocks are the surroundings. So a candidate is weighted twice:

| Weighting | Multiplier | Why |
|---|---|---|
| The block is **exposed** — some face of it touches something that is not a solid block | ×3 | Buried dirt is not part of what anyone sees. The ring of turf *is* the lawn. |
| The block **shares a face** with the feeder rather than meeting it at a corner | ×2 | What you touch is a stronger claim than what you nearly touch. |

The lawn then comes out grass, 36 to 10, and a feeder in bedrock still comes out stone, because down
there the stone is what is exposed to the shaft. `ConduitCamouflage` owns this and takes the world as
an interface, so the lawn is tested as a lawn drawn out of strings.

**Full opaque cubes only.** A feeder is a cube and always will be, so wearing a fence's model would
draw a fence with a solid hitbox and wearing a chest's would draw a chest whose lid never opens —
both worse than the bare block, because both look like a bug rather than a disguise. Anything with a
tile entity, anything not drawn by an ordinary model, and the conduit block itself are all refused.

**Right-click it with any full block to pin that block instead**; sneak and right-click it
empty-handed to hand it back to the survey. A pinned feeder stops surveying, because a guess that
overrules the person who corrected it is not a feature.

**A survey that finds nothing changes nothing.** The survey cannot tell a feeder hanging in open air
from one whose neighbours are not loaded yet, so "nothing" is never taken as an instruction to
undress: a feeder keeps its last look until it has something better to put on. Without that, a feeder
at a chunk edge would come up bare on some loads and not others, and nothing would ever put it back —
settled terrain never fires another neighbour change. For the same reason a feeder only re-surveys on
load if it never found anything in the first place.

### What it does to a run

**Nothing, as far as the graph is concerned.** A feeder is never a node in the wire graph, holds no
energy, has no `update` and does nothing per tick. `ConduitRoute` slides straight through it, so a run
crossing four feeders is still one `Connection` between the same two junction boxes — four blocks
longer, and no more expensive. Everything that makes conduit cheap stays true with feeders in it.

It carries the **whole bundle** and splits nothing. A patched face on a box wears a plate in its
conductor's colour so the box says how it is wired from across the room; hiding a box would throw
exactly that away and leave a base whose wiring could only be found by digging. If you want a
conductor out, that is what the box is for, and it stays where you can see it.

Two rules follow from the axis:

- **A run passes through along one axis only.** An Engineer's Hammer turns it. A feeder laid across
  the grain of a run stops it exactly as a stone block would — and stops it *visibly*, because the
  conduit draws its arm on the same test the walk uses, so a run halted by a sideways feeder also
  looks halted.
- **Crossing a feeder is the one place a run may change surface.** The conduit on the far side still
  has to have the crossing in its own plane — the same thing a junction box asks — so a feeder
  licenses a plane change without licensing a conduit facing the wrong way entirely.

### Waking the boxes

A junction box rebuilds its runs when one of its *own* neighbours changes. That covers every gesture
that finishes a run **at** a box, which is how conduit is normally laid: outward from one box until
the last length lands against the far one.

Dropping a feeder into a floor with conduit already above and below it is the other gesture — it
finishes a run in the **middle**, where neither box is anywhere near the block that did it. Before
this the run would be joined on the wall and absent from the graph until something else happened to
poke a box or the chunk reloaded, which reads as the feature intermittently not working.

`ConduitRoute.junctionsAround` walks out from a length of run and reports the boxes on it, and
`TileEntityConduit` calls it whenever its connection mask actually changes. That is once per building
action and never otherwise. It fixes the same latent case for plain conduit joined in the middle.

### What it does not do

- **Particles are metal, not turf.** The particle texture belongs to the model rather than to a
  position, so breaking or hitting a feeder shows bare steel whatever it is wearing. Arguably the
  better failure: it tells somebody who has forgotten where they buried one that they have found it.
- **The disguise does not survive being picked up.** Break a feeder and it drops a feeder; place it
  again and it surveys again. A pinned one is one right-click to restore.

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
| Ground Feeder | A conduit between two aluminium plates, in a column |

## Configuration

| Option | Default | Effect |
|---|---|---|
| `cityModeConduits` | `true` | Opt this subsystem into city mode (needs `cityMode` on as well) |

Conduit has no options of its own. The per-channel rate follows IE's steel wire and the tier of any
given circuit is set by hardware, so there is nothing left that wants a number in a file.
