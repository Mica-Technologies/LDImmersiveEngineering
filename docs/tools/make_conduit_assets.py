#!/usr/bin/env python3
"""
Regenerates the conduit block's texture, models and blockstate.

Conduit is surface-mounted tubing: it lies flat against a face, turns in right angles,
and wraps around the corners of what it is clipped to, which is the shape IE's catenary
wires cannot make and the reason the block exists.  It is drawn by an ordinary multipart
blockstate -- a hub pad against the mounting face plus one arm per joined direction, in
whichever of its three forms that arm has -- rather than by a renderer.  Seventy-eight
small models, every one an axis-aligned box, and nothing drawn per frame.

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
# Palette -- conduit is painted steel tubing, so it is lighter and flatter than the
# gunmetal the grid hardware uses.  It has to read as "clipped to that wall" at a
# glance, which means a bright tube and a visibly darker clip band.
# ---------------------------------------------------------------------------
OUTLINE = (38, 38, 42, 255)
TUBE_SHADE = (128, 128, 134, 255)
TUBE = (176, 176, 182, 255)
TUBE_LIT = (208, 208, 214, 255)
CLIP = (86, 86, 92, 255)
CLIP_LIT = (108, 108, 114, 255)
# The patch plate is deliberately near-white rather than steel: it is the one surface in this
# feature whose whole job is to carry a tint, and a tint multiplies whatever is under it.
PATCH_FILL = (236, 236, 238, 255)
PATCH_SEAM = (198, 198, 202, 255)

FACINGS = ["down", "up", "north", "south", "west", "east"]
AXIS_OF = {"down": "y", "up": "y", "north": "z", "south": "z", "west": "x", "east": "x"}
NEGATIVE = {"down", "north", "west"}

# How a blockstate refers to one of the models above.
#
# **The `models/block/` prefix is implied and must not be written out.**  A blockstate saying
# `immersiveengineering:block/conduit/x` resolves to `models/block/block/conduit/x.json`, which
# does not exist -- and a missing model is a purple block with nothing in the log.  IE's own files
# say `immersiveengineering:grid/utility_box` for `models/block/grid/utility_box.json`; this
# follows them.
MODEL_REF = "immersiveengineering:conduit/%s"


def read_bounds_constants(repo):
    """Take DEPTH and HALF_WIDTH from the Java rather than restating them here.

    If ConduitBounds moves its numbers, the models move with it or this script stops --
    which is the point.  A silent divergence between the box you click and the tube you
    see is the exact failure this guards against.
    """
    path = os.path.join(repo, "src", "main", "java", "blusunrize", "immersiveengineering",
                        "common", "blocks", "conduit", "ConduitBounds.java")
    with open(path, encoding="utf-8") as handle:
        source = handle.read()
    found = {}
    for name in ("DEPTH", "HALF_WIDTH"):
        match = re.search(r"int\s+%s\s*=\s*(\d+)\s*;" % name, source)
        if not match:
            raise SystemExit("could not find %s in ConduitBounds.java" % name)
        found[name] = int(match.group(1))
    return found["DEPTH"], found["HALF_WIDTH"]


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
    uv_lo, uv_hi = list(lo), list(hi)
    axis = "xyz".index(AXIS_OF[arm])
    if arm in NEGATIVE:
        hi[axis], lo[axis] = 0, -depth
        uv_lo[axis], uv_hi[axis] = 0, depth
    else:
        lo[axis], hi[axis] = 16, 16 + depth
        uv_lo[axis], uv_hi[axis] = 16 - depth, 16
    # UVs come from where the cube *would* be if it were inside the block.  Faces are
    # given explicit UVs taken from their own coordinates, and a coordinate outside 0..16
    # is outside the sprite -- on an atlas that samples whatever texture happens to be
    # next door.  Taken from the last few pixels of the arm instead, the cap carries the
    # tube's stripe straight around the corner.
    return (lo, hi, uv_lo, uv_hi)


def model_json(*boxes):
    """A model of one or more boxes, every face textured.

    Faces are given explicit UVs taken from the box itself, so the tube's stripe runs
    along the run rather than being stretched differently on each length of it.

    A box may carry a second pair of corners to take its UVs from instead.  Only the
    outer-corner cap needs it, and it needs it because it is the one piece that lies
    outside its own block: a UV outside 0..16 samples past the edge of the sprite, which
    on a stitched atlas means whatever texture was placed next to this one.
    """
    elements = []
    for entry in boxes:
        frm, to = entry[0], entry[1]
        uv_frm, uv_to = (entry[2], entry[3]) if len(entry) == 4 else (frm, to)
        faces = {}
        for face in ("down", "up", "north", "south", "west", "east"):
            if face in ("down", "up"):
                uv = [uv_frm[0], uv_frm[2], uv_to[0], uv_to[2]]
            elif face in ("north", "south"):
                uv = [uv_frm[0], 16 - uv_to[1], uv_to[0], 16 - uv_frm[1]]
            else:
                uv = [uv_frm[2], 16 - uv_to[1], uv_to[2], 16 - uv_frm[1]]
            faces[face] = {"texture": "#conduit", "uv": uv}
        elements.append({"from": frm, "to": to, "faces": faces})
    return {
        "textures": {
            "conduit": "immersiveengineering:blocks/conduit",
            "particle": "immersiveengineering:blocks/conduit",
        },
        "elements": elements,
    }


def write_json(path, body):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(body, handle, indent="\t")
        handle.write("\n")


def build_models(assets, depth, half):
    """The seventy-eight pieces: six hubs, and each of twenty-four arms in three forms.

    An arm is straight, or it climbs the block in front of it (an inner corner), or it
    carries on a little past the block edge to cap an outer one.  Three whole models
    rather than a straight arm plus an optional extra part, because a multipart part
    either applies or it does not: straight and climbing are two states of one arm, not
    one arm with something added.
    """
    out = os.path.join(assets, "models", "block", "conduit")
    written = []
    for mount in FACINGS:
        name = "conduit_%s_hub" % mount
        write_json(os.path.join(out, name + ".json"),
                   model_json(box(mount, depth, half)))
        written.append(name)
        for arm in in_plane(mount):
            name = "conduit_%s_%s" % (mount, arm)
            write_json(os.path.join(out, name + ".json"),
                       model_json(box(mount, depth, half, arm)))
            written.append(name)
            write_json(os.path.join(out, name + "_riser.json"),
                       model_json(box(mount, depth, half, arm),
                                  riser_box(mount, depth, half, arm)))
            written.append(name + "_riser")
            write_json(os.path.join(out, name + "_wrap.json"),
                       model_json(box(mount, depth, half, arm),
                                  wrap_box(mount, depth, half, arm)))
            written.append(name + "_wrap")

    # The held item and the creative-tab icon: a straight length lying on the floor,
    # which is what placing one actually gives you. Assembled from the same three boxes
    # the block would use rather than drawn separately, so it cannot drift.
    write_json(os.path.join(out, "conduit_item.json"), model_json(
        box("down", depth, half),
        box("down", depth, half, "north"),
        box("down", depth, half, "south")))
    written.append("conduit_item")
    return written


def build_blockstate(assets):
    """The block's multipart blockstate: the hub always, and each arm in whichever form it has.

    Multipart rather than variants because the alternative is enumerating 6 facings x 16
    connection combinations by hand.  The `when` clauses use the same absolute
    sideconnection_* and runconnection_* properties BlockConduit fills in from the tile
    entity, so reading one against the other needs no translation.

    **Two properties per arm, spelling four states.**  Since runs learned to turn corners
    an arm is one of four things, and the pair reads:

        sideconnection = the tubing crosses this cell boundary in the plane of the surface
        runconnection  = the arm toward this face turns a corner

    so false/false is no arm, true/false a straight one, false/true a riser -- which stops
    at the edge and climbs -- and true/true a straight arm that carries on past the edge to
    cap an outer corner.  Nothing was added to the block state to pay for corners: Forge
    builds the cartesian product of every listed property at startup, and this block already
    declares fourteen.

    It lives in conduit_run.json rather than conduit.json, and BlockConduit's custom state
    mapper is what points at it -- the same split IE's fences use.  A multipart file cannot
    also carry the `inventory,...` variant the item model is looked up through, so the two
    have to be separate files.  Getting either half wrong gives a purple block and no error,
    which is why build_item_blockstate below is not optional.
    """
    parts = []
    for mount in FACINGS:
        parts.append({
            "when": {"facing": mount},
            "apply": {"model": MODEL_REF % ("conduit_%s_hub" % mount)},
        })
        for arm in in_plane(mount):
            for suffix, side, run in (("", "true", "false"),
                                      ("_riser", "false", "true"),
                                      ("_wrap", "true", "true")):
                parts.append({
                    "when": {
                        "facing": mount,
                        "sideconnection_%s" % arm: side,
                        "runconnection_%s" % arm: run,
                    },
                    "apply": {
                        "model": MODEL_REF % ("conduit_%s_%s%s" % (mount, arm, suffix)),
                    },
                })
    write_json(os.path.join(assets, "blockstates", "conduit_run.json"), {"multipart": parts})
    return parts


# Half the junction box's width across the surface it is mounted on, in pixels.  Five, because
# six to ten would be lost against a run and four would not read as hardware from across a room.
BOX_HALF = 5


def box_bounds(mount, depth):
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
    lo = [8 - BOX_HALF] * 3
    hi = [8 + BOX_HALF] * 3
    axis = "xyz".index(AXIS_OF[mount])
    stand_off = 2 * depth + 2
    if mount in NEGATIVE:
        lo[axis], hi[axis] = 0, stand_off
    else:
        lo[axis], hi[axis] = 16 - stand_off, 16
    return lo, hi


def build_junction_box(assets, depth, half):
    """The junction box: a squat surface box per mounting face, and the blockstate that draws it.

    Plainer than the conduit on purpose.  It is a thing you walk up to and right-click with
    a dye, so it wants to read as a box with a lid rather than as more tubing, and it wants
    to be visible from across a room.  A cube inset on every side, drawn with its own
    texture, does both and costs one model per face it can be bolted to.

    Six of everything, keyed on `facing`, which costs no block state at all: BlockConduit
    already declares `facing` for every meta and nothing else fills it in for a box.  The box
    has no facing of its own -- no tile entity field, no placement rule, no packet -- it is
    derived from the runs that reach it, in `ConduitGeometry.junctionBoxMount`.
    """
    out = os.path.join(assets, "models", "block", "conduit")
    faces = {face: {"texture": "#box"} for face in FACINGS}
    # Multipart: one housing per mounting face, plus a coloured plate on each patched face, plus a
    # stub toward each face a run is physically touching -- all three keyed on the mount as well,
    # since where the housing is decides where a plate sits and what a stub has to bridge.
    #
    # A `variants` file would have to resolve the *whole* property string the state mapper hands
    # it -- type, facing and all twelve connection flags, because BlockConduit declares them for
    # every meta -- which in the Forge format means a submap per property or the variant simply
    # does not resolve.  Multipart ignores the variant string entirely and reads the state.
    #
    # The plates reuse the same absolute sideconnection_* properties the run does; BlockConduit
    # fills them from the patch table rather than from a connection mask when the tile is a box.
    # Same properties, same file format, one meaning per block type.  The stubs key off
    # runconnection_*, a second and unrelated set -- see IEProperties.RUNCONNECTION.
    parts = []
    for mount in FACINGS:
        frm, to = box_bounds(mount, depth)
        write_json(os.path.join(out, "junction_box_%s.json" % mount), {
            "textures": {
                "box": "immersiveengineering:blocks/conduit_junction_box",
                "particle": "immersiveengineering:blocks/conduit_junction_box",
            },
            "elements": [{"from": frm, "to": to, "faces": faces}],
        })
        parts.append({
            "when": {"facing": mount},
            "apply": {"model": MODEL_REF % ("junction_box_%s" % mount)},
        })
        for face in build_patch_models(assets, mount, frm, to):
            parts.append({
                "when": {"facing": mount, "sideconnection_%s" % face: "true"},
                "apply": {"model": MODEL_REF % ("junction_patch_%s_%s" % (mount, face))},
            })
        # Only the faces build_run_stub_models actually wrote get a part: the box already reaches
        # the block edge on the face it is bolted to, so there is no gap to close there and no
        # model to name.
        for face in build_run_stub_models(assets, mount, frm, to):
            parts.append({
                "when": {"facing": mount, "runconnection_%s" % face: "true"},
                "apply": {"model": MODEL_REF % ("junction_run_%s_%s" % (mount, face))},
            })
    write_json(os.path.join(assets, "blockstates", "conduit_junction_box.json"), {
        "multipart": parts,
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
        faces = {f: {"texture": "#box"} for f in FACINGS}
        write_json(os.path.join(out, "junction_run_%s_%s.json" % (mount, face)), {
            "textures": {
                "box": "immersiveengineering:blocks/conduit_junction_box",
                "particle": "immersiveengineering:blocks/conduit_junction_box",
            },
            "elements": [{"from": lo, "to": hi, "faces": faces}],
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


def build_junction_texture(assets):
    """The box's face: a steel lid with four corner bolts and a dark seam.

    Deliberately unlike the tube texture.  A player scanning a wall has to be able to tell a
    box from a straight run at a glance, because the box is the only part they can interact
    with.
    """
    img = Image.new("RGBA", (16, 16), CLIP)
    px = img.load()
    rect(px, 0, 0, 15, 15, CLIP)
    rect(px, 1, 1, 14, 14, CLIP_LIT)
    rect(px, 0, 0, 15, 0, TUBE_SHADE)
    rect(px, 0, 15, 15, 15, OUTLINE)
    rect(px, 0, 0, 0, 15, TUBE_SHADE)
    rect(px, 15, 0, 15, 15, OUTLINE)
    # The seam where a lid would come off, and the bolts holding it on.
    rect(px, 2, 7, 13, 8, CLIP)
    for bx, by in ((2, 2), (13, 2), (2, 13), (13, 13)):
        px[bx, by] = TUBE_LIT
    out = os.path.join(assets, "textures", "blocks", "conduit_junction_box.png")
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


def build_feeder_texture(assets):
    """The bare feeder's face: a steel plate with a conduit port through the middle of it.

    The same tile on all six faces, which is right for a block whose whole idea is that a run
    goes in one side and out the other.  Only ever seen on a feeder that has not found anything
    to wear yet, or in the creative tab.
    """
    img = Image.new("RGBA", (16, 16), CLIP_LIT)
    px = img.load()
    rect(px, 0, 0, 15, 15, CLIP_LIT)
    # A bevel, lit from the top left, so a bare feeder does not read as a flat grey square.
    rect(px, 0, 0, 15, 0, TUBE_SHADE)
    rect(px, 0, 0, 0, 15, TUBE_SHADE)
    rect(px, 0, 15, 15, 15, OUTLINE)
    rect(px, 15, 0, 15, 15, OUTLINE)
    for bx, by in ((2, 2), (13, 2), (2, 13), (13, 13)):
        px[bx, by] = TUBE_LIT
    # The port: an octagonal hole, with a length of tube sitting in it.  Octagonal rather than
    # square because a square hole in a square face reads as a panel, not as something passing
    # through.
    rect(px, 5, 4, 10, 11, OUTLINE)
    rect(px, 4, 5, 11, 10, OUTLINE)
    rect(px, 6, 5, 9, 10, TUBE)
    rect(px, 5, 6, 10, 9, TUBE)
    rect(px, 6, 5, 9, 5, TUBE_LIT)
    rect(px, 6, 10, 9, 10, TUBE_SHADE)
    out = os.path.join(assets, "textures", "blocks", "conduit_ground_feeder.png")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    img.save(out, "PNG", optimize=True)
    return out


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


def build_texture(assets, depth):
    """One 16x16 tile, used by every face of every piece.

    Laid out as a run of tubing seen side-on: a highlight along the top of the tube, a
    shaded underside, and clip bands at the quarter points so a long straight run reads
    as clipped down at intervals rather than as one extruded stick.
    """
    img = Image.new("RGBA", (16, 16), TUBE)
    px = img.load()
    rect(px, 0, 0, 15, 15, TUBE)
    # The tube's own shading: lit along the top, shaded along the bottom.
    rect(px, 0, 0, 15, 1, TUBE_LIT)
    rect(px, 0, 13, 15, 15, TUBE_SHADE)
    rect(px, 0, 15, 15, 15, OUTLINE)
    # Clip bands. Two of them, at the quarter points, so a straight run shows a clip
    # roughly every half block whichever way the UV falls.
    for band in (3, 11):
        rect(px, band, 0, band + 1, 15, CLIP)
        rect(px, band, 0, band + 1, 1, CLIP_LIT)
        rect(px, band, 15, band + 1, 15, OUTLINE)
    out = os.path.join(assets, "textures", "blocks", "conduit.png")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    img.save(out, "PNG", optimize=True)
    return out


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    repo = os.path.dirname(os.path.dirname(here))
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets", default=os.path.join(
        repo, "src", "main", "resources", "assets", "immersiveengineering"))
    args = parser.parse_args()

    depth, half = read_bounds_constants(repo)
    models = build_models(args.assets, depth, half)
    parts = build_blockstate(args.assets)
    build_junction_box(args.assets, depth, half)
    build_ground_feeder(args.assets)
    build_item_blockstate(args.assets)
    texture = build_texture(args.assets, depth)
    build_junction_texture(args.assets)
    build_patch_texture(args.assets)
    build_feeder_texture(args.assets)

    print("depth=%d half_width=%d (read from ConduitBounds.java)" % (depth, half))
    print("wrote %d models, %d blockstate parts" % (len(models), len(parts)))
    print("wrote %s" % os.path.relpath(texture, repo))


if __name__ == "__main__":
    main()
