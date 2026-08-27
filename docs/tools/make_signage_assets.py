#!/usr/bin/env python3
"""
Regenerates the utility pole signage block's textures, models and blockstates.

Fifteen tags, every one of them a sign that exists on a real pole -- LADWP's and SCE's.
A tag is a flat plate bolted to whatever holds the wires up, so each is one thin
axis-aligned box wearing one 16x16 sprite, and the only thing drawn per frame is the
lettering (see TileRenderUtilitySign).  Sixty variants: fifteen plates times four
horizontal facings, and the facings are y-rotations of one model rather than four files.

**The geometry comes out of UtilitySignKind.java rather than being restated here.**  A
plate whose model and whose selection box disagree is obvious in a screenshot and invisible
in code, and the renderer lays text out against the same numbers.

Usage:  python docs/tools/make_signage_assets.py [--assets <assets dir>]

Requires Pillow.
"""

import argparse
import json
import math
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
# The transmission tower identifiers are a paler, creamier yellow than the LADWP distribution
# strips -- that is what the reference photographs of them show, and telling a tower tag from a
# pole tag at a glance is worth as much here as telling a red strip from a yellow one.
PALE_YELLOW = (236, 214, 118, 255)
PALE_YELLOW_LIT = (248, 232, 164, 255)
# The number stencilled onto a tower leg is painted on amber primer rather than on a plate.
AMBER = (232, 160, 74, 255)
AMBER_LIT = (246, 186, 108, 255)
WHITE = (238, 238, 236, 255)
WHITE_LIT = (250, 250, 248, 255)
SILVER = (176, 180, 184, 255)
SILVER_LIT = (198, 202, 206, 255)
ORANGE = (216, 118, 32, 255)
ORANGE_LIT = (234, 142, 56, 255)
CLEAR = (0, 0, 0, 0)
# The bolt the round inspection tag hangs on -- a fastening, not paint, so it gets its own two
# tones rather than borrowing SILVER's: dark enough to read against every plate colour a nail
# might sit on, with one lighter pixel for the catch-light a torch would actually find on it.
BOLT = (58, 58, 62, 255)
BOLT_LIT = (150, 154, 158, 255)

# SILVER_VERTICAL's own face: a bright edge shading through the mid tone -- SILVER itself -- to
# a darker far edge, which is the small number of tones a sixteen-pixel sprite can carry and
# still read as brushed metal rather than as a flat grey rectangle at the distance a pole is
# read from.
METAL_TONES = ((150, 154, 158, 255), SILVER, (120, 124, 128, 255))
METAL_SPECULAR = (198, 202, 206, 255)
METAL_GRAIN = 10

MODEL_DIR = os.path.join("models", "block", "signage")
TEXTURE_REF = "immersiveengineering:blocks/sign_%s"
MODEL_REF = "immersiveengineering:signage/sign_%s"

FACING_ROTATION = {"north": 0, "east": 90, "south": 180, "west": 270}


def read_kinds(repo):
    """The kinds, in ordinal order, straight out of the enum.

    Parsed rather than duplicated: the ordinal is what a sign saves and what the
    blockstate keys on, so a list here that drifted out of order would repaint every
    sign in a world rather than fail.
    """
    path = os.path.join(repo, "src", "main", "java", "blusunrize", "immersiveengineering",
                        "common", "blocks", "signage", "UtilitySignKind.java")
    with open(path, encoding="utf-8") as handle:
        source = handle.read()
    body = source[source.index("public enum UtilitySignKind"):source.index("public static final UtilitySignKind[]")]
    pattern = re.compile(r"^\t([A-Z_]+)\((\d+), (\d+), (\d+), 0x([0-9A-Fa-f]+), "
                         r"SignTextFlow\.([A-Z_]+), SignShape\.([A-Z]+), SignDivider\.([A-Z_]+)\)",
                         re.MULTILINE)
    kinds = []
    for match in pattern.finditer(body):
        kinds.append({
            "name": match.group(1).lower(),
            "width": int(match.group(2)),
            "height": int(match.group(3)),
            "lines": int(match.group(4)),
            "flow": match.group(6),
            "shape": match.group(7),
            "divider": match.group(8),
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


def metal_strip(image, draw, rect):
    """SILVER_VERTICAL's own face, in place of the flat fill every other strip gets.

    A brushed, anodised metal plate is not one colour: it is a gradient across its *width*
    from a bright edge, through the mid tone, to a darker far edge, with a specular band
    where an overhead light catches one strip of it and a faint lengthwise grain running
    the length of the plate.  Three tones and one highlight row is all a sixteen-pixel
    sprite can actually carry -- more than that is indistinguishable from noise at the
    distance a pole is read from, which is what a flatter, more detailed first attempt at
    this came back looking like.
    """
    x0, y0, x1, y1 = rect
    width = x1-x0
    for i in range(x0, x1):
        band = min(len(METAL_TONES)-1, (i-x0)*len(METAL_TONES)//width)
        tone = METAL_TONES[band]
        for j in range(y0, y1):
            image.putpixel((i, j), tone)
    # The specular band: a third of the way down, the way a rolled sheet catches an overhead
    # light in one strip rather than evenly across its face.
    spec_row = y0+(y1-y0)//3
    for i in range(x0+1, x1-1):
        image.putpixel((i, spec_row), METAL_SPECULAR)
    # The grain: every third row nudged a shade darker, which at this resolution reads as
    # brushing rather than as banding -- a grain on every row was tried and just looked like
    # a duller version of the same three flat tones.
    for j in range(y0, y1):
        if (j-y0) % 3 == 1:
            for i in range(x0+1, x1-1):
                r, g, b, a = image.getpixel((i, j))
                image.putpixel((i, j), (max(0, r-METAL_GRAIN), max(0, g-METAL_GRAIN),
                                         max(0, b-METAL_GRAIN), a))
    draw.rectangle([x0, y0, x1-1, y1-1], outline=SOFT_EDGE)


def _paint_filled_outline(image, rect, inside, fill, edge):
    """Paint every pixel of `rect` that `inside` says is part of the shape, with the ring of
    inside pixels that border an outside one getting `edge` instead of `fill`.

    Shared by the ellipse and the diamond, which were the two shapes `draw.ellipse` and
    `draw.polygon` rendered asymmetrically on a plate whose true centre is a half pixel: each
    rounds a centre like that differently in each quadrant, and a playtester could see it --
    a lopsided oval, a diamond whose four corners did not match.  Testing a pixel's own centre
    against the shape's equation, about the plate's true centre rather than a rounded one, is
    symmetric by construction and does not care how anything else rounds.  `edge=None` skips
    the ring and fills the whole shape flat, for the one diamond that has no outline at all.
    """
    x0, y0, x1, y1 = rect
    mask = {}
    for j in range(y0, y1):
        for i in range(x0, x1):
            mask[(i, j)] = inside(i, j)
    for (i, j), is_inside in mask.items():
        if not is_inside:
            continue
        is_edge = edge is not None and any(
            not mask.get((i+di, j+dj), False)
            for di, dj in ((1, 0), (-1, 0), (0, 1), (0, -1)))
        image.putpixel((i, j), edge if is_edge else fill)


def _ellipse_inside(rect):
    """An `inside(i, j)` test for `rect`, true for a pixel whose own centre falls within the
    ellipse inscribed in it -- the oval and the round tag are the same shape at two ratios.
    """
    x0, y0, x1, y1 = rect
    cx, cy = x0+(x1-x0)/2.0, y0+(y1-y0)/2.0
    rx, ry = (x1-x0)/2.0, (y1-y0)/2.0

    def inside(i, j):
        dx, dy = (i+0.5-cx)/rx, (j+0.5-cy)/ry
        return dx*dx+dy*dy <= 1.0

    return inside


def _diamond_inside(rect):
    """An `inside(i, j)` test for `rect`, true for a pixel whose own centre is within `radius`
    Manhattan distance of the plate's true centre -- a diamond stood on its point is exactly
    the set of points a taxicab could reach, which is symmetric about both axes and, because
    every diamond this set draws is square, about a ninety degree turn as well.
    """
    x0, y0, x1, y1 = rect
    cx, cy = x0+(x1-x0)/2.0, y0+(y1-y0)/2.0
    radius = (x1-x0)/2.0

    def inside(i, j):
        return abs(i+0.5-cx)+abs(j+0.5-cy) <= radius

    return inside


def oval(image, draw, rect, fill, lit, edge=EDGE):
    """The painted white oval a series-wired street light wears."""
    _paint_filled_outline(image, rect, _ellipse_inside(rect), fill, edge)
    assert_mirror_symmetric(image, rect, "oval_fraction")
    x0, y0, x1, y1 = rect
    draw.arc([x0+1, y0+1, x1-2, y1-2], 170, 350, fill=lit)


def disc(image, draw, rect, fill, lit, edge=SOFT_EDGE):
    """The round bolt-on inspection tag."""
    _paint_filled_outline(image, rect, _ellipse_inside(rect), fill, edge)
    assert_mirror_symmetric(image, rect, "inspection_round")
    x0, y0, x1, y1 = rect
    draw.arc([x0+1, y0+1, x1-2, y1-2], 170, 350, fill=lit)


def diamond(image, rect, fill, outline=None):
    """A diamond on its point, which is how a tower number is hung.

    The plain LADWP tower diamond has no border at all -- the number is painted straight
    onto the plate -- so the outline is optional rather than assumed.
    """
    _paint_filled_outline(image, rect, _diamond_inside(rect), fill, outline)


def paint_x(image, rect, colour, margin):
    """The cross the line crossing diamond is named for, drawn pixel by pixel about the
    plate's true centre rather than with `draw.line`.

    A diagonal line is exactly the case `draw.line` cannot be trusted to round the same way
    twice: nothing guarantees the two arms come out the same length, which on a twelve-pixel
    diamond is visible as a cross that does not meet its own corners.  Testing `|dx| - |dy|`
    is symmetric under swapping or negating either axis, so all four arms are identical by
    construction.  `margin` is how far short of the plate's own point each arm stops, which is
    what keeps this reading as a mark painted on the diamond rather than as the diamond being
    cut into quarters.
    """
    x0, y0, x1, y1 = rect
    cx, cy = x0+(x1-x0)/2.0, y0+(y1-y0)/2.0
    reach = (x1-x0)/2.0-margin
    for j in range(y0, y1):
        for i in range(x0, x1):
            dx, dy = i+0.5-cx, j+0.5-cy
            if abs(dx) > reach or abs(dy) > reach:
                continue
            if abs(abs(dx)-abs(dy)) < 0.75:
                image.putpixel((i, j), colour)


def assert_mirror_symmetric(image, rect, name):
    """Confirms a shape is exactly mirror-symmetric left-right and top-bottom about the
    plate's own centre.

    This is the property `draw.ellipse` did not reliably have on an even-sided plate -- a
    playtester could see the oval come out lopsided against a photograph of a real one -- and
    it is checked here, at the point the shape is painted, rather than trusted to stay true
    from the arithmetic that is meant to produce it.
    """
    x0, y0, x1, y1 = rect
    for j in range(y0, y1):
        for i in range(x0, x1):
            mirror_x, mirror_y = x0+(x1-1-i), y0+(y1-1-j)
            if image.getpixel((i, j)) != image.getpixel((mirror_x, j)):
                raise SystemExit("%s is not left-right symmetric at (%d, %d)" % (name, i, j))
            if image.getpixel((i, j)) != image.getpixel((i, mirror_y)):
                raise SystemExit("%s is not top-bottom symmetric at (%d, %d)" % (name, i, j))


def assert_fourfold_symmetric(image, rect, name):
    """Confirms a shape is unchanged by a ninety degree turn about the plate's own centre.

    Mirror symmetry alone would pass a diamond drawn taller than it is wide, or a cross whose
    two arms were not actually the same length; this is the stronger check that a rhombus is
    really square, which is what the LADWP tower diamond and the line crossing diamond both
    are meant to be.
    """
    x0, y0, x1, y1 = rect
    w, h = x1-x0, y1-y0
    if w != h:
        raise SystemExit("%s is not square, so four-fold symmetry does not apply" % name)
    for j in range(y0, y1):
        for i in range(x0, x1):
            li, lj = i-x0, j-y0
            here = image.getpixel((i, j))
            for ri, rj in ((w-1-lj, li), (w-1-li, w-1-lj), (lj, w-1-li)):
                there = image.getpixel((x0+ri, y0+rj))
                if here != there:
                    raise SystemExit("%s is not four-fold symmetric between (%d, %d) and "
                                     "(%d, %d)" % (name, i, j, x0+ri, y0+rj))


# What SignDivider's Java table says, mirrored here so the generator can switch on it without
# a second copy of the fractions and thicknesses living in the sprite instead of in the enum
# they are meant to come from.  See SignDivider.java for what each one actually is.
DIVIDER_ARITHMETIC = {"FRACTION_BAR": (0.5, 2), "NAIL": (0.5, 2)}


def divider_row(rect, fraction, thickness):
    """The row a divider's first pixel sits on, worked out exactly as SignLayout.dividerRow
    does it in Java: floor(depth*fraction - thickness/2 + 0.5).

    **The half-thickness is what makes a centred divider centred.**  The fraction says where
    the middle of the rule or the bar or the bolt goes, and the band the lettering is kept out
    of is half its thickness either side of that.  Taking the fraction as the divider's *top*
    edge instead cost the line below it the divider's whole thickness, which on a fraction bar
    is a series number printed half again the size of the pole number under it.

    Both sides round the same way for the same reason they share the fraction at all, and
    SignageTest reads this row back off the finished sprite rather than trusting either.
    """
    x0, y0, x1, y1 = rect
    depth = y1-y0
    return y0+int(math.floor(depth*fraction-thickness/2+0.5))


def paint_bolt(image, rect, row, thickness):
    """The bolt head the round inspection tag is actually hung by, painted where it really is
    -- through the plate's own centre, in the band SignDivider.NAIL keeps clear of lettering.

    Two pixels of depth is not enough for a full lit arc the way the oval and the disc get
    one, so the fastening reads as one instead by a single lighter pixel at the corner a torch
    would catch first, rather than by shading all the way round it.
    """
    x0, y0, x1, y1 = rect
    cx = x0+(x1-x0)//2
    bolt_rect = (cx-2, row, cx+2, row+thickness)
    _paint_filled_outline(image, bolt_rect, _ellipse_inside(bolt_rect), BOLT, None)
    image.putpixel((bolt_rect[0], bolt_rect[1]), BOLT_LIT)


def paint_divider(draw, image, rect, kind):
    """What is printed across a plate's middle, switched on the SignDivider its kind declares.

    One token shared with the Java layout, rather than a table kept twice: SignLayout fits
    the lettering either side of whatever this paints, off the same fraction and the same
    thickness, so a divider that moved here without SignDivider.java changing to match would
    put a line's letters on top of it with nothing anywhere saying so.
    """
    divider = kind["divider"]
    if divider == "NONE":
        return
    fraction, thickness = DIVIDER_ARITHMETIC[divider]
    row = divider_row(rect, fraction, thickness)
    x0, y0, x1, y1 = rect
    if divider == "FRACTION_BAR":
        # The bar of the fraction a series-wired light wears, inset two pixels from each side
        # so it stops short of the oval's own painted outline rather than touching it -- a bar
        # that ran into the outline read as part of the border instead of as a divider.  Two
        # rows deep, because an even plate cannot centre an odd bar; see SignDivider.
        draw.rectangle([x0+2, row, x1-3, row+thickness-1], fill=EDGE)
    elif divider == "NAIL":
        paint_bolt(image, rect, row, thickness)
    else:
        raise SystemExit("no artwork for divider %s" % divider)


# What each routine above cuts a plate to.  UtilitySignKind declares the same thing, and
# SignLayout fits the lettering to what it declares -- so a plate drawn as an oval but
# declared a rectangle gets its text laid out to a rectangle it has not got, which is
# exactly how the numbers came to be hanging off the paint the first time round.
SHAPE_OF = {"strip": "RECT", "oval": "ELLIPSE", "disc": "ELLIPSE", "diamond": "DIAMOND"}


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
    routine = "strip"
    if name == "parallel_generation":
        strip(draw, rect, RED, RED_LIT)
    elif name in ("yellow_vertical", "yellow_horizontal", "tower_horizontal"):
        strip(draw, rect, YELLOW, YELLOW_LIT)
    elif name == "white_vertical":
        strip(draw, rect, WHITE, WHITE_LIT)
    elif name == "silver_vertical":
        metal_strip(image, draw, rect)
    elif name in ("orange_horizontal", "orange_vertical"):
        strip(draw, rect, ORANGE, ORANGE_LIT)
    elif name == "oval_fraction":
        oval(image, draw, rect, WHITE, WHITE_LIT)
        routine = "oval"
    elif name == "inspection_round":
        disc(image, draw, rect, SILVER, SILVER_LIT)
        routine = "disc"
    elif name == "tower_diamond":
        diamond(image, rect, YELLOW)
        assert_fourfold_symmetric(image, rect, name)
        routine = "diamond"
    elif name == "line_crossing_diamond":
        diamond(image, rect, YELLOW, outline=EDGE)
        # The cross this sign is named for, short of the points so it reads as a marking on
        # the plate rather than as the plate being cut in four -- see paint_x.
        paint_x(image, rect, EDGE, margin=3)
        assert_fourfold_symmetric(image, rect, name)
        routine = "diamond"
    elif name in ("tower_vertical", "tower_short"):
        strip(draw, rect, PALE_YELLOW, PALE_YELLOW_LIT)
    elif name == "tower_number":
        strip(draw, rect, AMBER, AMBER_LIT)
    else:
        raise SystemExit("no artwork for sign kind %s" % name)
    if SHAPE_OF[routine] != kind["shape"]:
        raise SystemExit("%s is drawn as a %s but declares SignShape.%s -- one of the two is "
                         "wrong, and what comes out is a sign whose text is fitted to a plate "
                         "it has not got" % (name, SHAPE_OF[routine], kind["shape"]))
    # Whatever is printed across this plate's middle -- the tower rule, the fraction bar, the
    # nail -- goes on last, after the plate's own base artwork, and is switched on the divider
    # its kind declares rather than being tied to one particular shape's branch above.
    paint_divider(draw, image, rect, kind)
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
