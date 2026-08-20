#!/usr/bin/env python3
"""
Regenerates the utility pole signage block's textures, models and blockstates.

Thirteen tags, every one of them a sign that exists on a real pole -- LADWP's and SCE's.
A tag is a flat plate bolted to whatever holds the wires up, so each is one thin
axis-aligned box wearing one 16x16 sprite, and the only thing drawn per frame is the
lettering (see TileRenderUtilitySign).  Fifty-two variants: thirteen plates times four
horizontal facings, and the facings are y-rotations of one model rather than four files.

**The geometry comes out of UtilitySignKind.java rather than being restated here.**  A
plate whose model and whose selection box disagree is obvious in a screenshot and invisible
in code, and the renderer lays text out against the same numbers.

Usage:  python docs/tools/make_signage_assets.py [--assets <assets dir>]

Requires Pillow.
"""

import argparse
import json
import os
import re

from PIL import Image, ImageDraw

# ---------------------------------------------------------------------------
# Palette.  Utility signage is painted or anodised metal in a small number of very
# specific colours, and the colour is most of what identifies a sign at a distance --
# a red strip and a yellow strip mean different things and are told apart before
# anybody is close enough to read either.
# ---------------------------------------------------------------------------
EDGE = (26, 26, 28, 255)
SOFT_EDGE = (70, 70, 74, 255)
RED = (176, 46, 40, 255)
RED_LIT = (198, 62, 54, 255)
YELLOW = (226, 186, 34, 255)
YELLOW_LIT = (242, 208, 70, 255)
WHITE = (238, 238, 236, 255)
WHITE_LIT = (250, 250, 248, 255)
SILVER = (176, 180, 184, 255)
SILVER_LIT = (198, 202, 206, 255)
ORANGE = (216, 118, 32, 255)
ORANGE_LIT = (234, 142, 56, 255)
CLEAR = (0, 0, 0, 0)

MODEL_DIR = os.path.join("models", "block", "signage")
TEXTURE_REF = "immersiveengineering:blocks/sign_%s"
MODEL_REF = "immersiveengineering:signage/sign_%s"

FACING_ROTATION = {"north": 0, "east": 90, "south": 180, "west": 270}


def read_kinds(repo):
    """The thirteen kinds, in ordinal order, straight out of the enum.

    Parsed rather than duplicated: the ordinal is what a sign saves and what the
    blockstate keys on, so a list here that drifted out of order would repaint every
    sign in a world rather than fail.
    """
    path = os.path.join(repo, "src", "main", "java", "blusunrize", "immersiveengineering",
                        "common", "blocks", "signage", "UtilitySignKind.java")
    with open(path, encoding="utf-8") as handle:
        source = handle.read()
    body = source[source.index("public enum UtilitySignKind"):source.index("public static final UtilitySignKind[]")]
    pattern = re.compile(r"^\t([A-Z_]+)\((\d+), (\d+), (\d+), 0x([0-9A-Fa-f]+), (true|false)\)",
                         re.MULTILINE)
    kinds = []
    for match in pattern.finditer(body):
        kinds.append({
            "name": match.group(1).lower(),
            "width": int(match.group(2)),
            "height": int(match.group(3)),
            "lines": int(match.group(4)),
            "rotated": match.group(6) == "true",
        })
    if not kinds:
        raise SystemExit("could not read any kinds out of UtilitySignKind.java")
    return kinds


def plate_rect(kind):
    """Where the plate sits in a 16x16 cell, as (x0, y0, x1, y1), exclusive of the far edge.

    Centred, and every kind is an even number of pixels across and down, so this always
    lands on whole texture pixels -- a half-pixel edge samples between two texels and
    comes out of the atlas as a blurred fringe.
    """
    x0 = 8-kind["width"]//2
    y0 = 8-kind["height"]//2
    return x0, y0, x0+kind["width"], y0+kind["height"]


# ---------------------------------------------------------------------------
# Textures
# ---------------------------------------------------------------------------

def strip(draw, rect, fill, lit, edge=EDGE):
    """A rectangular plate: a body, a lit top-left edge and a dark outline.

    The lit edge is what stops a flat colour reading as a decal.  Two pixels' worth of
    shading is the whole of it -- these are sixteen-pixel sprites and anything more
    detailed disappears at the distance a pole is seen from.
    """
    x0, y0, x1, y1 = rect
    draw.rectangle([x0, y0, x1-1, y1-1], fill=fill, outline=edge)
    draw.line([x0+1, y0+1, x1-2, y0+1], fill=lit)
    draw.line([x0+1, y0+1, x0+1, y1-2], fill=lit)


def oval(draw, rect, fill, lit, edge=EDGE):
    """The painted white oval a series-wired street light wears."""
    x0, y0, x1, y1 = rect
    draw.ellipse([x0, y0, x1-1, y1-1], fill=fill, outline=edge)
    draw.arc([x0+1, y0+1, x1-2, y1-2], 170, 350, fill=lit)


def disc(draw, rect, fill, lit, edge=SOFT_EDGE):
    """The round bolt-on inspection tag."""
    x0, y0, x1, y1 = rect
    draw.ellipse([x0, y0, x1-1, y1-1], fill=fill, outline=edge)
    draw.arc([x0+1, y0+1, x1-2, y1-2], 170, 350, fill=lit)


def diamond(draw, rect, fill, outline=None):
    """A diamond on its point, which is how a tower number is hung.

    The plain LADWP tower diamond has no border at all -- the number is painted straight
    onto the plate -- so the outline is optional rather than assumed.
    """
    x0, y0, x1, y1 = rect
    cx = (x0+x1-1)/2
    cy = (y0+y1-1)/2
    points = [(cx, y0), (x1-1, cy), (cx, y1-1), (x0, cy)]
    draw.polygon(points, fill=fill, outline=outline)


def build_texture(assets, kind):
    """One sprite per kind: the plate on a transparent field.

    Transparent rather than trimmed to the plate, because the model's UVs are the plate's
    own pixel rect in this image -- one sprite, one rect, and no table mapping one to the
    other.
    """
    image = Image.new("RGBA", (16, 16), CLEAR)
    draw = ImageDraw.Draw(image)
    rect = plate_rect(kind)
    name = kind["name"]
    if name == "parallel_generation":
        strip(draw, rect, RED, RED_LIT)
    elif name in ("yellow_vertical", "yellow_horizontal", "tower_horizontal"):
        strip(draw, rect, YELLOW, YELLOW_LIT)
    elif name == "white_vertical":
        strip(draw, rect, WHITE, WHITE_LIT)
    elif name == "silver_vertical":
        strip(draw, rect, SILVER, SILVER_LIT, edge=SOFT_EDGE)
    elif name in ("orange_horizontal", "orange_vertical"):
        strip(draw, rect, ORANGE, ORANGE_LIT)
    elif name == "oval_fraction":
        oval(draw, rect, WHITE, WHITE_LIT)
    elif name == "inspection_round":
        disc(draw, rect, SILVER, SILVER_LIT)
    elif name == "tower_diamond":
        diamond(draw, rect, YELLOW)
    elif name == "line_crossing_diamond":
        diamond(draw, rect, YELLOW, outline=EDGE)
        # The cross this sign is named for.  Drawn short of the points so it reads as a
        # marking on the plate rather than as the plate being cut in four.
        x0, y0, x1, y1 = rect
        draw.line([x0+3, y0+3, x1-4, y1-4], fill=EDGE)
        draw.line([x0+3, y1-4, x1-4, y0+3], fill=EDGE)
    elif name == "tower_vertical":
        strip(draw, rect, YELLOW, YELLOW_LIT)
        # The rule the receiving station's initials sit under.  Two thirds of the way down,
        # which is between the second and third of three evenly spaced lines -- see
        # SignLayout.lineCentre, which is what puts the text either side of it.
        x0, y0, x1, y1 = rect
        rule = y0+(y1-y0)*2//3
        draw.line([x0+1, rule, x1-2, rule], fill=EDGE)
    else:
        raise SystemExit("no artwork for sign kind %s" % name)
    out = os.path.join(assets, "textures", "blocks", "sign_%s.png" % name)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    image.save(out, "PNG", optimize=True)
    return out


# ---------------------------------------------------------------------------
# Models
# ---------------------------------------------------------------------------

THICKNESS = 1


def build_model(assets, kind):
    """One thin box, authored for a sign whose back is against the block to the north.

    The other three facings are that model turned about Y by the blockstate.  A plate is
    the same plate whichever way it is bolted, and four copies of one box is four chances
    for three of them to be subtly wrong.

    The readable face is the +z one and takes the plate's own pixel rect as its UVs, so
    the sprite is drawn at exactly one texel per block pixel.  The four edges are one
    pixel wide and take a single texel from the middle of the plate: sampling their own
    coordinates would put the transparent corner of a diamond along its rim.
    """
    x0, y0, x1, y1 = plate_rect(kind)
    mid_x, mid_y = (x0+x1)//2, (y0+y1)//2
    face_uv = [x0, 16-y1, x1, 16-y0]
    # Mirrored, so the back of a plate reads as the back of it rather than as a second
    # front printed the wrong way round.
    back_uv = [x1, 16-y1, x0, 16-y0]
    edge_uv = [mid_x, 16-mid_y-1, mid_x+1, 16-mid_y]
    faces = {
        "south": {"texture": "#sign", "uv": face_uv},
        "north": {"texture": "#sign", "uv": back_uv},
        "up": {"texture": "#sign", "uv": edge_uv},
        "down": {"texture": "#sign", "uv": edge_uv},
        "west": {"texture": "#sign", "uv": edge_uv},
        "east": {"texture": "#sign", "uv": edge_uv},
    }
    body = {
        "textures": {
            "sign": TEXTURE_REF % kind["name"],
            "particle": TEXTURE_REF % kind["name"],
        },
        "elements": [{
            "from": [x0, y0, 0],
            "to": [x1, y1, THICKNESS],
            "faces": faces,
        }],
    }
    path = os.path.join(assets, MODEL_DIR, "sign_%s.json" % kind["name"])
    write_json(path, body)
    return path


def write_json(path, body):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(body, handle, indent="\t")
        handle.write("\n")


# ---------------------------------------------------------------------------
# Blockstates
# ---------------------------------------------------------------------------

def build_blockstates(assets, kinds):
    """The two files, and both have to exist.

    `signage.json` carries the `inventory` variant the item model resolves through;
    `signage_utility_sign.json` carries the block's own fifty-two, and is named by
    BlockUtilitySign.getCustomStateMapping.  A custom state mapping with no matching
    file is one of the two silent causes of a purple block in 1.12 and neither of them
    logs anything.

    The block file is written as Forge property submaps rather than fifty-two spelled-out
    keys: the loader takes the cartesian product, so `facing` supplies a rotation, `kind`
    supplies a model and `type` supplies nothing at all.  **Every listed property has to
    appear.**  A submap file that leaves one out does not resolve the variant string the
    state mapper hands it, and again nothing is logged.
    """
    item = {
        "forge_marker": 1,
        "defaults": {
            "transform": "forge:default-block",
            "model": MODEL_REF % kinds[1]["name"],
        },
        "variants": {
            "inventory,type=utility_sign": [{}],
            "type": {"utility_sign": {}},
        },
    }
    write_json(os.path.join(assets, "blockstates", "signage.json"), item)

    block = {
        "forge_marker": 1,
        "defaults": {"model": MODEL_REF % kinds[0]["name"]},
        "variants": {
            "facing": {facing: ({} if angle == 0 else {"y": angle})
                       for facing, angle in FACING_ROTATION.items()},
            "kind": {str(index): {"model": MODEL_REF % kind["name"]}
                     for index, kind in enumerate(kinds)},
            "type": {"utility_sign": {}},
        },
    }
    write_json(os.path.join(assets, "blockstates", "signage_utility_sign.json"), block)


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    repo = os.path.dirname(os.path.dirname(here))
    parser = argparse.ArgumentParser()
    parser.add_argument("--assets", default=os.path.join(
        repo, "src", "main", "resources", "assets", "immersiveengineering"))
    args = parser.parse_args()

    kinds = read_kinds(repo)
    print("%d kinds read from UtilitySignKind.java" % len(kinds))
    for kind in kinds:
        build_texture(args.assets, kind)
        build_model(args.assets, kind)
    build_blockstates(args.assets, kinds)
    print("wrote %d textures, %d models and 2 blockstates" % (len(kinds), len(kinds)))


if __name__ == "__main__":
    main()
