#!/usr/bin/env python3
"""
Regenerates the conduit block's texture, models and blockstate.

Conduit is surface-mounted tubing: it lies flat against a face and turns in right
angles, which is the shape IE's catenary wires cannot make and the reason the block
exists.  It is drawn by an ordinary multipart blockstate -- a hub pad against the
mounting face plus one arm per joined direction -- rather than by a renderer.  Thirty
small models, every one an axis-aligned box, and nothing drawn per frame.

Everything here is generated because the alternative is thirty hand-written JSON files
whose only difference is six numbers, and a typo in one of them shows up as a single
mis-shaped elbow somewhere in a base.

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


def model_json(*boxes):
    """A model of one or more boxes, every face textured.

    Faces are given explicit UVs taken from the box itself, so the tube's stripe runs
    along the run rather than being stretched differently on each length of it.
    """
    elements = []
    for frm, to in boxes:
        faces = {}
        for face in ("down", "up", "north", "south", "west", "east"):
            if face in ("down", "up"):
                uv = [frm[0], frm[2], to[0], to[2]]
            elif face in ("north", "south"):
                uv = [frm[0], 16 - to[1], to[0], 16 - frm[1]]
            else:
                uv = [frm[2], 16 - to[1], to[2], 16 - frm[1]]
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
    """The thirty pieces: six hubs and twenty-four arms."""
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
    """The block's multipart blockstate: the hub always, each arm when its side connects.

    Multipart rather than variants because the alternative is enumerating 6 facings x 16
    connection combinations by hand.  The `when` clauses use the same absolute
    sideconnection_* properties BlockConduit fills in from the tile entity, so reading
    one against the other needs no translation.

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
            parts.append({
                "when": {"facing": mount, "sideconnection_%s" % arm: "true"},
                "apply": {"model": MODEL_REF % ("conduit_%s_%s" % (mount, arm))},
            })
    write_json(os.path.join(assets, "blockstates", "conduit_run.json"), {"multipart": parts})
    return parts


def build_junction_box(assets, depth, half):
    """The junction box: a squat surface box, and the blockstate that draws it.

    Plainer than the conduit on purpose.  It is a thing you walk up to and right-click with
    a dye, so it wants to read as a box with a lid rather than as more tubing, and it wants
    to be visible from across a room.  A cube inset on every side, drawn with its own
    texture, does both and costs one model.
    """
    size = 5  # half-width in pixels; 6..10 would be lost against a run
    frm = [8 - size, 0, 8 - size]
    to = [8 + size, 2 * depth + 2, 8 + size]
    faces = {}
    for face in ("down", "up", "north", "south", "west", "east"):
        faces[face] = {"texture": "#box"}
    write_json(os.path.join(assets, "models", "block", "conduit", "junction_box.json"), {
        "textures": {
            "box": "immersiveengineering:blocks/conduit_junction_box",
            "particle": "immersiveengineering:blocks/conduit_junction_box",
        },
        "elements": [{"from": frm, "to": to, "faces": faces}],
    })
    build_patch_models(assets, frm, to)
    # Multipart: the box always, plus a coloured plate on each patched face.
    #
    # A `variants` file would have to resolve the *whole* property string the state mapper hands
    # it -- type, facing and all six sideconnection flags, because BlockConduit declares them for
    # every meta -- which in the Forge format means a submap per property or the variant simply
    # does not resolve.  Multipart ignores the variant string entirely and reads the state, so a
    # part with no `when` is both correct and the shorter thing to write.
    #
    # The plates reuse the same absolute sideconnection_* properties the run does; BlockConduit
    # fills them from the patch table rather than from a connection mask when the tile is a box.
    # Same properties, same file format, one meaning per block type.
    parts = [{"apply": {"model": MODEL_REF % "junction_box"}}]
    for face in FACINGS:
        parts.append({
            "when": {"sideconnection_%s" % face: "true"},
            "apply": {"model": MODEL_REF % ("junction_patch_%s" % face)},
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


def build_patch_models(assets, frm, to):
    """One plate model per face, each tinted through its own tint index.

    Six models and six tint indices rather than ninety-six models, because the colour is not in
    the model at all: the plate is painted near-white and `BlockConduit.getRenderColour` supplies
    the dye per face at render time.  Enumerating six faces times sixteen colours as separate
    models would be the other way to do this and would cost a hundred bakes to say the same thing.

    The tint index is the face's ordinal in EnumFacing order, which is what FACINGS is, so the
    Java side needs no mapping table -- it reads EnumFacing.byIndex(tintIndex) straight off.
    """
    out = os.path.join(assets, "models", "block", "conduit")
    for index, face in enumerate(FACINGS):
        lo, hi = patch_bounds(frm, to, face)
        faces = {f: {"texture": "#patch", "tintindex": index} for f in FACINGS}
        write_json(os.path.join(out, "junction_patch_%s.json" % face), {
            "textures": {"patch": "immersiveengineering:blocks/conduit_patch"},
            "elements": [{"from": lo, "to": hi, "faces": faces}],
        })


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
            "inventory,type=junction_box": [{
                "model": MODEL_REF % "junction_box",
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
