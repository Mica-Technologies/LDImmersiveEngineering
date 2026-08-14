# The Hydraulic Crawler

A drivable tracked excavator with swappable attachments. The fork's first vehicle and its first
ridable entity.

**It is not the Excavator.** Immersive Engineering already has one — the bucket-wheel multiblock,
with its own manual entry, JEI category and the `mbExcavator` advancement. This is the **Hydraulic
Crawler**, "the Crawler" in speech, and the distinction matters because both dig and a shared name
would make every sentence about either one ambiguous.

---

## Driving it

| Control | What it does |
|---|---|
| **W / S** | Throttle, forward and back |
| **A / D** | Steer the tracks — skid steer, so it turns on the spot |
| **Mouse** | Slews the house, cab and arm included |
| **R / C** | Raise and lower the arm |
| **X / Z** | Extend and retract it |
| **V** | Work the attachment |
| **Sneak + V** | Tip the bucket out |
| **G** | Change attachment |
| **Sneak + right-click, holding a hammer** | Take the machine away again — its diesel comes with it |
| **Right-click with a can of diesel** | Refuel |

**Turning your head slews the house.** The operator cannot look around independently of the
machine, which is authentic and is also the sort of thing that has to be said rather than
discovered. The house follows at **six degrees a tick** rather than instantly — see the movement
contract below.

**It is heavy, and it is meant to feel heavy.** The throttle asks for a speed and the machine winds
up to it over about a second; letting go stops it in about a third of one. It steers tightest
standing still and describes an arc at speed, the way a skid steer does. A one-block ledge is
climbed rather than bumped into, and climbing it costs some of the speed you arrived with.

**The arm is aimed, not jointed.** Two pairs of keys say which way it points and how far along that
line the tool sits; the boom and stick solve their own angles to get there. That is a deliberate
choice over six per-joint keys, and it has now paid for itself twice — first when playtesting moved
the arm off the view and onto keys, and again when the second axis was added. Neither change touched
the solver, because it takes an elevation and an extension and does not care where either comes from.

The working envelope is a **band**, not an arc: roughly **2.1 to 5.7 blocks out**, and from **2.8
blocks below the tracks to 5.0 above** them. With elevation alone the tool could only ever be
somewhere on a single circle, so reaching anything meant driving until that circle crossed it.

## The attachments

| Attachment | What it does |
|---|---|
| **Bucket** | Digs, and keeps what it digs in nine internal slots |
| **Grapple** | Picks up one block, or one creature, and puts it down again |
| **Breaker** | Destroys what it touches and drops it on the floor |

The Bucket and the Breaker both take blocks out of the world; the difference is whether you are
expected to want them. Somebody digging a foundation does, somebody demolishing a house does not.

The Bucket never voids what it digs — a full bucket drops on the floor rather than swallowing
anything, because a machine that quietly ate spoil is one nobody could trust with anything they
cared about. Its inventory is also exposed as a capability, so a pipe can unload it where somebody
has built for that.

The Grapple carries a block as a **block state**, not as an item, so a stair comes back down facing
the way it went up. Blocks with a tile entity are refused rather than carried: a chest that came back
empty is worse than one that could not be picked up.

## Demolition, and why it is safe to put on a server

**Every block the machine breaks is attributed to its operator, and with no operator nothing
breaks.** This is not a formality:

- `BlockEvent.BreakEvent` is fired per block, so claim mods, protection plugins and grief logs treat
  the Crawler exactly as they treat somebody swinging a pick.
- `World.isBlockModifiable` is checked as well, because it catches spawn protection and the world
  border, which the event does not.
- The hardness ceiling is obsidian. **Unbreakable blocks are refused before the comparison, not by
  it** — bedrock reports −1, and −1 is less than 50, so a ceiling written as a single "at most" check
  lets the machine dig through the floor of the world.
- Three blocks per bite, ten ticks between bites. Unbudgeted, an arm sweeping through a wall takes
  every block it passes every tick, which is hundreds a second.
- Targets are taken **nearest first**. Three arbitrary blocks from around the bucket punches a random
  pattern of holes; the three nearest eat into a wall from the face the tool is pressed against.

A demolition machine that bypassed any of this could not be allowed on a server and would be the
most destructive thing in the mod.

## Fuel

Diesel, eight buckets, refuelled by hand or at a [Gas Station Pump](PETROLEUM.md). Two millibuckets
a tick with somebody aboard and twelve more while the attachment is working — demolition is what
costs, and driving about is nearly free by comparison.

**The attachment stops at the reserve; the tracks do not.** The worst thing this machine can do to
somebody is run dry mid-demolition, hundreds of blocks from home, in a thing that cannot be pushed
and cannot be picked up without a hammer. The last four hundred millibuckets are for driving back.

**Only a container with fuel in it counts as refuelling.** An empty one is let through to the seat.
The obvious guard — `Utils.interactWithTank`, which every tank *block* in this fork uses — spends
the click for anything that merely *is* a container, and on a machine you climb into that meant
holding an empty bucket made the Crawler silently refuse to open. Draining fuel back out by hand is
deliberately not offered either: it would siphon a bucket out of a fuelled machine when somebody
meant to get in. Dismantling is how you get diesel back, and it hands over the lot.

**The fuel level and the bucket's load are both synced parameters, not fields.** An entity's
`FluidTank` and `ItemStackHandler` are ordinary fields: they go to disk and never to a client, so
the panel — which is drawn client-side off `getRidingEntity()` — read its own empty copies and
reported an empty tank on a full machine. Both now mirror into `DataParameter`s from
`onContentsChanged`, with the NBT paths topped up by hand because neither `FluidTank.readFromNBT`
nor `ItemStackHandler.deserializeNBT` fires that hook.

## How it moves

**The server decides where the machine is, and the client only ever catches up to that.** Everything
about the driving happens on the server: throttle, steering, gravity and the one `move` call a tick.
A client runs none of it. It receives a position and a heading, and slides towards them over three
ticks — the same mechanism a boat uses, with fewer steps, because this machine's tracker sends a
packet every tick and more smoothing would only be more delay between the key and the tracks.

**That split is what "janky" was.** The machine used to run its own gravity and its own `move` on the
client, with a motion vector nothing client-side ever filled in, and then have its position
overwritten by every packet that arrived. So it stood still, jumped forward, stood still, jumped
forward, twenty times a second — and the operator's camera, bolted to a seat on top of it, did the
same. Two authorities disagreeing about where a thing is, at the tick rate.

| Quantity | Value | Why |
|---|---|---|
| Top speed | 0.14 blocks/tick | About what the old impulse-and-friction scheme settled at |
| Reverse | ×0.6 | Slower, as it is on anything tracked |
| Acceleration | 0.008 blocks/tick² | Full speed in about a second |
| Braking | 0.02 blocks/tick² | Stopped in about a third of one, so it never coasts |
| Turn rate | 2.6°/tick at rest | Tightest standing still, ×0.6 of that at full speed |
| Turn ramp | 0.4°/tick², 0.9 unwinding | A turn winds up rather than switching on |
| Slew | 6°/tick | Twice a real machine's ten revolutions a minute |
| Climb cost | 45% of speed per block | A ledge is heaved over, not stepped over for free |
| Interpolation | 3 ticks, snapping past 4 blocks | Smoothing below a teleport, and a teleport above it |

**Nothing is set; everything is a rate that is wound up to.** The throttle asks for a speed and the
acceleration walks towards it, with a separate and faster figure for slowing down, because an engine
and a brake are not the same device. The steering is the same shape with different numbers. All of it
is in `CrawlerDrive`, world-free and unit-tested, for the same reason the geometry is.

**A machine held against a wall banks no momentum.** The speed it carries into the next tick is
reconciled against the ground it actually covered, so an obstacle bogs it down instead of storing up a
throttle's worth of energy to spend the instant the obstacle is dug away — which on a demolition
machine happens several times a minute.

**The house slews at a rate for the same reason the arm's joints do.** It used to snap straight to the
operator's view, which threw the seat (and the camera bolted to it) a block and a half sideways in one
tick on a flick of the mouse, and swept the tool between two headings without ever occupying the space
in between. The joints had been rate-limited since they were written; the one axis that could move the
tool through a wall in a single tick was the one that was not.

## What it looks like

**The machine is one generated OBJ, drawn a group at a time.** It was nine boxes until a
playtester called the model "crude and rudimentary at least", which was fair: nine boxes is what
a `ModelBase` hierarchy is worth writing by hand, and it is not an excavator. It is now about
eleven hundred faces — an undercarriage with a drive sprocket, an idler and four road wheels a
side inside a wrapped tread belt; a house with a louvred engine cowl, an exhaust stack,
handrails, a striped counterweight and a cab whose windows are holes rather than paint; a
tapered boom and stick carrying their rams; and a bucket, a breaker and a grapple, of which the
fitted one is drawn.

| Group | Moves how |
|---|---|
| `undercarriage` | Never, relative to the machine: the frames, the belly and the slew ring |
| `track_left`, `track_right` | Their tread scrolls with the ground each track has covered |
| `wheel_<side>_0..5` | Each turns about its own axle, at the rate its own radius demands |
| `house` | Slews |
| `house_glass` | Slews with the house; drawn last, blended |
| `boom`, `stick` | Rotate about their pins, each carrying everything downstream |
| `tool_bucket`, `tool_grapple`, `tool_breaker` | The fitted one rotates about the tool pin |

**The cab glazing is a group of its own because the glass is drawn in its own pass.** It shipped
opaque, and a playtester in the seat could not see out to drive: the windows were real openings
in real steel, but the glazing was painted at full alpha and drawn in the same pass as the
bodywork, so it came out as a blue-grey wall. Both halves had to change. The generator now paints
the glazing region at about 38% and checks that it is the only region on the sheet that is not
opaque — a partial alpha anywhere else is a hole in the machine. The renderer draws
`house_glass` last, after every opaque group including the arm, with blending on and the world's
alpha threshold dropped, and puts both back afterwards; anything drawn after the glass is not
behind it, it is missing from behind it. The panes stay closed thin boxes rather than single
quads, so each has an outward-facing surface on both sides and reads and lights correctly from
the seat and from outside without turning face culling off.

**Every pivot is the number the box model used**, and that is the whole of what makes this a
change of appearance rather than of behaviour. The arm's hitboxes are placed along its centreline
from the boom's pivot, and the server decides which blocks may be destroyed from the same
arithmetic; a model whose steel had moved would still work perfectly and would break blocks
somewhere other than where its bucket is drawn. `CrawlerAssetsTest` compares the pivots, the
lengths and the wheel table between the Java and the generator, because neither language can read
the other's constants.

**The tracks are wound on by the ground actually covered, not by the throttle.** A machine held
against a wall has a speed and covers nothing, and tracks that ran while the machine stood still
would be the one thing tracks may never be seen to do. The two are wound at different rates while
turning — that is what a skid steer is — and both are measured from the difference between
`prevPos` and `pos`, which the server gets from its own movement and a client gets from sliding
towards it.

**Forge's OBJ support is for blocks, so the model is read here instead.** `OBJLoader` bakes UVs
against the block atlas and hands the result to a block renderer; an entity is drawn with its own
texture bound and its parts moving relative to each other. `EntityOBJModel` reads the file once,
keeps a flat vertex array per group, and draws a group at a time — which leaves the animation in
the renderer's hands, which is the reason for using an OBJ on something that moves at all.

**The generator checks what nothing at runtime would.** Overlapping atlas regions, a face wound
inside out, a UV that has left its region, a shell with a hole in it, a windscreen painted at an
alpha nobody can see through: none of those are errors Minecraft reports. It draws them.

---

## How it is put together

| Concern | Where |
|---|---|
| The entity: driving, riding, fuel, attachments | `common/entities/EntityHydraulicCrawler.java` |
| Dimensions, scale, headings, angle helpers | `common/entities/CrawlerGeometry.java` |
| Acceleration, braking, steering, interpolation | `common/entities/CrawlerDrive.java` |
| The arm's inverse kinematics and forward kinematics | `common/entities/CrawlerArm.java` |
| Which blocks a bite takes, and which may be taken | `common/entities/CrawlerDemolition.java` |
| The three attachments | `common/entities/CrawlerAttachment.java` |
| Arm hitboxes | `common/entities/EntityCrawlerPart.java` |
| Numbers worth tuning | `common/entities/CrawlerConfig.java` |
| The model and its renderer | `client/models/ModelHydraulicCrawler.java`, `client/render/EntityRenderHydraulicCrawler.java` |
| The OBJ reader the model is drawn through | `client/models/EntityOBJModel.java` |
| The operator's panel | `client/gui/CrawlerHud.java` |
| Control input | `common/util/network/MessageCrawlerInput.java` |
| The model and its textures, generated | `docs/tools/make_crawler_obj.py` |
| The in-game manual chapter | `ie.manual.entry.crawler0`–`crawler4`, registered in `ClientProxy` |

**The manual chapter is not decoration.** This machine has six keybinds, two sneak gestures, three
attachments, a fuel it will not substitute and a reserve that stops the tool but not the tracks —
none of it guessable. All of it lived only in this file, which no player opens, so the chapter sits
next to the Excavator's in `CAT_HEAVYMACHINES`: they are the two things in the mod that dig, they
are constantly confused by name, and a reader who has just met one is exactly the reader who needs
telling it is not the other.

### Decisions worth not re-litigating

**Driving and slewing need no packet.** A rider's movement input and look direction are already
synchronised by vanilla, because that is how boats and pigs work. Making the machine a function of
those means the server and client cannot disagree about where the arm is pointing — which matters
enormously, because where the arm is pointing decides which blocks stop existing. Only the arm keys,
the trigger and the swap need a message, and that message carries no entity id: the machine is
whatever the server sees the sender riding.

**The collision footprint is square** because a 1.12 entity has exactly one collision box, it is
axis-aligned, and it cannot rotate. A square footprint is the same box at every angle, so neither
steering nor slewing can make it wrong.

**The arm's hitboxes are damage colliders, not movement colliders.** They are the Ender Dragon's
`MultiPartEntityPart`, positioned each tick from the forward kinematics, and `World`'s entity queries
find them by walking `getParts()`. Giving them collision boxes would make the arm something a player
is blocked by, and a blocking box that sweeps into somebody leaves them stuck inside geometry —
Minecraft resolves a moving entity against a standing one by refusing to let the standing one in. So
"ride in the cab" is a promise and "stand on the boom while it swings" is not.

**Every angle the machine is drawn at is interpolated, and none may be snapped.** The pose is four
synced floats that change once a tick, and the joints step four degrees at a time; drawn straight from
them, the arm moves at twenty frames a second on a screen refreshing at several times that. The
house's rotation is taken *relative to the interpolated heading*, not to this tick's — subtracting the
un-interpolated one drew the cab swinging back and forth across the tracks by a tick's worth of
steering, every frame, but only while turning.

**All the geometry is world-free and unit-tested.** The test harness has no Minecraft bootstrap, so
an `Entity` cannot even be constructed — anything left on the entity is code no test will ever run.
The inverse kinematics, the joint limits, the points along the arm and the demolition targeting are
all pure functions, which is the entire test surface and is only that large because it was built that
way on purpose.

### What is deliberately not here

**Damage and repair.** The machine refuses damage outright and is dismantled with a hammer, so there
is nothing for a durability system to respond to. Adding one would mean first inventing ways to hurt
it.

**Rotation of the carried block, and a bucket GUI.** The Grapple puts a block back exactly as it came
up but cannot turn it; the Bucket is emptied by gesture or by pipe rather than through a window. Both
are additions rather than gaps.

**Sound.** A diesel machine with no engine note feels wrong, and Minecraft's sound system makes a
convincing looping engine awkward. It deserves its own pass.
