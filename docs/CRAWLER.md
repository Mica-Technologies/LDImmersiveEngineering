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
discovered.

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

---

## How it is put together

| Concern | Where |
|---|---|
| The entity: driving, riding, fuel, attachments | `common/entities/EntityHydraulicCrawler.java` |
| Dimensions, scale, headings, angle helpers | `common/entities/CrawlerGeometry.java` |
| The arm's inverse kinematics and forward kinematics | `common/entities/CrawlerArm.java` |
| Which blocks a bite takes, and which may be taken | `common/entities/CrawlerDemolition.java` |
| The three attachments | `common/entities/CrawlerAttachment.java` |
| Arm hitboxes | `common/entities/EntityCrawlerPart.java` |
| Numbers worth tuning | `common/entities/CrawlerConfig.java` |
| The model and its renderer | `client/models/ModelHydraulicCrawler.java`, `client/render/EntityRenderHydraulicCrawler.java` |
| The operator's panel | `client/gui/CrawlerHud.java` |
| Control input | `common/util/network/MessageCrawlerInput.java` |
| Textures, generated | `docs/tools/make_crawler_textures.py` |
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
