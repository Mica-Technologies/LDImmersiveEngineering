# Utility pole signage

Thirteen kinds of tag bolted flat to whatever holds the wires up, and a two-gesture interface for
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
| Kinds | Thirteen, on the tile entity — not thirteen items and not thirteen metas |
| Placing | Against a **horizontal** face with something solid behind it |
| Choosing a plate | **Engineer's Hammer**: steps to the next kind |
| Writing on it | **Sneak + Engineer's Hammer**: opens the editor |
| Lines | 0–3 depending on the kind, auto-scaled to fill the plate |
| Per-frame cost | The lettering only, and only within 48 blocks |

---

## The thirteen kinds

Ordinals are what a sign saves, so `UtilitySignKind` may only be appended to.

| # | Kind | Plate | Lines | Text |
|---|---|---|---|---|
| 0 | `PARALLEL_GENERATION` | 14×6 red strip | 2 | white |
| 1 | `YELLOW_VERTICAL` | 6×14 yellow strip | 1 | black, turned |
| 2 | `WHITE_VERTICAL` | 6×14 white strip | 1 | black, turned |
| 3 | `SILVER_VERTICAL` | 6×14 bare metal strip | 1 | grey, turned |
| 4 | `OVAL_FRACTION` | 12×8 white oval | 2 | black |
| 5 | `YELLOW_HORIZONTAL` | 14×4 yellow strip | 1 | black |
| 6 | `ORANGE_HORIZONTAL` | 14×4 orange strip | 1 | black |
| 7 | `ORANGE_VERTICAL` | 6×14 orange strip | 1 | black, turned |
| 8 | `INSPECTION_ROUND` | 10×10 silver disc | 2 | grey |
| 9 | `TOWER_DIAMOND` | 12×12 yellow diamond, no border | 1 | black |
| 10 | `LINE_CROSSING_DIAMOND` | 12×12 yellow diamond, outline and cross | 0 | — |
| 11 | `TOWER_VERTICAL` | 8×14 yellow, rule printed at ⅔ height | 3 | black |
| 12 | `TOWER_HORIZONTAL` | 14×4 yellow strip | 1 | black |

What each means in the field is in the manual chapter (`docs/manual/chapters/signage.tex`) and in
the `UtilitySignKind` javadoc; it is deliberately not repeated in the code as data, because nothing
in the game branches on it.

**Every plate is an even number of pixels across and down.** Odd would put the plate's edge on a
half-pixel, which samples between two texels and comes out of the atlas as a blurred fringe — on a
six-pixel strip, most of the sign. `SignageTest` asserts it.

---

## Where a kind lives

**On the tile entity, and in a listed block property filled from it.** Thirteen kinds times four
facings is fifty-two states, which is affordable — the *text* is what is not, and once the text has
to be on the tile entity there is no reason for the kind to be anywhere else.

`TileEntityUtilitySign` implements `IAttachedIntegerProperies`, which is what
`BlockIETileProvider.getActualState` reads to fill `BlockUtilitySign.KIND`. That lets the blockstate
be a plain Forge `variants` file — a `facing` submap supplying a y-rotation and a `kind` submap
supplying a model — instead of a smart model with a loader behind it. Thirteen flat textured slabs
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

**The preview is the point.** Thirteen kinds is thirteen shapes, colours and text layouts, and
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

Lines are stacked evenly across the plate's other axis. `TOWER_VERTICAL`'s printed rule sits at two
thirds of its height, which is between the second and third of three evenly spaced lines — the rule
is in the texture, and the layout is what puts the text either side of it.

The vertical strips turn their whole text block ninety degrees and read **downwards**, which is how
the real ones are printed and the only way a strip six pixels wide holds a pole number at all.

---

## What it costs to look at

**The plate is an ordinary baked block model.** One of fifty-two, picked by the blockstate from the
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

`canPlaceBlockOnSide` refuses vertical faces and faces with nothing solid behind them, and
`neighborChanged` drops the sign when its support goes, the way a torch does. There is no collision
box: nobody wants to be stopped by a pole number, and a one-pixel collision box on a ladder is a way
to fall off one.

`ITileDrop` puts the kind and the three lines into the dropped item's `sign` compound, and
`readOnPlacement` reads them back — so hammering thirteen times to find the plate you meant and then
mining it by accident costs nothing. A blank sign of the default kind carries no tag at all, so a box
of unused ones still stacks.

---

## Files

| Concern | Where |
|---|---|
| The thirteen kinds | `common/blocks/signage/UtilitySignKind.java` |
| Text layout, shared | `common/blocks/signage/SignLayout.java` |
| The block | `common/blocks/signage/BlockUtilitySign.java` |
| The tile entity | `common/blocks/signage/TileEntityUtilitySign.java` |
| The lettering | `client/render/TileRenderUtilitySign.java` |
| The editor | `client/gui/GuiUtilitySign.java`, `common/gui/ContainerUtilitySign.java` |
| The packet | `common/util/network/MessageSignText.java` |
| Textures, models, blockstates | `docs/tools/make_signage_assets.py` — **generated, do not hand-edit** |
| Tests | `src/test/.../common/blocks/signage/SignageTest.java` |
| Manual chapter | `docs/manual/chapters/signage.tex` |
