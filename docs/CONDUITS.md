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
2. Lengths joined along a surface make a **run**, and a run **wraps around corners** — up the wall
   it reaches, and around the edge of the beam it is running along.
3. A run ends at a **Junction Box**. Lay conduit between two boxes and the run connects itself —
   there is no coil and no linking tool.
4. String an **LV / MV / HV wire** straight to a face of the box, or put a **Connector** of that
   tier — or a **Grid Feed / Service Unit** — against one. The box breaks a free conductor out to
   that face by itself, and wears its plate so you can see which. That is all most circuits need.
   One wire per face, so a box carries up to six circuits.
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

### A run wraps around corners

A run does not stop where its surface does. Two shapes of corner, and they are not the same shape:

| | What happens | Where the next length goes |
|---|---|---|
| **Inner** | A floor run reaches a wall and climbs it | On the wall, one cell up from the last floor length |
| **Outer** | A run reaches the end of the beam it is on and follows the beam's end face down | On that face — a *diagonal* neighbour, bolted to the same last block of the beam |

Both are turned between two lengths of conduit, with no junction box involved. An inner corner is
turned *inside* the lower length's own cell: its arm runs to the wall and then up the wall's face,
which is why an inner corner **needs a block in the corner to turn around**. Take that block away —
a doorway at the foot of the wall — and there is no corner and no join, which is the honest answer
rather than tubing bending through thin air.

An outer corner needs nothing at all: both halves are ordinary arms reaching the same edge from two
sides of the block they are both clipped to. One of the two grows a small cap into the corner cube
itself so the turn has no notch in it; which one is fixed rather than first-come, because two caps
in one place would z-fight.

**Clicking anywhere sensible works.** The wall a run is heading for, the far side of the edge it is
running along, or the exposed face of the last length — all three place the piece that turns the
corner. The last of those is the one that used to hand you a stub floating over the run.

> This reverses the rule the feature shipped with: *a run stays on one surface; a plane change goes
> through a junction box*. That was cheap and defensible on paper and wrong in the hand — the
> gesture somebody makes when a run meets a pole is to keep laying conduit, and being handed a dead
> stub for it reads as the feature being broken. Junction boxes are for splitting and breaking out,
> not a toll on every corner. Reported by a playtester against SimpleLogic's wires, which hug a pole
> and go round its edges.

**A run still arrives at a junction box face on**, never around a corner: a box is a cube in the
middle of its cell, so a run wrapping round the outside of one would have nowhere to arrive. Put the
box *at* the corner instead — it sits in the plane of whichever run reaches it and both runs meet it
flush, which is the gesture the box was always for.

Runs are **discovered, not drawn**. Placing the last length between two boxes creates the
connection; pulling a length out of the middle breaks it. A run ends at the *first* box it meets,
so a corridor with boxes at every corner is a chain, not a mesh.

## The junction box

A patch panel with six faces. **It sits in the plane of the runs that reach it** — bolt one to the
end of a wall run and the box hugs that wall, exactly as the conduit does, and grows out to meet the
run where the run actually is. A box nothing reaches sits on the floor of its cell. That plane is
derived from the neighbours every time the box is drawn rather than stored anywhere, so a box placed
before its run and one placed after it end up looking the same; a box where two planes meet can only
sit in one of them and picks floors over ceilings over walls. See `ConduitGeometry.junctionBoxMount`.

Each face is one of three things:

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

### Wires straight to a face

**A junction box is a wire endpoint in its own right.** Point a coil at the face you want and the
wire attaches there, with no connector bolted to the wall beside it.

This closes the same seam the Grid Feed and Service Units closed in
`commit 701d113d0`: a connector that exists only so that a wire has something to attach to is a
block and a rule with nothing behind them but the order the two features were written in. It also
answers the playtest report directly — wires *did* work beside a box and *did not* work at the box,
which reads as the feature being half-finished, because it was.

The rules:

- **One wire per face.** A face is one breakout on one conductor; two wires on it would be two
  circuits sharing a conductor, which is a short. Six faces is therefore six wires and six circuits.
- **The face is the face you clicked.** `TargetingInfo.side` is already carried through
  `canConnectCable` and `connectCable`, so the gesture says which circuit is meant and nothing has
  to be configured afterwards.
- **Not the face it is bolted to.** The housing lies flush against that one, so a wire there would
  leave from inside the block the box is screwed to. In practice that face is usually not even
  clickable; a box with no runs on it is drawn standing on the floor of its cell, so `down` is the
  one it refuses. The rule is applied when a wire is attached and never afterwards — a box's plane
  moves when the runs reaching it move, and yanking a circuit because somebody laid conduit on the
  far side would be a wire cut by an unrelated action.
- **LV, MV and HV only.** Structural cable holds things up and redstone wire carries no flux;
  neither has anything to do on a conductor. Same acceptance set as a Grid Feed or Service Unit.
- **Attaching to a bare face auto-patches it**, exactly as hanging a connector does, on the lowest
  free conductor. A face that is already patched keeps its colour.
- **Cutting the wire frees the face and never un-patches it.** Same rule as taking a connector down.
  Wirecutters clicked on a face cut *that face's* wire and leave the other five alone — the box's
  `getCableLimiter` answers with the wire on the clicked face, which is what
  `clearAllConnectionsFor` filters on. A run is never cut this way: runs are made by laying conduit
  and removed by breaking it.

A refusal says which rule it hit rather than the generic "you cannot attach this wire here" — see
`IImmersiveConnectable.getCableRefusal` and the `chat.immersiveengineering.warning.conduit.wire*`
keys. Being told "wrong cable" while holding the right cable is how a rule becomes a bug report.

**A face can serve a wire and an adjacent connector at once.** They are the same conductor arriving
at the same face by two routes, so nothing about that is ambiguous, and it is not a second budget:
`drainToBreakout` offers the neighbour first — touching is the stronger claim, the same order the
Grid Service Unit settled on — and offers the wire only what is left.

The three-connectors-round-a-box arrangement is untouched. That is what an underground feeder looks
like, and it still auto-patches and still works.

Under the hood: incoming energy is credited by way of
`IImmersiveConnectable.outputEnergy(amount, simulate, type, arriving)`, a four-argument form added
for exactly this — a connector has one terminal and does not need to be told which of its wires the
energy came down, and a box has six and cannot work without it. Outgoing energy goes through
`WireNetTransfer` with a filter on the route's first hop, so a conductor's energy leaves by *its*
face's wire and no other. The box refuses to be a through-route at all
(`allowEnergyToPass` answers only for the null query), because a route search allowed to walk in on
one wire and out on another would quietly turn sixteen conductors into one wire with extra steps.

### Tiers

There is no tier setting. **The tier of a circuit is whatever hardware is on its face** — the wire
you string to it, or the connector you hang on it. An LV wire makes it an LV circuit, an HV wire an
HV one, and the same for connectors. IE's wires and connectors already cap throughput by tier, and a
second place to say so would only be somewhere for the two to disagree.

So a face's phase is now *whatever is on it*. An HV wire on one face of a box and an LV wire on
another are two circuits of two tiers coming out of the same bundle, capped by their own hardware.
That is Decision 7 of the plan — each channel behaves as its own wire — made visible: the tubing
does not have a tier, the circuit does.

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
- **Crossing a feeder changes surface without a corner.** A run may turn corners on its own now, but
  a corner is a corner — two faces of one block. A feeder is the one thing that lets a run come out
  of a floor on a surface with no geometric relationship to the one it went in on. The conduit on
  the far side still has to have the crossing in its own plane — the same thing a junction box asks
  — so a feeder licenses that without licensing a conduit facing the wrong way entirely.
- **A feeder is passed through, not climbed.** It is a solid cube, so it would otherwise pass the
  test for "is there a block in the corner to turn around". A run reaching one goes through it,
  which is the entire point of the block.

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
  existed, however many circuits a corridor carries — and however many times the run turns a
  corner, which costs `ConduitRoute` a few more probes when somebody builds something and nothing
  at all per tick. The conduit blocks between two boxes are never nodes.
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
visibly switches. Being fed *at all* is what lights it — an LV connector's 256 a tick keeps a
conductor lit exactly as an HV one's does — and the second is a second, not an amount the source has
to keep up with. (Earlier builds charged the decay against what had actually been credited, which was
far more than any LV or MV wire delivers in a tick, so a conductor fed by one went dark on the very
tick it was lit and the run read as dead in city mode.)

A wire strung to a box crosses the two subsystems, and each keeps its own flag: whether the *push*
onto the wire is the lossless one is `cityModeWires`, because that is a property of the wire network
the energy is going onto and of the nodes at the other end of it; whether the box **debits itself**
for what it delivered is `cityModeConduits`, because that is the conduit's own accounting. An
energised conductor delivers to the block against its face and to the wire on it without being
drained by either, which is what makes presence spread rather than divide.

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
