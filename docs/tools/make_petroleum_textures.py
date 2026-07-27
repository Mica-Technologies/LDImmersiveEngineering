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


if __name__ == "__main__":
    main()
