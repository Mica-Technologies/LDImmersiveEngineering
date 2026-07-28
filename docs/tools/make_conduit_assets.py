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

FACINGS = ["down", "up", "north", "south", "west", "east"]
AXIS_OF = {"down": "y", "up": "y", "north": "z", "south": "z", "west": "x", "east": "x"}
NEGATIVE = {"down", "north", "west"}


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
            "apply": {"model": "immersiveengineering:block/conduit/conduit_%s_hub" % mount},
        })
        for arm in in_plane(mount):
            parts.append({
                "when": {"facing": mount, "sideconnection_%s" % arm: "true"},
                "apply": {"model": "immersiveengineering:block/conduit/conduit_%s_%s" % (mount, arm)},
            })
    write_json(os.path.join(assets, "blockstates", "conduit_run.json"), {"multipart": parts})
    return parts


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
            "model": "immersiveengineering:block/conduit/conduit_item",
        },
        "variants": {
            "inventory,type=conduit_run": [{}],
            "type": {"conduit_run": {}},
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
    build_item_blockstate(args.assets)
    texture = build_texture(args.assets, depth)

    print("depth=%d half_width=%d (read from ConduitBounds.java)" % (depth, half))
    print("wrote %d models, %d blockstate parts" % (len(models), len(parts)))
    print("wrote %s" % os.path.relpath(texture, repo))


if __name__ == "__main__":
    main()
