#!/usr/bin/env python3
"""
Regenerates the conduit block's texture, models and blockstate.

Conduit is surface-mounted tubing: it lies flat against a face, turns in right angles,
and wraps around the corners of what it is clipped to, which is the shape IE's catenary
wires cannot make and the reason the block exists.  It is drawn out of static geometry --
a hub pad against the mounting face plus one arm per joined direction, in whichever of its
three forms that arm has -- rather than by a renderer.  Seventy-eight small models, every
one an axis-aligned box, and nothing drawn per frame.

Which of them to draw is picked by a smart model reading the tile entity, so the two
blockstates written here are one unconditional part each.  They used to be seventy-eight
and seventy-two selectors, keyed on twelve boolean block properties, and those twelve cost
BlockConduit 73,728 block states and a model reference for every one of them.

Everything here is generated because the alternative is seventy-eight hand-written JSON
files whose only difference is six numbers, and a typo in one of them shows up as a
single mis-shaped elbow somewhere in a base.

**The geometry constants are the ones the hitbox uses.**  They are read out of
ConduitBounds.java rather than restated, because a model and a selection box that
disagree is obvious in a screenshot and invisible in code.

Usage:  python docs/tools/make_conduit_assets.py [--assets <assets dir>]

Requires Pillow.
"""

import argparse
import json
import os
import re

from PIL import Image

# ---------------------------------------------------------------------------
# Palette -- galvanized steel, the finish real EMT conduit and pull boxes come in: a cool,
# slightly blue grey with a bright specular line where the light catches the round of the
# tube.  The couplings and fittings are die-cast zinc, a touch warmer and duller than the
# tube they join, which is what makes them read as separate parts from across a room.
# ---------------------------------------------------------------------------
OUTLINE = (38, 38, 42, 255)
STEEL_HI = (236, 240, 244, 255)
STEEL_LIT = (196, 201, 207, 255)
STEEL_MID = (163, 168, 175, 255)
STEEL_SHADE = (128, 133, 140, 255)
STEEL_DARK = (94, 98, 105, 255)
CAST_HI = (214, 213, 207, 255)
CAST_LIT = (180, 179, 173, 255)
CAST_MID = (149, 148, 143, 255)
CAST_SHADE = (117, 116, 112, 255)
CAST_DARK = (85, 84, 81, 255)
SCREW = (60, 60, 63, 255)
# The patch plate is deliberately near-white rather than steel: it is the one surface in this
# feature whose whole job is to carry a tint, and a tint multiplies whatever is under it.
PATCH_FILL = (236, 236, 238, 255)
PATCH_SEAM = (198, 198, 202, 255)

FACINGS = ["down", "up", "north", "south", "west", "east"]
AXIS_OF = {"down": "y", "up": "y", "north": "z", "south": "z", "west": "x", "east": "x"}
NEGATIVE = {"down", "north", "west"}

OPPOSITE = {"down": "up", "up": "down", "north": "south", "south": "north",
            "west": "east", "east": "west"}

# ---------------------------------------------------------------------------
# Which way a face reads its texture.
#
# For each face: the world axis the texture's u runs along and in which direction, then the
# same for v.  These are the directions Minecraft's own default UVs imply (BlockPart's
# getFaceUvs) -- the vertex at the low end of that direction gets the first coordinate of the
# pair -- so a face given UVs through face_uv below lands the texture the right way round.
# ---------------------------------------------------------------------------
FACE_TEX_AXES = {
    "down": (("x", 1), ("z", -1)),
    "up": (("x", 1), ("z", 1)),
    "north": (("x", -1), ("y", -1)),
    "south": (("x", 1), ("y", -1)),
    "west": (("z", 1), ("y", -1)),
    "east": (("z", -1), ("y", -1)),
}


class Region(object):
    """A rectangle of a sprite, painted twice: once with its rows running down the sprite, and
    once transposed so the same rows run across it.

    **This is what lets the conduit be shaded at all.**  A tube is lit along its length -- a
    highlight down the exposed face, sides darkening toward the wall -- so every face has to read
    the shading *across* the run whichever way the run goes.  Which of a face's two texture axes
    lies across the run depends on the run's direction and the face, and a face cannot rotate
    its texture freely without the `rotation` field's own rules, so instead every pattern exists
    both ways round and each face picks the copy whose rows lie the way it needs.

    `rows` is a list of rows, each a list of colours: a pattern whose row index follows one world
    axis and whose column index follows another.  `h` is where it is painted as written, `v`
    where it is painted transposed; both as (x, y) of the top-left texel.
    """

    def __init__(self, rows, h, v):
        self.rows = rows
        self.height = len(rows)
        self.width = len(rows[0])
        self.h = (h[0], h[1], self.width, self.height)
        self.v = (v[0], v[1], self.height, self.width)

    def paint(self, px):
        for r, row in enumerate(self.rows):
            for c, colour in enumerate(row):
                px[self.h[0] + c, self.h[1] + r] = colour
                px[self.v[0] + r, self.v[1] + c] = colour


def face_uv(face, region, row_axis, col_axis, sprite, row_rev=False, col_rev=False):
    """The UVs that lay `region` on `face` with its rows following `row_axis`.

    Row index 0 lands at the low-coordinate end of `row_axis` unless `row_rev`, and likewise for
    columns; the pattern is stretched to the face, so a pattern that is constant along one axis
    can cover a face of any length on it.  `sprite` is the sprite's size in texels -- UVs are
    always in sixteenths of the sprite, whatever its resolution.
    """
    (ua, us), (va, vs) = FACE_TEX_AXES[face]
    assert {ua, va} == {row_axis, col_axis}, (face, row_axis, col_axis)
    if va == row_axis:
        x, y, w, h = region.h
        u_rng, u_rev = (x, x + w), col_rev
        v_rng, v_rev = (y, y + h), row_rev
    else:
        x, y, w, h = region.v
        u_rng, u_rev = (x, x + w), row_rev
        v_rng, v_rev = (y, y + h), col_rev

    def ends(rng, sign, rev):
        at_min, at_max = (rng[1], rng[0]) if rev else rng
        return (at_min, at_max) if sign > 0 else (at_max, at_min)

    u1, u2 = ends(u_rng, us, u_rev)
    v1, v2 = ends(v_rng, vs, v_rev)
    k = 16.0 / sprite
    return [u1 * k, v1 * k, u2 * k, v2 * k]


def other_axis(*axes):
    """The one of x, y and z that is not among `axes`."""
    return [a for a in "xyz" if a not in axes][0]


def in_face_axes(face):
    """The two world axes lying in a face, in xyz order."""
    return [a for a in "xyz" if a != AXIS_OF[face]]

# How a blockstate refers to one of the models above.
#
# **The `models/block/` prefix is implied and must not be written out.**  A blockstate saying
# `immersiveengineering:block/conduit/x` resolves to `models/block/block/conduit/x.json`, which
# does not exist -- and a missing model is a purple block with nothing in the log.  IE's own files
# say `immersiveengineering:grid/utility_box` for `models/block/grid/utility_box.json`; this
# follows them.
MODEL_REF = "immersiveengineering:conduit/%s"
# The models above are no longer named by any blockstate.  Both blockstates name a single smart
# model instead, and the Java picks the pieces off the tile entity -- see ConduitRunModel and
# ConduitJunctionModel.  That is what took the block from 73,728 states to 18: a multipart
# blockstate can only select on *listed* block properties, and there were twelve booleans' worth
# of them describing a shape the tile entity already knew.
#
# The generated files themselves are unchanged, and are still the only geometry there is.
RUN_MODEL_REF = "immersiveengineering:smartmodel/conduit_run"
# The junction box's goes through IE's connection smart model on top of that, which draws the box
# and then any catenary strung to it.  ClientProxy registers one key against the assembling model;
# the name after `conn_` is that key.  See ConnLoader -- the `models/block/smartmodel/conn_`
# prefix is what its `accepts` matches.
CONN_MODEL_REF = "immersiveengineering:smartmodel/conn_conduit_junction_box"


def read_bounds_constants(repo):
    """Take DEPTH, HALF_WIDTH and JUNCTION_HALF from the Java rather than restating them here.

    If ConduitBounds moves its numbers, the models move with it or this script stops --
    which is the point.  A silent divergence between the box you click and the tube you
    see is the exact failure this guards against.
    """
    path = os.path.join(repo, "src", "main", "java", "blusunrize", "immersiveengineering",
                        "common", "blocks", "conduit", "ConduitBounds.java")
    with open(path, encoding="utf-8") as handle:
        source = handle.read()
    found = {}
    for name in ("DEPTH", "HALF_WIDTH", "JUNCTION_HALF"):
        match = re.search(r"int\s+%s\s*=\s*(\d+)\s*;" % name, source)
        if not match:
            raise SystemExit("could not find %s in ConduitBounds.java" % name)
        found[name] = int(match.group(1))
    return found["DEPTH"], found["HALF_WIDTH"], found["JUNCTION_HALF"]


def in_plane(mount):
    """The four directions a conduit on that face may run in.

    Same order as ConduitGeometry.inPlane, which derives it from EnumFacing.values().
    Order does not matter to the blockstate -- it keys on absolute facings -- but
    matching it keeps the two readable side by side.
    """
    return [f for f in FACINGS if AXIS_OF[f] != AXIS_OF[mount]]


def mount_span(mount, depth):
    """Where the tubing sits along the mounting axis: hard against the surface."""
    return (0, depth) if mount in NEGATIVE else (16 - depth, 16)


def box(mount, depth, half, arm=None):
    """One cuboid, in block pixels, as [from, to].

    With no arm this is the hub: a centred pad against the mounting face.  With an arm
    it is the length from the *edge of the hub* out to the block boundary that direction
    points at, so two joined conduits meet flush across the boundary between them.

    The arm deliberately stops where the hub starts rather than spanning the whole block.
    Overlapping coplanar faces z-fight, and a run that shimmered along its length would be
    the first thing anybody noticed about the feature.
    """
    lo = {"x": 8 - half, "y": 8 - half, "z": 8 - half}
    hi = {"x": 8 + half, "y": 8 + half, "z": 8 + half}
    axis = AXIS_OF[mount]
    lo[axis], hi[axis] = mount_span(mount, depth)
    if arm is not None:
        arm_axis = AXIS_OF[arm]
        if arm in NEGATIVE:
            lo[arm_axis], hi[arm_axis] = 0, 8 - half
        else:
            lo[arm_axis], hi[arm_axis] = 8 + half, 16
    return ([lo["x"], lo["y"], lo["z"]], [hi["x"], hi["y"], hi["z"]])


def riser_box(mount, depth, half, arm):
    """The climbing half of an inner corner: the piece that turns up at the end of an arm.

    A floor run reaching a wall carries on up it, and the turn happens inside the lower
    cell.  So: the same cross-section the tubing has everywhere, stood on end against the
    block the arm points at, running from the top of the horizontal tubing right out to
    the far face of the cell -- which is where the conduit clipped to that wall one cell
    up has its own arm coming down to meet it.

    It starts where the horizontal arm stops rather than overlapping it.  Two boxes
    sharing a corner would put two coplanar faces in the same place, and a corner that
    z-fights reads as a broken model rather than as a corner.
    """
    lo = {"x": 8 - half, "y": 8 - half, "z": 8 - half}
    hi = {"x": 8 + half, "y": 8 + half, "z": 8 + half}
    axis = AXIS_OF[mount]
    # Along the mounting axis: from the back of the tubing out to the opposite face.
    lo[axis], hi[axis] = (depth, 16) if mount in NEGATIVE else (0, 16 - depth)
    arm_axis = AXIS_OF[arm]
    # Along the arm: hard against the block being climbed, one tubing depth thick.
    lo[arm_axis], hi[arm_axis] = (0, depth) if arm in NEGATIVE else (16 - depth, 16)
    return ([lo["x"], lo["y"], lo["z"]], [hi["x"], hi["y"], hi["z"]])


def wrap_box(mount, depth, half, arm):
    """The cube that fills an outer corner, which lies outside this block entirely.

    Both halves of an outer corner are ordinary arms reaching the same edge from two
    sides of the block they are both clipped to, and the little cube where they meet
    belongs to neither: the arm stops at the boundary and the other one starts one cell
    over and a cell down.  Without something in it there is a conduit-sized notch at
    every wrapped corner.

    So one of the two grows into it -- `ConduitGeometry.drawsCornerCap` picks which, and
    it has to be exactly one, because two identical cubes in one place is z-fighting
    rather than a corner.  Both would name the same cube: it is the corner, not a piece
    of either arm.

    It is deliberately *not* in `ConduitBounds`.  1.12 gives a block one bounding box,
    and growing this one past the block boundary would make a conduit selectable and
    breakable from a cell it does not occupy.
    """
    lo, hi = box(mount, depth, half, arm)
    axis = "xyz".index(AXIS_OF[arm])
    if arm in NEGATIVE:
        hi[axis], lo[axis] = 0, -depth
    else:
        lo[axis], hi[axis] = 16, 16 + depth
    # Its faces are given UVs from a pattern rather than from its own coordinates (see
    # conduit_faces), so lying outside the block does not send them outside the sprite.
    return (lo, hi)


def write_json(path, body):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(body, handle, indent="\t")
        handle.write("\n")


# ---------------------------------------------------------------------------
# The conduit tile.
#
# Every pattern in it is laid out in terms of a run rather than of a block: rows follow one
# axis of the piece and columns another, and face_uv lays each on a face the right way round.
# Row index 0 of a *side* pattern is the exposed edge and its last row is the edge against the
# wall; the *top* patterns run across the tube's width.  See conduit_faces for which face wears
# which.  The widths are the tube's own -- four pixels across, three deep -- so a pattern maps
# texel for texel onto the face it is drawn on, and only the length is ever stretched, along
# which the tube's patterns do not change.
# ---------------------------------------------------------------------------
CONDUIT_SPRITE = 16
# Across the exposed face: a bright line where the light catches the round of the tube.
TUBE_TOP_PROFILE = [STEEL_LIT, STEEL_HI, STEEL_LIT, STEEL_MID]
# Down each side, from the exposed edge to the wall.
TUBE_SIDE_PROFILE = [STEEL_MID, STEEL_SHADE, STEEL_DARK]


def _along(profile, length=4):
    """A pattern that is `profile` across and the same all the way along."""
    return [[colour] * length for colour in profile]


def _coupling_top():
    """A set-screw coupling seen from the front: two darker lips where the tubes go in, a
    duller body between them, and the set screw."""
    body = [CAST_LIT, CAST_HI, CAST_LIT, CAST_MID]
    lip = [CAST_MID, CAST_LIT, CAST_MID, CAST_SHADE]
    rows = [[lip[r], body[r], body[r], lip[r]] for r in range(4)]
    rows[1][2] = SCREW
    return rows


def _coupling_side():
    body = [CAST_MID, CAST_SHADE, CAST_DARK]
    lip = [CAST_SHADE, CAST_DARK, OUTLINE]
    return [[lip[r], body[r], body[r], lip[r]] for r in range(3)]


def _fitting_top():
    """A cast fitting's cover: a raised rim round a flat lid with its screw."""
    return [
        [CAST_MID, CAST_MID, CAST_MID, CAST_SHADE],
        [CAST_MID, CAST_HI, CAST_LIT, CAST_SHADE],
        [CAST_MID, CAST_LIT, SCREW, CAST_SHADE],
        [CAST_SHADE, CAST_SHADE, CAST_SHADE, CAST_SHADE],
    ]


TUBE_TOP = Region(_along(TUBE_TOP_PROFILE), h=(0, 0), v=(4, 0))
TUBE_SIDE = Region(_along(TUBE_SIDE_PROFILE), h=(0, 4), v=(4, 4))
COUPLING_TOP = Region(_coupling_top(), h=(8, 0), v=(12, 0))
COUPLING_SIDE = Region(_coupling_side(), h=(8, 4), v=(12, 4))
FITTING_TOP = Region(_fitting_top(), h=(0, 8), v=(4, 8))
FITTING_SIDE = Region(_along([CAST_MID, CAST_SHADE, CAST_DARK]), h=(8, 8), v=(12, 8))
# The face against the wall.  Never seen in a world, only on a dropped item.
CONDUIT_BACK = Region(_along([STEEL_DARK] * 4), h=(0, 12), v=(0, 12))
CONDUIT_REGIONS = (TUBE_TOP, TUBE_SIDE, COUPLING_TOP, COUPLING_SIDE,
                   FITTING_TOP, FITTING_SIDE, CONDUIT_BACK)


def conduit_faces(kind, exposed, length_axis=None):
    """The six faces of one piece of conduit.

    `kind` is what the piece is:

    - "tube", a length of tubing lying along `length_axis`;
    - "coupling", the hub of a straight run, which is a set-screw coupling along `length_axis`;
    - "fitting", the hub where a run turns or branches, which is a cast fitting with no length;
    - "cap", the little cube that fills an outer corner, which is a fitting too.

    `exposed` is the direction the piece faces away from the surface it lies on: the opposite of
    the mount for tubing on that surface, and away from the wall being climbed for a riser.
    """
    e_axis = AXIS_OF[exposed]
    # Side patterns start at the exposed edge, so on a piece facing the positive way they have to
    # run against the world coordinate.
    e_rev = exposed not in NEGATIVE
    faces = {}
    for face in FACINGS:
        a, b = in_face_axes(face)
        if kind == "cap":
            uv = face_uv(face, FITTING_TOP, a, b, CONDUIT_SPRITE)
        elif face == exposed:
            if kind == "fitting":
                uv = face_uv(face, FITTING_TOP, a, b, CONDUIT_SPRITE)
            else:
                width_axis = other_axis(length_axis, e_axis)
                region = TUBE_TOP if kind == "tube" else COUPLING_TOP
                uv = face_uv(face, region, width_axis, length_axis, CONDUIT_SPRITE)
        elif face == OPPOSITE[exposed]:
            uv = face_uv(face, CONDUIT_BACK, a, b, CONDUIT_SPRITE)
        else:
            across = other_axis(AXIS_OF[face], e_axis)
            region = {"tube": TUBE_SIDE, "coupling": COUPLING_SIDE,
                      "fitting": FITTING_SIDE}[kind]
            uv = face_uv(face, region, e_axis, across, CONDUIT_SPRITE, row_rev=e_rev)
        faces[face] = {"texture": "#conduit", "uv": uv}
    return faces


def model_json(*pieces):
    """A model of one or more pieces, each a (from, to, faces) box from `piece`."""
    return {
        "textures": {
            "conduit": "immersiveengineering:blocks/conduit",
            "particle": "immersiveengineering:blocks/conduit",
        },
        "elements": [{"from": frm, "to": to, "faces": faces} for frm, to, faces in pieces],
    }


def piece(bounds, kind, exposed, length_axis=None):
    frm, to = bounds
    return frm, to, conduit_faces(kind, exposed, length_axis)


def build_models(assets, depth, half):
    """The ninety pieces: six fittings and twelve couplings for the hub, and each of twenty-four
    arms in three forms.

    An arm is straight, or it climbs the block in front of it (an inner corner), or it
    carries on a little past the block edge to cap an outer one.  Three whole models
    rather than a straight arm plus an optional extra part, because a multipart part
    either applies or it does not: straight and climbing are two states of one arm, not
    one arm with something added.

    The hub comes in two kinds because the tube is shaded along its length and a hub has
    none: a straight run's hub is a coupling lying along the run, and every other hub is a
    fitting, which looks the same from every side.  ConduitGeometry.hubAxis picks.
    """
    out = os.path.join(assets, "models", "block", "conduit")
    written = []
    for mount in FACINGS:
        exposed = OPPOSITE[mount]
        name = "conduit_%s_hub" % mount
        write_json(os.path.join(out, name + ".json"),
                   model_json(piece(box(mount, depth, half), "fitting", exposed)))
        written.append(name)
        for axis in in_face_axes(mount):
            coupling = "%s_%s" % (name, axis)
            write_json(os.path.join(out, coupling + ".json"),
                       model_json(piece(box(mount, depth, half), "coupling", exposed, axis)))
            written.append(coupling)
        for arm in in_plane(mount):
            name = "conduit_%s_%s" % (mount, arm)
            length = piece(box(mount, depth, half, arm), "tube", exposed, AXIS_OF[arm])
            write_json(os.path.join(out, name + ".json"), model_json(length))
            written.append(name)
            # A riser lies against the wall it climbs, so it faces away from that wall and runs
            # along this conduit's own mounting axis.
            write_json(os.path.join(out, name + "_riser.json"),
                       model_json(length, piece(riser_box(mount, depth, half, arm), "tube",
                                                OPPOSITE[arm], AXIS_OF[mount])))
            written.append(name + "_riser")
            write_json(os.path.join(out, name + "_wrap.json"),
                       model_json(length, piece(wrap_box(mount, depth, half, arm), "cap",
                                                exposed)))
            written.append(name + "_wrap")

    # The held item and the creative-tab icon: a straight length lying on the floor,
    # which is what placing one actually gives you. Assembled from the same boxes the
    # block would use rather than drawn separately, so it cannot drift.
    write_json(os.path.join(out, "conduit_item.json"), model_json(
        piece(box("down", depth, half), "coupling", "up", "z"),
        piece(box("down", depth, half, "north"), "tube", "up", "z"),
        piece(box("down", depth, half, "south"), "tube", "up", "z")))
    written.append("conduit_item")
    return written


def build_texture(assets):
    """The conduit tile: every pattern a piece of conduit wears, once each way round.

    **Faces no longer sample this at their own block coordinates.**  They did, and it forced
    the tile to look the same read in any direction -- a vertical run read its rows and a
    horizontal one its columns -- which is why it used to be flat steel with a ring painted as
    both a row and a column, and why it could never be shaded like a tube.  Now each face is
    given UVs that lay a pattern along the run it belongs to (conduit_faces), so the tile can
    say what a tube actually looks like: a highlight down the exposed face, sides darkening to
    the wall, couplings and fittings in duller cast metal.  Horizontal and vertical runs still
    look alike, which was the whole point of the old tile, because every face reads its pattern
    the same way relative to the run.

    Everything not covered by a pattern is plain steel, since the breaking particles sample
    the whole sprite.
    """
    img = Image.new("RGBA", (CONDUIT_SPRITE, CONDUIT_SPRITE), STEEL_MID)
    px = img.load()
    for region in CONDUIT_REGIONS:
        region.paint(px)
    out = os.path.join(assets, "textures", "blocks", "conduit.png")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    img.save(out, "PNG", optimize=True)
    return out


def build_blockstate(assets):
    """The block's blockstate: one unconditional part naming the smart model, and nothing else.

    **This file used to be a seventy-eight selector multipart** -- a hub per facing plus each
    arm in each of its three forms, chosen by twelve boolean block properties.  It worked, and
    it cost 73,728 block states: Forge builds the cartesian product of every listed property at
    startup and hands each resulting state a ModelResourceLocation of its own, so twelve
    booleans on top of type and facing meant seventy-three thousand model references for a
    block made of seventy-eight little boxes.

    Those seventy-eight models are unchanged and are still all the geometry there is.  What
    moved is only the *selection*: ConduitRunModel reads the mount and the three arm masks
    straight off the tile entity through IEProperties.TILEENTITY_PASSTHROUGH and glues the
    same pieces together, caching the result per distinct shape.  A blockstate can only select
    on listed properties, which is why the selection had to be listed properties before.

    Still multipart rather than variants, and still a single unconditional part: a `variants`
    file would have to resolve the whole property string the state mapper hands it, which in
    the Forge format means a submap per property or the variant silently does not resolve.
    The ground feeder's file has had exactly this shape since it was written.

    It lives in conduit_run.json rather than conduit.json, and BlockConduit's custom state
    mapper is what points at it -- the same split IE's fences use.  A multipart file cannot
    also carry the `inventory,...` variant the item model is looked up through, so the two
    have to be separate files.  Getting either half wrong gives a purple block and no error,
    which is why build_item_blockstate below is not optional.

    The model reference is NOT one of the generated ones.  `smartmodel/conduit_run` is claimed
    by ConduitRunLoader, which builds the model in code -- there is no file behind it and there
    must not be one.
    """
    parts = [{"apply": {"model": RUN_MODEL_REF}}]
    write_json(os.path.join(assets, "blockstates", "conduit_run.json"), {"multipart": parts})
    return parts


# Half the junction box's width across the surface it is mounted on lives in ConduitBounds now,
# alongside the tubing's own numbers, and arrives here through read_bounds_constants.  It moved
# because the Java needs it too: a wire strung to a box has to land on the box's actual surface,
# and a terminal point derived from a second copy of the number is a wire starting in mid-air.


def box_bounds(mount, depth, box_half):
    """Where the junction box's housing sits when it is bolted to that face, as [from, to].

    **The box hugs its surface, exactly as the conduit does.**  It stands `2*depth+2` off the
    face it is clipped to and is centred across it, so a run clipped to the same face passes
    through the housing's own cross-section rather than beside it.

    That is the whole of the fix this shape exists for.  The box used to be modelled as one
    lump standing on the floor of its own cell whichever way it was bolted -- which is right
    for a floor run and wrong for every other, because a conduit only occupies the first three
    pixels off its surface.  A box that did not hug the same face was not merely offset from
    the run: it was in a part of the block the run never reaches, so the run's arm arrived at
    the boundary with nothing on the other side of it, and the piece grown out to close that
    gap grew along the floor, three pixels clear of the wall the run was on.  On a floor run
    all of this coincided and looked correct, which is why it shipped.

    For `down` this is bit-for-bit the shape the box has always had, so a box with no runs on
    it -- and every box in a world saved before this -- looks exactly as it did.
    """
    lo = [8 - box_half] * 3
    hi = [8 + box_half] * 3
    axis = "xyz".index(AXIS_OF[mount])
    stand_off = 2 * depth + 2
    if mount in NEGATIVE:
        lo[axis], hi[axis] = 0, stand_off
    else:
        lo[axis], hi[axis] = 16 - stand_off, 16
    return lo, hi


def build_junction_box(assets, depth, box_half):
    """The junction box: a squat surface box per mounting face, and the blockstate that draws it.

    Plainer than the conduit on purpose.  It is a thing you walk up to and right-click with
    a dye, so it wants to read as a box with a lid rather than as more tubing, and it wants
    to be visible from across a room.  A cube inset on every side, drawn with its own
    texture, does both and costs one model per face it can be bolted to.

    Six of everything, and it costs no block state at all: the box has no facing of its own --
    no tile entity field, no placement rule, no packet -- it is derived from the runs that reach
    it, in `ConduitGeometry.junctionBoxMount`, and read at render time by ConduitJunctionModel.
    """
    out = os.path.join(assets, "models", "block", "conduit")
    # **None of these models is named by the blockstate any more.**  The file used to be a
    # seventy-two selector multipart -- a housing per mounting face, a coloured plate on each
    # patched face, a stub toward each face a run touches -- selected by `facing` and twelve
    # boolean block properties.  Those twelve cost BlockConduit 73,728 states, because Forge
    # builds the cartesian product of every listed property at startup and gives each state a
    # model reference of its own.
    #
    # ConduitJunctionLoader bakes all of them once and ConduitJunctionModel picks between them
    # from the tile entity instead, which is where the mount, the patch table and the joined
    # faces have always actually lived.  The models below are byte-for-byte what they were.
    #
    # Still multipart rather than variants, with one unconditional part: a `variants` file would
    # have to resolve the whole property string the state mapper hands it, which in the Forge
    # format means a submap per property or the variant silently does not resolve.
    #
    # The part names `smartmodel/conn_conduit_junction_box`, not the assembling model directly,
    # so that a wire strung to the box is drawn along with it -- only ConnModelReal draws a
    # catenary.  ClientProxy registers the assembling model as the base behind that key.
    for mount in FACINGS:
        frm, to = box_bounds(mount, depth, box_half)
        write_json(os.path.join(out, "junction_box_%s.json" % mount), {
            "textures": {
                "box": "immersiveengineering:blocks/conduit_junction_box",
                "particle": "immersiveengineering:blocks/conduit_junction_box",
            },
            "elements": [{"from": frm, "to": to, "faces": housing_faces(mount)}],
        })
        build_patch_models(assets, mount, frm, to)
        # build_run_stub_models writes nothing for the face the box already reaches on its own:
        # there is no gap to close there, and ConduitJunctionLoader skips the same face.
        build_run_stub_models(assets, mount, frm, to)
    write_json(os.path.join(assets, "blockstates", "conduit_junction_box.json"), {
        "multipart": [{"apply": {"model": CONN_MODEL_REF}}],
    })


# How far a patch plate stands off the face it marks, and how far in from the box's edge it
# sits.  The lift is a quarter of a pixel: enough that it never z-fights with the box, small
# enough that it reads as painted on rather than bolted on.
PATCH_LIFT = 0.25
PATCH_INSET = 2


def patch_bounds(frm, to, face):
    """The plate that marks one face of the box, derived from the box rather than written out.

    Derived so the plates follow the box if its size ever changes.  A hand-written plate that
    silently stopped lining up would be exactly the kind of thing nobody notices in a diff.
    """
    lo, hi = list(frm), list(to)
    i = "xyz".index(AXIS_OF[face])
    for j in range(3):
        if j != i:
            lo[j] += PATCH_INSET
            hi[j] -= PATCH_INSET
    if face in NEGATIVE:
        hi[i], lo[i] = frm[i], frm[i] - PATCH_LIFT
    else:
        lo[i], hi[i] = to[i], to[i] + PATCH_LIFT
    return lo, hi


def build_patch_models(assets, mount, frm, to):
    """One plate model per face of a box bolted to `mount`, each tinted through its own tint index.

    Six models and six tint indices rather than ninety-six models, because the colour is not in
    the model at all: the plate is painted near-white and `BlockConduit.getRenderColour` supplies
    the dye per face at render time.  Enumerating six faces times sixteen colours as separate
    models would be the other way to do this and would cost a hundred bakes to say the same thing.

    The tint index is the face's ordinal in EnumFacing order, which is what FACINGS is, so the
    Java side needs no mapping table -- it reads EnumFacing.byIndex(tintIndex) straight off.
    """
    out = os.path.join(assets, "models", "block", "conduit")
    written = []
    for index, face in enumerate(FACINGS):
        lo, hi = patch_bounds(frm, to, face)
        faces = {f: {"texture": "#patch", "tintindex": index} for f in FACINGS}
        write_json(os.path.join(out, "junction_patch_%s_%s.json" % (mount, face)), {
            "textures": {"patch": "immersiveengineering:blocks/conduit_patch"},
            "elements": [{"from": lo, "to": hi, "faces": faces}],
        })
        written.append(face)
    return written


# How much of a breakout stub is the coloured mouth: the last two pixels before the block edge.
#
# The colour is at the *mouth* rather than on a plate against the housing because a breakout that
# reaches the block edge buries a plate that does not.  Growing the stub is what closes the gap a
# playtester reported between a box and everything bolted next to it, and the plate was the one
# thing the stub would have swallowed -- so the plate moved to the end of the stub, which is also
# where decision 9 always said the colour belonged: "a colour strip at the mouth".
MOUTH_DEPTH = 2


def run_stub_bounds(frm, to, face):
    """Where a stub toward one face sits: the box's own cross-section, extended from the box's
    edge out to the block boundary that face's arm reaches.

    Because the housing hugs the surface it is bolted to, that cross-section is also the one a
    conduit clipped to the same surface arrives on -- the tubing runs inside the housing's own
    footprint -- so the stub meets the arm rather than passing beside it.  See `box_bounds`.

    Returns None on the face the box already reaches by itself, which is the one it is bolted
    to: there is nothing to bridge there and a zero-thickness box would be a model that parses
    and draws nothing.
    """
    lo, hi = list(frm), list(to)
    i = "xyz".index(AXIS_OF[face])
    if face in NEGATIVE:
        hi[i], lo[i] = frm[i], 0
    else:
        lo[i], hi[i] = to[i], 16
    if lo[i] == hi[i]:
        return None
    return lo, hi


def split_stub(lo, hi, face):
    """A stub cut into the length that stays steel and the mouth that takes the dye.

    Returned as two boxes that abut rather than overlap.  Two coplanar faces in the same place
    z-fight; two boxes sharing a plane between them do not, because whichever of the pair faces
    the camera is the one inside the solid.
    """
    i = "xyz".index(AXIS_OF[face])
    shaft, mouth = (list(lo), list(hi)), (list(lo), list(hi))
    if face in NEGATIVE:
        cut = lo[i]+MOUTH_DEPTH
        shaft[0][i], mouth[1][i] = cut, cut
    else:
        cut = hi[i]-MOUTH_DEPTH
        shaft[1][i], mouth[0][i] = cut, cut
    return shaft, mouth


def build_run_stub_models(assets, mount, frm, to):
    """One model per face that needs to close the gap between the box and a conduit's flush arm.

    Before this the box was the one surface a run reaches that never grew to meet it: a conduit
    already draws an arm all the way to the block edge on a face it joins the box across (see
    TileEntityConduit.connectsTo and the commit that added it), but the box's own model stopped
    short of that same edge on every face except the one it already sits flush against. The gap
    read as a run that had not finished, or as a second one starting apart from the first.

    A stub only closes that gap if it is in the run's plane, which is why there is one set per
    mounting face rather than one set: a stub grown from a floor-standing housing toward a wall
    run passes three pixels behind the arm it is supposed to meet and closes nothing.

    Textured and coloured like the box itself: a stub is the box's own housing reaching out, not a
    length of tubing, so it uses the box's texture rather than the conduit's.
    """
    out = os.path.join(assets, "models", "block", "conduit")
    written = []
    for face in FACINGS:
        bounds = run_stub_bounds(frm, to, face)
        if bounds is None:
            continue
        lo, hi = bounds
        faces = stub_faces(mount, face)
        box_textures = {
            "box": "immersiveengineering:blocks/conduit_junction_box",
            "particle": "immersiveengineering:blocks/conduit_junction_box",
        }
        write_json(os.path.join(out, "junction_run_%s_%s.json" % (mount, face)), {
            "textures": box_textures,
            "elements": [{"from": lo, "to": hi, "faces": faces}],
        })
        # The same stub cut short, and the coloured mouth that finishes it.  A face with a
        # conductor broken out on it wears these two instead of the whole stub above, so the
        # colour sits at the block edge where the hardware bolted to it can be seen against it.
        (shaft_lo, shaft_hi), (mouth_lo, mouth_hi) = split_stub(lo, hi, face)
        write_json(os.path.join(out, "junction_run_%s_%s_short.json" % (mount, face)), {
            "textures": box_textures,
            "elements": [{"from": shaft_lo, "to": shaft_hi, "faces": faces}],
        })
        # Tinted through the face's own ordinal, exactly as the flat plate is, so
        # BlockConduit.getRenderColour needs no second mapping table.
        tinted = {f: {"texture": "#patch", "tintindex": FACINGS.index(face)} for f in FACINGS}
        write_json(os.path.join(out, "junction_mouth_%s_%s.json" % (mount, face)), {
            "textures": {
                "patch": "immersiveengineering:blocks/conduit_patch",
                "particle": "immersiveengineering:blocks/conduit_patch",
            },
            "elements": [{"from": mouth_lo, "to": mouth_hi, "faces": tinted}],
        })
        written.append(face)
    return written


def build_patch_texture(assets):
    """The plate a patched face wears: near-white, so the tint that multiplies it reads true.

    A mid-grey plate would drag every dye toward mud -- tinting multiplies, so whatever the
    texture is not, the result cannot be.  The dark border is what stops a white channel's plate
    disappearing into the box's lit face.
    """
    img = Image.new("RGBA", (16, 16), PATCH_FILL)
    px = img.load()
    rect(px, 0, 0, 15, 15, OUTLINE)
    rect(px, 1, 1, 14, 14, PATCH_FILL)
    # A seam across the middle, so a plate still reads as hardware rather than as a paint splash.
    rect(px, 2, 7, 13, 8, PATCH_SEAM)
    out = os.path.join(assets, "textures", "blocks", "conduit_patch.png")
    img.save(out, "PNG", optimize=True)
    return out


# ---------------------------------------------------------------------------
# The junction box sprite: a galvanized pull box.
#
# Thirty-two texels square so that it has room for a lid, a side and the plain plate the stubs
# wear, each at one texel per block pixel -- face_uv takes the sprite size and works in
# sixteenths of it, so a 32-texel sprite simply has four times the area at the same density as
# the conduit's.  The housing is ten pixels across and eight deep (ConduitBounds), and the
# patterns are exactly that size, so nothing is stretched except the stubs' length.
# ---------------------------------------------------------------------------
BOX_SPRITE = 32


def _lid():
    """The cover: a darker rim, a brushed plate, and a screw in each corner."""
    import random
    rng = random.Random(0x1d1e)
    rows = []
    for r in range(10):
        row = []
        for c in range(10):
            if r in (0, 9) or c in (0, 9):
                row.append(STEEL_SHADE)
            else:
                # Brushed, not flat: a steel lid with no variation in it reads as plastic.
                shift = rng.choice((-6, -3, 0, 0, 3, 6))
                row.append(tuple(min(255, max(0, v + shift)) for v in STEEL_LIT[:3]) + (255,))
        rows.append(row)
    for r, c in ((2, 2), (2, 7), (7, 2), (7, 7)):
        rows[r][c] = SCREW
        rows[r - 1][c - 1] = STEEL_HI
    return rows


# Down a side of the box, from the lid to the wall: the lid's lip catching the light, the shadow
# under it, then the body darkening toward the surface it is bolted to.
BOX_SIDE_PROFILE = [STEEL_LIT, STEEL_SHADE, STEEL_MID, STEEL_MID, STEEL_MID,
                    STEEL_SHADE, STEEL_SHADE, STEEL_DARK]


def _side():
    """A side with a knockout in it: the stamped disc a real box has on every face a conduit
    could come in by.  Only faces with no run on them show one -- a stub covers the rest --
    which is exactly where a real one would still be in place."""
    rows = _along(BOX_SIDE_PROFILE, 10)
    knockout = [".DD.", "DLMD", "DMMD", ".DD."]
    for r, line in enumerate(knockout):
        for c, mark in enumerate(line):
            colour = {"D": STEEL_DARK, "L": STEEL_LIT, "M": STEEL_MID}.get(mark)
            if colour is not None:
                rows[2 + r][3 + c] = colour
    return rows


BOX_LID = Region(_lid(), h=(0, 0), v=(0, 20))
BOX_SIDE = Region(_side(), h=(10, 0), v=(20, 0))
# What a stub wears: the box's own plate carried out to the block edge, with no lid and no
# knockout -- a stub is the housing reaching out to meet a run, so it is plain sheet.
STUB_TOP = Region(_along([STEEL_SHADE] + [STEEL_LIT] * 8 + [STEEL_SHADE]), h=(0, 10), v=(4, 10))
STUB_SIDE = Region(_along(BOX_SIDE_PROFILE), h=(14, 10), v=(18, 10))
BOX_BACK = Region(_along([STEEL_DARK] * 4), h=(26, 10), v=(26, 10))
BOX_REGIONS = (BOX_LID, BOX_SIDE, STUB_TOP, STUB_SIDE, BOX_BACK)


def housing_faces(mount):
    """The box itself: the lid on the face away from the wall, a knocked-out side on the four
    around it."""
    exposed = OPPOSITE[mount]
    e_axis = AXIS_OF[exposed]
    faces = {}
    for face in FACINGS:
        a, b = in_face_axes(face)
        if face == exposed:
            uv = face_uv(face, BOX_LID, a, b, BOX_SPRITE)
        elif face == mount:
            uv = face_uv(face, BOX_BACK, a, b, BOX_SPRITE)
        else:
            uv = face_uv(face, BOX_SIDE, e_axis, other_axis(AXIS_OF[face], e_axis), BOX_SPRITE,
                         row_rev=exposed not in NEGATIVE)
        faces[face] = {"texture": "#box", "uv": uv}
    return faces


def stub_faces(mount, toward):
    """A stub reaching from the box toward `toward`: plain plate, shaded like the box's sides."""
    exposed = OPPOSITE[mount]
    e_axis = AXIS_OF[exposed]
    length_axis = AXIS_OF[toward]
    faces = {}
    for face in FACINGS:
        a, b = in_face_axes(face)
        if face == mount:
            uv = face_uv(face, BOX_BACK, a, b, BOX_SPRITE)
        elif face == exposed:
            if length_axis == e_axis:
                # The stub standing off the lid toward a run above the box: its end is the lid.
                uv = face_uv(face, BOX_LID, a, b, BOX_SPRITE)
            else:
                uv = face_uv(face, STUB_TOP, other_axis(length_axis, e_axis), length_axis,
                             BOX_SPRITE)
        else:
            uv = face_uv(face, STUB_SIDE, e_axis, other_axis(AXIS_OF[face], e_axis), BOX_SPRITE,
                         row_rev=exposed not in NEGATIVE)
        faces[face] = {"texture": "#box", "uv": uv}
    return faces


def build_junction_texture(assets):
    """The box: a galvanized pull box, deliberately unlike the tube.

    A player scanning a wall has to be able to tell a box from a straight run at a glance,
    because the box is the only part they can interact with -- so it has a lid with screws,
    where the tube has a highlight.
    """
    img = Image.new("RGBA", (BOX_SPRITE, BOX_SPRITE), STEEL_MID)
    px = img.load()
    for region in BOX_REGIONS:
        region.paint(px)
    out = os.path.join(assets, "textures", "blocks", "conduit_junction_box.png")
    img.save(out, "PNG", optimize=True)
    return out


def build_feeder_texture(assets):
    """The bare feeder's face: a galvanized plate with a length of conduit coming through it.

    The same tile on all six faces, read at the faces' own coordinates, which is right for a
    whole cube: every face shows all of it.  Only ever seen on a feeder that has not found
    anything to wear yet, or in the creative tab.
    """
    import random
    rng = random.Random(0xfeed)
    img = Image.new("RGBA", (16, 16), STEEL_MID)
    px = img.load()
    for x in range(16):
        for y in range(16):
            shift = rng.choice((-6, -3, 0, 0, 3, 6))
            px[x, y] = tuple(min(255, max(0, v + shift)) for v in STEEL_MID[:3]) + (255,)
    # A bevel, lit from the top left, so a bare feeder does not read as a flat grey square.
    rect(px, 0, 0, 15, 0, STEEL_LIT)
    rect(px, 0, 0, 0, 15, STEEL_LIT)
    rect(px, 0, 15, 15, 15, STEEL_DARK)
    rect(px, 15, 0, 15, 15, STEEL_DARK)
    for bx, by in ((2, 2), (13, 2), (2, 13), (13, 13)):
        px[bx, by] = SCREW
        px[bx - 1, by - 1] = STEEL_HI
    # The port: an octagonal hole with the end of a tube in it -- a bright rim, and the dark
    # of the tube's bore in the middle.  Octagonal rather than square because a square hole in
    # a square face reads as a panel, not as something passing through.
    rect(px, 5, 4, 10, 11, OUTLINE)
    rect(px, 4, 5, 11, 10, OUTLINE)
    rect(px, 6, 5, 9, 10, STEEL_LIT)
    rect(px, 5, 6, 10, 9, STEEL_LIT)
    rect(px, 6, 5, 9, 5, STEEL_HI)
    rect(px, 5, 6, 5, 9, STEEL_HI)
    rect(px, 6, 10, 9, 10, STEEL_SHADE)
    rect(px, 10, 6, 10, 9, STEEL_SHADE)
    rect(px, 7, 6, 8, 9, OUTLINE)
    rect(px, 6, 7, 9, 8, OUTLINE)
    out = os.path.join(assets, "textures", "blocks", "conduit_ground_feeder.png")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    img.save(out, "PNG", optimize=True)
    return out


def build_ground_feeder(assets):
    """The ground feeder: a whole cube, and the blockstate that hands it to the smart model.

    Almost nothing is written here, and that is the point.  A feeder in the world is drawn as
    whatever block is around it -- the quads come from that block's own baked model at render
    time, so there is no model here to generate for the case anybody will actually see.

    What *is* generated is the bare form: what a feeder wears when it has found nothing to
    copy, and what the item shows in a creative tab where there are no surroundings at all.
    A plain steel cube with a port through it, so an undisguised one reads as hardware
    somebody has yet to bury rather than as a missing texture.
    """
    faces = {}
    for face in FACINGS:
        faces[face] = {"texture": "#feeder"}
    write_json(os.path.join(assets, "models", "block", "conduit", "ground_feeder.json"), {
        "textures": {
            "feeder": "immersiveengineering:blocks/conduit_ground_feeder",
            "particle": "immersiveengineering:blocks/conduit_ground_feeder",
        },
        "elements": [{"from": [0, 0, 0], "to": [16, 16, 16], "faces": faces}],
    })
    # Multipart with a single unconditional part, for the reason the junction box is multipart:
    # BlockConduit declares facing and six sideconnection flags for every meta, so a `variants`
    # file would have to carry a submap for each or fail to resolve.  Multipart ignores the
    # variant string and reads the state.
    #
    # The model reference is NOT one of the generated ones.  `smartmodel/conduit_disguise` is
    # claimed by ConduitDisguiseLoader, which builds the model in code -- there is no file behind
    # it and there must not be one.
    write_json(os.path.join(assets, "blockstates", "conduit_ground_feeder.json"), {
        "multipart": [{
            "apply": {"model": "immersiveengineering:smartmodel/conduit_disguise"},
        }],
    })


def build_item_blockstate(assets):
    """The item form, in the Forge blockstate format IE looks the item model up through.

    ClientProxy registers a block's item as `<name>#inventory,<property>=<value>`, so this
    file has to exist and has to carry that exact variant even though the block itself is
    described elsewhere.
    """
    write_json(os.path.join(assets, "blockstates", "conduit.json"), {
        "forge_marker": 1,
        "defaults": {
            "transform": "forge:default-block",
            "model": MODEL_REF % "conduit_item",
        },
        "variants": {
            "inventory,type=conduit_run": [{}],
            # The floor-mounted housing: an item has no surroundings either, so there is no run
            # for the box to pick a plane from, and a box with no runs is a box on the floor.
            "inventory,type=junction_box": [{
                "model": MODEL_REF % "junction_box_down",
            }],
            # The item shows the bare cube rather than the smart model: an item has no
            # surroundings, so there is nothing for a disguise to be.
            "inventory,type=ground_feeder": [{
                "model": MODEL_REF % "ground_feeder",
            }],
            "type": {"conduit_run": {}, "junction_box": {}, "ground_feeder": {}},
        },
    })


def rect(px, x0, y0, x1, y1, colour):
    for x in range(x0, x1 + 1):
        for y in range(y0, y1 + 1):
            px[x, y] = colour


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    repo = os.path.dirname(os.path.dirname(here))
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets", default=os.path.join(
        repo, "src", "main", "resources", "assets", "immersiveengineering"))
    args = parser.parse_args()

    depth, half, box_half = read_bounds_constants(repo)
    models = build_models(args.assets, depth, half)
    parts = build_blockstate(args.assets)
    build_junction_box(args.assets, depth, box_half)
    build_ground_feeder(args.assets)
    build_item_blockstate(args.assets)
    texture = build_texture(args.assets)
    build_junction_texture(args.assets)
    build_patch_texture(args.assets)
    build_feeder_texture(args.assets)

    print("depth=%d half_width=%d junction_half=%d (read from ConduitBounds.java)"
          % (depth, half, box_half))
    print("wrote %d models, %d blockstate parts" % (len(models), len(parts)))
    print("wrote %s" % os.path.relpath(texture, repo))


if __name__ == "__main__":
    main()
