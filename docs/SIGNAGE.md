# Utility pole signage

Sixteen kinds of tag bolted flat to whatever holds the wires up, and a two-gesture interface for
choosing between them and writing on them.

**A grid you cannot read is a grid you cannot maintain.** That is the whole argument for the block,
and it came from somebody who does the reading: a hundred poles across a map are a hundred identical
poles until each one says which station feeds it, which feeder it is on and who last inspected it.

Every kind here is a sign that exists. The shapes, the colours and what each one means are the
*Los Angeles Department of Water and Power*'s and *Southern California Edison*'s, taken from a
playtester's reference photographs, and they are told apart the way the real ones are: by shape and
colour, long before anybody is close enough to read the number.

---

## The short version

| | |
|---|---|
| Block | `immersiveengineering:signage`, one meta (`utility_sign`) |
| Item | One. Four per two Aluminium Plates |
| Kinds | Sixteen, on the tile entity — not sixteen items and not sixteen metas |
| Placing | Against a **horizontal** face with something behind it: a wall, or a pole the plate reaches back to |
| Choosing a plate | **Engineer's Hammer**: steps to the next kind |
| Writing on it | **Sneak + Engineer's Hammer**: opens the editor |
| Lines | 0–3 depending on the kind, auto-scaled to fill the plate's *paint* — see below |
| Per-frame cost | The lettering only, and only within 48 blocks |

---

## The sixteen kinds

Ordinals are what a sign saves, so `UtilitySignKind` may only be appended to.

| # | Kind | Plate | Shape | Lines | Text |
|---|---|---|---|---|---|
| 0 | `PARALLEL_GENERATION` | 14×6 red strip | `RECT` | 2 | white |
| 1 | `YELLOW_VERTICAL` | 6×14 yellow strip | `RECT` | 1 | black, turned |
| 2 | `WHITE_VERTICAL` | 6×14 white strip | `RECT` | 1 | black, turned |
| 3 | `SILVER_VERTICAL` | 6×14 bare metal strip | `RECT` | 1 | grey, turned |
| 4 | `OVAL_FRACTION` | 12×8 white oval | `ELLIPSE` | 2 | black |
| 5 | `YELLOW_HORIZONTAL` | 14×4 yellow strip | `RECT` | 1 | black |
| 6 | `ORANGE_HORIZONTAL` | 14×4 orange strip | `RECT` | 1 | black |
| 7 | `ORANGE_VERTICAL` | 6×14 orange strip | `RECT` | 1 | black, turned |
| 8 | `INSPECTION_ROUND` | 10×10 silver disc | `ELLIPSE` | 2 | grey |
| 9 | `TOWER_DIAMOND` | 12×12 yellow diamond, no border | `DIAMOND` | 1 | black |
| 10 | `LINE_CROSSING_DIAMOND` | 12×12 yellow diamond, outline and cross | `DIAMOND` | 0 | — |
| 11 | `TOWER_VERTICAL` | 6×16 pale yellow strip | `RECT` | 3 | black, two groups down + circuit across the foot |
| 12 | `TOWER_HORIZONTAL` | 14×4 yellow strip | `RECT` | 1 | black |
| 13 | `TOWER_SHORT` | 8×16 pale yellow strip | `RECT` | 2 | black, one group down + circuit across the foot |
| 14 | `TOWER_NUMBER` | 10×16 amber panel | `RECT` | 1 | black, turned a quarter round |
| 15 | `TRANSFORMER_GROUNDED` | 14×6 green strip | `RECT` | 2 | white |

**The three tower identifiers were redrawn from photographs**, after the playtester who supplied the
first set said they were not right. The tall one had three lines read *across* an eight-pixel plate
with a rule printed at ⅔ height; the real one is narrower, has no rule at all — the groups are
divided by a gap and nothing else — and its first two groups are columns of upright characters read
*downwards*, with only the circuit written across the foot. `SignDivider.TOWER_RULE` went with the
rule. The short one is the same tag without the tower number, cut *wider* rather than shorter so
that dropping a group does not print the remaining letters half again as large. The number-only one
is not a plate: it is the number stencilled onto the leg of the tower, which is the one place in the
set where a line really is turned a quarter round.

`TRANSFORMER_GROUNDED` is `PARALLEL_GENERATION` in green — same plate, same two lines, same white
lettering — because on a real pole it is the same kind of strip saying a different thing, and the
colour is what tells them apart from the ground. The two-pole banks with the jumbo transformers wear
it, and there was nothing in the set for them.

What each means in the field is in the manual chapter (`docs/manual/chapters/signage.tex`) and in
the `UtilitySignKind` javadoc; it is deliberately not repeated in the code as data, because nothing
in the game branches on it.

**Every plate is an even number of pixels across and down.** Odd would put the plate's edge on a
half-pixel, which samples between two texels and comes out of the atlas as a blurred fringe — on a
six-pixel strip, most of the sign. `SignageTest` asserts it.

---

## Where the lettering goes

`SignLayout`, shared by `TileRenderUtilitySign` and `GuiUtilitySign` — a preview that lays text out
by its own arithmetic is a preview that lies as soon as either side is touched.

**A line is printed to fill the plate rather than typeset at a fixed size**, which is what somebody
who reads real ones means by "resizable": a short number comes out big and a long one comes out
small, exactly as they do on a pole. It is scaled to whichever limit it hits first — the plate it
has to fit along, or its share of the plate's depth.

**Filling the plate means filling the paint, not the sprite.** The first version fitted every line
to the sprite's bounding box, and every one of them had text sitting on its border or, on
the round and pointed kinds, hanging off the plate entirely. Four things fix that, and all four are
in `SignLayout`:

| | |
|---|---|
| `SignShape.getInset()` | The painted outline is not printable plate. One pixel on everything with a border; half on the plain tower diamond, which has none. |
| `SignShape.spanAt()` | A plate is not the rectangle it is drawn inside. An oval or a diamond narrows away from its middle, so a line is fitted to how much plate is left **where its tallest letter reaches**, not at its centre. `getStackFactor()` is the matching rule for the stack's depth: the largest box inscribed *in* the shape rather than around it. |
| `PADDING` | A tenth of the way in on every side, so the lettering is held off the border rather than stopping against it. A fraction rather than a number of pixels, because a 14×4 strip has two pixels of plate inside its border and no more. |
| `INK_HEIGHT` | A glyph cell is eight pixels tall and a capital paints seven of them. Scaling and centring by the cell puts every line half a pixel high and half a pixel taller than the plate was measured for. Both draw sites offset by the ink instead — and by `inkWidth`, since `getStringWidth` counts the gap after the last character too. |

`LEADING` keeps a stack of lines from meeting, and a plate with something printed across it —
`OVAL_FRACTION`'s bar, `INSPECTION_ROUND`'s nail — declares `getRuleAfterLine()` so that its lines
are divided either side of that rather than laid over it.

### Stacked plates, and the one that lies on its side

`SignTextFlow` says which way a plate's lettering runs, and three of its four values are not
"across":

| | |
|---|---|
| `DOWN` | A column of upright characters, one to a row, reading downwards. The vertical strips — a strip six pixels wide holds a pole number no other way. Its rows are the characters of the kind's one line, not lines of their own. |
| `DOWN_FOOT_ACROSS` | The same, in groups, except the last line, which reads **across the foot** of the plate. Both transmission tower identifiers: the tower number and the station pair read downwards, and the circuit — "L1" — is two characters side by side at the bottom. |
| `TURNED` | One line lying on its side, reading top to bottom with every character's top edge toward the right. `TOWER_NUMBER` and nothing else. This is the rotation that was **wrong** for the strips and is right here, because on a tower leg it is what is really painted. It is also the only case where `getTextSpan()` and `getTextDepth()` swap. |

`SignLayout.planStack` divides a stacked plate's depth: one row per character of each group that
reads downwards, one row for a group that reads across the foot, and `GROUP_GAP` — three quarters of
a row — between groups. **Every row on the plate is the same depth**, and that is the point of
planning the plate rather than each group: giving each group an even band of its own printed a
three-character group half again the size of a seven-character one under it, which is exactly what a
photograph of the real tag says it is not. An empty line claims no rows and takes its gap with it,
so filling in the first and third boxes gives two groups snug on the plate rather than a hole where
the second would have been.

The foot group goes through `rowScale` with its whole string's width rather than through
`stackedScale` with the widest character's: a column is scaled by one letter because each letter is
centred on its own, and a word is not.

`SignageTest` walks every kind, every line and a spread of string widths and asserts the painted box
lands inside the shape; it also reads the sprites back off disk to check that each plate is really
cut to the shape its kind declares, and that the tower plate's rule is drawn in the gap the layout
leaves for it.

---

## Where a kind lives

**On the tile entity, and in a listed block property filled from it.** Sixteen kinds times four
facings and seven reaches is 448 states, which is affordable — the *text* is what is not, and once the text has
to be on the tile entity there is no reason for the kind to be anywhere else.

`TileEntityUtilitySign` implements `IAttachedIntegerProperies`, which is what
`BlockIETileProvider.getActualState` reads to fill `BlockUtilitySign.KIND`. That lets the blockstate
be a plain Forge `variants` file — a `facing` submap supplying a y-rotation and a `kind` submap
supplying a model — instead of a smart model with a loader behind it. Sixteen flat textured slabs
do not need one.

Both blockstate files have to exist: `signage.json` carries the `inventory` variant the item model
resolves through, and `signage_utility_sign.json` carries the block's own, named by
`BlockUtilitySign.getCustomStateMapping`. A custom state mapping with no matching file is one of the
two silent causes of a purple block in 1.12 and neither logs anything. **Every listed property has
to appear in the submaps**, for the same reason. `SignageTest` checks all of it.

---

## The two gestures

`hammerUseSide` on the tile:

- **Hammer** → `kind.next()`, wrapping.
- **Sneak + hammer** → `CommonProxy.openGuiForTile`.

The hammer bypasses the vanilla sneak-use check (`ItemIETool.doesSneakBypassUse`), which is what
lets the sneaking form reach `onBlockActivated` at all. It is the same route the junction box's
colour cycle takes — see [CONDUITS.md](CONDUITS.md), which also records the off-hand retry that
made that one misbehave.

### The editor

`GuiUtilitySign`, over a slotless `ContainerUtilitySign` whose only job is to *be* the permission
check for `MessageSignText`.

**The preview is the point.** Sixteen kinds is sixteen shapes, colours and text layouts, and
choosing between them from a list of names would mean hanging one, climbing down, looking, and
climbing back up. The preview draws the real atlas sprite at four times size with the real lettering
laid out by `SignLayout` — the same arithmetic the world renderer uses, shared precisely so a
preview cannot lie.

**Text is sent before the window closes, never after.** `closeScreen` sends the vanilla
close-window packet and only then runs `onGuiClosed`, so text sent from there arrives at a server
that has already swapped the player's container back to their inventory — and `MessageSignText`
refuses it, correctly. Every line typed was silently thrown away, which is how this was found.

---

## Laying text out

`SignLayout` is pure arithmetic in block pixels, shared by the renderer and the editor and tested
without a game running.

"Resizable line of text" is how every kind was asked for, and it means what somebody who reads real
ones means by it: the number is printed to *fill* the plate, not typeset at a fixed size with
whatever hangs off the end lost. A line is scaled to whichever limit it hits first — the length of
the plate, or the share of its width one line of a stack gets — and capped at five pixels tall so a
single character on a big diamond is not blown up until it fills the plate corner to corner.

Lines are stacked evenly across the plate's other axis, except either side of a printed divider —
the oval's fraction bar and the round tag's nail, which are in the texture, and the layout is what
puts the text either side of them.

The vertical strips and both tall tower tags are **columns of upright characters read downwards**,
not lines turned on their side; `TOWER_NUMBER` is the one that really is turned. See the "Stacked
plates" section above.

---

## What it costs to look at

**The plate is an ordinary baked block model.** One of a hundred and twelve, picked by the blockstate from the
kind and the facing, and baked into the chunk mesh like any other block. A pole line of tags costs
what a pole line of blocks costs.

**Only the lettering is drawn per frame**, by `TileRenderUtilitySign`, and:

- nothing at all past 48 blocks (`getMaxRenderDistanceSquared`), where the plate is a pixel or two
  across and the number was never legible;
- nothing at all for a blank sign, or for `LINE_CROSSING_DIAMOND`, which is a symbol;
- no matrix pushed before either of those checks.

It does **not** billboard. The text is fixed to the plate and turns with it, so a tag bolted to the
south face of a pole reads from the south and is invisible from the north — correctly. Text that
swivelled to follow the player would give away that there is nothing really there, which is the same
reason the Gas Station Pump's price is painted on its panel rather than floated beside the crosshair.

Lighting is disabled for the lettering, as vanilla signs do it: a pole number that went black at dusk
would be useless at exactly the hour somebody is out with a torch reading it.

---

## Placement and drops

`getFacingLimitation()` is **6** — horizontal, preferring the side clicked — so the facing is the
direction the plate's *back* points, toward whatever it is bolted to. Clicking the south face of a
pole therefore hangs a sign that reads from the south.

`canPlaceBlockOnSide` refuses vertical faces and faces with nothing behind them, and
`neighborChanged` drops the sign when its support goes, the way a torch does. There is no collision
box: nobody wants to be stopped by a pole number, and a one-pixel collision box on a ladder is a way
to fall off one.

### Reaching for the pole

**A pole is not a wall, and a pole is what signs go on.** A sign is placed in the empty cell beside
its support with its plate against the face they share — which is right against a wall, and two to
four pixels short of a pole, because a pole's skin is inside its own block. City Super Mod's light
poles and traffic poles are an eighth to a quarter of a block in on every side, so a tag hung on one
hung in the air beside it. That is what a playtester reported.

Two things follow, both in `SignMount`:

| | |
|---|---|
| **Placing** | Forge's `isSideSolid` falls through to "opaque, full cube, no redstone", which a wall is and a four-pixel pole is not. `BlockUtilitySign.hasSupport` therefore accepts either that *or* a solid, non-replaceable block whose own bounding box comes within `SignMount.MAX` of the face — which lets in poles, fences and posts and still keeps out torches, flowers and air. |
| **Reaching** | The plate grows a short **standoff** back to whatever is really there, measured off that block's own bounding box rather than guessed at or configured. Four pixels square, up to six deep, hidden behind every plate in the set. |

**The plate does not move, and that is deliberate.** Sliding it into the pole's cell would look the
same and would make the sign unclickable: `World.rayTraceBlocks` walks cell by cell and only ever
tests the block that owns the cell it is in, so a plate drawn inside its neighbour's cell is a plate
whose neighbour gets hit instead. A standoff keeps every pixel of the clickable plate in the sign's
own block, and the selection box is unchanged.

The reach is measured on placement and again whenever a neighbour changes, and — like the conduits
and the junction boxes before it — `refreshMount` only notifies when the measured value actually
changed. A tile that marks its block for update on every neighbour change is a tick-hang bomb beside
another one that does the same.

**Why the kind and the reach are one property.** Forge builds a variant out of the cartesian product
of the submaps it is given and merges the partial variants, so two submaps that both want to name a
model cannot both be honoured — the second simply wins. What a sign draws depends on *both*, so the
two travel as one integer, `kind * 7 + mount`, and the blockstate has one `plate` submap of 112
entries. `make_signage_assets.py` reads `SignMount.MAX` out of the Java for the same reason it parses
the kind table: a generator that writes models for reaches the block cannot select is a generator
that writes purple blocks.

CSM's traffic poles grow a mount collar of their own toward anything solid beside them, signs
included, and that is left alone — it is the pole's own hardware and it was asked to stay.

`ITileDrop` puts the kind and the three lines into the dropped item's `sign` compound, and
`readOnPlacement` reads them back — so hammering sixteen times to find the plate you meant and then
mining it by accident costs nothing. A blank sign of the default kind carries no tag at all, so a box
of unused ones still stacks.

---

## Files

| Concern | Where |
|---|---|
| The kinds | `common/blocks/signage/UtilitySignKind.java` |
| Text layout, shared | `common/blocks/signage/SignLayout.java` |
| Reaching for the pole | `common/blocks/signage/SignMount.java` |
| The block | `common/blocks/signage/BlockUtilitySign.java` |
| The tile entity | `common/blocks/signage/TileEntityUtilitySign.java` |
| The lettering | `client/render/TileRenderUtilitySign.java` |
| The editor | `client/gui/GuiUtilitySign.java`, `common/gui/ContainerUtilitySign.java` |
| The packet | `common/util/network/MessageSignText.java` |
| Textures, models, blockstates | `docs/tools/make_signage_assets.py` — **generated, do not hand-edit** |
| Tests | `src/test/.../common/blocks/signage/SignageTest.java` |
| Manual chapter | `docs/manual/chapters/signage.tex` |
