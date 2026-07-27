#!/usr/bin/env python3
"""
Regenerates the petroleum fluid textures (currently just crude oil).

Every fluid IE ships is the *same* animation wearing a different colour: creosote,
plant oil, biodiesel, propane and natural gas all have a byte-identical alpha channel
and differ only in a flat RGB tint. That is deliberate -- a fluid block's motion is
carried entirely by the alpha ramp, so reusing it is what makes a tank of crude read
as "the same stuff as the rest of IE" rather than as a bolt-on. This script therefore
takes an existing fluid's alpha channel as the motion reference and re-tints it,
which is also the only way to guarantee the frame size, frame count and loop length
match the shipped set exactly.

Keeping the art in a script rather than only as committed PNGs means a palette tweak
is a diff someone can read, and that the whole petroleum set can be re-rolled at once
as more refined products are added.

Usage:  python docs/tools/make_petroleum_textures.py [--out <dir>] [--source <name>]

Requires Pillow. Writes PNGs and their .mcmeta into
src/main/resources/assets/immersiveengineering/textures/blocks/fluid/.

Format note: "still" sheets are 16x512 (32 frames of 16x16), "flow" sheets are
32x1024 (32 frames of 32x32), RGBA, and the .mcmeta is an empty animation block --
Minecraft then infers square frames and one tick each.
"""

import argparse
import os

from PIL import Image

# ---------------------------------------------------------------------------
# Palette -- crude reads as near-black; the only colour in it is a faint brown-green
# sheen on the thin, light-catching parts of the surface. Ordered thin -> dense,
# matching how the shipped fluids darken as the alpha ramp climbs.
# ---------------------------------------------------------------------------
CRUDE_STILL_RAMP = (
    (42, 40, 25),  # sheen: the thinnest film, where the brown-green cast shows
    (30, 29, 18),
    (21, 20, 13),
    (13, 13, 9),  # deepest body, effectively black
)

# The shipped flow sheets are a single flat tone -- the diagonal motion is all in the
# alpha -- so crude gets one tone too, sitting between the two lightest still shades.
CRUDE_FLOW_TONE = (35, 33, 21)

# Alpha cut points between the ramp entries. Derived from the shipped sheets, whose
# four tints cluster around alpha 174 / 185 / 194 / 222.
RAMP_CUTS = (180, 190, 205)

MCMETA = '{\n  "animation": {\n  }\n}\n'

DEFAULT_OUT = os.path.join("src", "main", "resources", "assets", "immersiveengineering",
                           "textures", "blocks", "fluid")
DEFAULT_SOURCE = "biodiesel"


def load_motion(path):
    """Returns (size, alpha bytes) of the reference sheet."""
    with Image.open(path) as img:
        img = img.convert("RGBA")
        return img.size, img.getchannel("A").tobytes()


def shade_for(alpha, ramp):
    for i, cut in enumerate(RAMP_CUTS):
        if alpha < cut:
            return ramp[i]
    return ramp[-1]


def tint(size, alpha, ramp):
    """Re-colours a motion sheet, keeping its alpha untouched."""
    out = Image.new("RGBA", size)
    if len(ramp) == 1:
        flat = ramp[0]
        out.putdata([(flat[0], flat[1], flat[2], a) for a in alpha])
    else:
        out.putdata([shade_for(a, ramp)+(a,) for a in alpha])
    return out


def write(img, out_dir, name):
    path = os.path.join(out_dir, name+".png")
    img.save(path, "PNG", optimize=True)
    with open(path+".mcmeta", "w", newline="\n") as f:
        f.write(MCMETA)
    print("wrote", path, img.size)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", default=DEFAULT_OUT,
                        help="directory to write the PNGs into")
    parser.add_argument("--source", default=DEFAULT_SOURCE,
                        help="fluid whose alpha channel supplies the motion")
    args = parser.parse_args()

    os.makedirs(args.out, exist_ok=True)
    for kind, ramp in (("still", CRUDE_STILL_RAMP), ("flow", (CRUDE_FLOW_TONE,))):
        size, alpha = load_motion(os.path.join(args.out, "%s_%s.png"%(args.source, kind)))
        write(tint(size, alpha, ramp), args.out, "ie_crude_oil_"+kind)
    write_blocks(BLOCK_DIR)


# ---------------------------------------------------------------------------
# Block textures. Separate from the fluid sheets above: these are ordinary
# 16x16 stills, and they follow the same palette discipline as the grid set in
# make_grid_textures.py so the two features read as one mod.
# ---------------------------------------------------------------------------
OUTLINE = (28, 28, 31, 255)
STEEL_DARK = (58, 58, 64, 255)
STEEL = (78, 78, 85, 255)
STEEL_LIT = (99, 99, 107, 255)
BOLT = (150, 150, 158, 255)
RUST = (122, 74, 43, 255)
ORANGE = (233, 118, 43, 255)
VALVE_RED = (176, 60, 48, 255)

BLOCK_DIR = os.path.join("src", "main", "resources", "assets", "immersiveengineering",
                         "textures", "blocks")


def _blank(fill=STEEL):
    from PIL import Image as _I
    return _I.new("RGBA", (16, 16), fill)


def _rect(px, x0, y0, x1, y1, colour):
    for y in range(max(0, y0), min(16, y1+1)):
        for x in range(max(0, x0), min(16, x1+1)):
            px[x, y] = colour


def _dots(px, coords, colour):
    for x, y in coords:
        if 0 <= x < 16 and 0 <= y < 16:
            px[x, y] = colour


def oilfield_frame():
    """Lattice scaffold: the structural block both big rigs are built from."""
    img = _blank(STEEL_DARK)
    px = img.load()
    # Uprights and cross-bracing, so a wall of these reads as a truss.
    _rect(px, 1, 0, 3, 15, STEEL)
    _rect(px, 12, 0, 14, 15, STEEL)
    for i in range(16):
        x = 3+(i*9)//16
        px[min(14, 3+i//2), i] = STEEL_LIT
        px[max(1, 12-i//2), i] = STEEL_LIT
    _rect(px, 0, 0, 15, 0, OUTLINE)
    _rect(px, 0, 15, 15, 15, OUTLINE)
    _dots(px, [(2, 2), (13, 2), (2, 13), (13, 13)], BOLT)
    return img


def wellhead():
    """The valve stack: flanged body, hand wheel, an outlet spool."""
    img = _blank(STEEL_DARK)
    px = img.load()
    _rect(px, 5, 3, 10, 15, STEEL)
    _rect(px, 5, 3, 10, 3, STEEL_LIT)
    # Flanges.
    for y in (5, 9, 13):
        _rect(px, 4, y, 11, y, STEEL_LIT)
        _rect(px, 4, y+1, 11, y+1, OUTLINE)
    # Hand wheel on top.
    _rect(px, 6, 1, 9, 1, VALVE_RED)
    px[7, 2] = VALVE_RED
    px[8, 2] = VALVE_RED
    # Outlet spool to one side.
    _rect(px, 11, 10, 14, 12, STEEL)
    _rect(px, 14, 10, 14, 12, OUTLINE)
    _dots(px, [(5, 7), (10, 7), (5, 11), (10, 11)], BOLT)
    return img


def derrick_side():
    """Cladding for the derrick's lower housing."""
    img = _blank(STEEL)
    px = img.load()
    _rect(px, 0, 0, 15, 15, STEEL)
    for y in range(2, 15, 4):
        _rect(px, 1, y, 14, y, STEEL_DARK)
        _rect(px, 1, y+1, 14, y+1, OUTLINE)
    _rect(px, 0, 0, 0, 15, OUTLINE)
    _rect(px, 15, 0, 15, 15, OUTLINE)
    _dots(px, [(3, 1), (12, 1), (3, 14), (12, 14)], BOLT)
    return img


def pumpjack_body():
    """The pumpjack's painted machinery housing -- the one warm accent."""
    img = _blank(STEEL)
    px = img.load()
    _rect(px, 0, 0, 15, 15, STEEL)
    _rect(px, 2, 4, 13, 11, ORANGE)
    _rect(px, 2, 4, 13, 4, (255, 150, 70, 255))
    _rect(px, 2, 11, 13, 11, RUST)
    _rect(px, 0, 0, 15, 0, OUTLINE)
    _rect(px, 0, 15, 15, 15, OUTLINE)
    _dots(px, [(1, 2), (14, 2), (1, 13), (14, 13)], BOLT)
    return img


BLOCK_TEXTURES = {
    "petroleum_oilfield_frame": oilfield_frame,
    "petroleum_wellhead": wellhead,
    "petroleum_derrick_side": derrick_side,
    "petroleum_pumpjack_body": pumpjack_body,
}


def write_blocks(out_dir):
    os.makedirs(out_dir, exist_ok=True)
    for name, builder in sorted(BLOCK_TEXTURES.items()):
        path = os.path.join(out_dir, name+".png")
        builder().save(path, "PNG", optimize=True)
        print("wrote", path)


if __name__ == "__main__":
    main()
