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
    write_fractions(args.out, args.source)
    write_blocks(BLOCK_DIR)
    write_roads(BLOCK_DIR)
    write_extra(BLOCK_DIR)


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


# ---------------------------------------------------------------------------
# The distillation cuts. Each is the same motion (see load_motion) under a
# different tint, so the whole family reads as one set of fluids. Colours run
# from pale straw at the top of the column to near-black at the bottom, which
# is both roughly true and the fastest way to tell them apart in a tank.
# ---------------------------------------------------------------------------
FRACTION_RAMPS = {
    # name: (still ramp light->dark, flow tone)
    "ie_naphtha": (((236, 226, 178), (214, 200, 142), (186, 170, 108)), (206, 192, 136)),
    "ie_gasoline": (((238, 206, 120), (222, 176, 74), (188, 140, 48)), (214, 172, 72)),
    "ie_diesel": (((214, 176, 116), (184, 140, 80), (146, 104, 52)), (178, 136, 78)),
    "ie_heavy_fuel_oil": (((104, 92, 80), (74, 64, 56), (48, 41, 36)), (70, 61, 53)),
    "ie_lubricant": (((198, 166, 96), (168, 134, 66), (132, 100, 44)), (162, 130, 64)),
    "ie_bitumen": (((72, 68, 66), (48, 45, 44), (28, 26, 26)), (44, 42, 41)),
    "ie_asphalt": (((88, 84, 82), (62, 59, 58), (40, 38, 38)), (58, 55, 54)),
    "ie_sour_gas": (((198, 206, 150), (168, 178, 112), (132, 142, 78)), (162, 172, 108)),
    # Steam is the one fluid here that is not a hydrocarbon and should not read as one:
    # near-white and barely tinted, so a steam line is obviously not a fuel line at a glance.
    "ie_steam": (((242, 244, 246), (216, 220, 226), (188, 194, 202)), (224, 228, 234)),
}


def write_fractions(out_dir, source):
    for name, (still_ramp, flow_tone) in sorted(FRACTION_RAMPS.items()):
        for kind, ramp in (("still", still_ramp), ("flow", (flow_tone,))):
            size, alpha = load_motion(os.path.join(out_dir, "%s_%s.png"%(source, kind)))
            write(tint(size, alpha, ramp), out_dir, name+"_"+kind)


# ---------------------------------------------------------------------------
# Laid asphalt. Aggregate speckle over a dark binder, which is what asphalt
# actually looks like and reads clearly at a distance as a road surface.
# ---------------------------------------------------------------------------
ASPHALT_BASE = (46, 45, 47, 255)
ASPHALT_GRIT = (66, 65, 68, 255)
ASPHALT_GRIT2 = (84, 83, 87, 255)
ASPHALT_SEAM = (34, 33, 35, 255)
ROAD_PAINT = (214, 198, 96, 255)


def _speckle(px, seed):
    """Deterministic aggregate scatter -- same input, same road, every run."""
    state = seed
    for y in range(16):
        for x in range(16):
            state = (state*1103515245+12345) & 0x7FFFFFFF
            roll = state % 100
            px[x, y] = (ASPHALT_GRIT2 if roll < 6
                        else ASPHALT_GRIT if roll < 22
                        else ASPHALT_BASE)


def asphalt():
    img = _blank(ASPHALT_BASE)
    _speckle(img.load(), 20260727)
    return img


def asphalt_tile():
    img = _blank(ASPHALT_BASE)
    px = img.load()
    _speckle(px, 991)
    # A single seam cross, so tiles read as laid rather than poured.
    _rect(px, 0, 7, 15, 8, ASPHALT_SEAM)
    _rect(px, 7, 0, 8, 15, ASPHALT_SEAM)
    return img


def asphalt_marked():
    img = _blank(ASPHALT_BASE)
    px = img.load()
    _speckle(px, 5150)
    # Dashed centre line down the block, so a run of them reads as a lane.
    for y in range(1, 15):
        if y % 6 != 0:
            _rect(px, 7, y, 8, y, ROAD_PAINT)
    return img


ROAD_TEXTURES = {
    "petroleum_asphalt": asphalt,
    "petroleum_asphalt_tile": asphalt_tile,
    "petroleum_asphalt_marked": asphalt_marked,
}


def write_roads(out_dir):
    os.makedirs(out_dir, exist_ok=True)
    for name, builder in sorted(ROAD_TEXTURES.items()):
        path = os.path.join(out_dir, name+".png")
        builder().save(path, "PNG", optimize=True)
        print("wrote", path)


def flare_stack():
    """Sooted stack: a rolled steel tube with a scorched tip band."""
    img = _blank(STEEL_DARK)
    px = img.load()
    _rect(px, 5, 0, 10, 15, STEEL)
    _rect(px, 5, 0, 5, 15, STEEL_LIT)
    _rect(px, 10, 0, 10, 15, OUTLINE)
    for y in range(1, 15, 4):
        _rect(px, 5, y, 10, y, STEEL_DARK)
    # Soot creeping up from the tip.
    for y in range(0, 5):
        _rect(px, 5, y, 10, y, (36, 33, 32, 255))
    return img


def vessel():
    """Pressure vessel plate: riveted seams round a cylinder."""
    img = _blank(STEEL)
    px = img.load()
    for x in range(16):
        shade = STEEL_LIT if x < 4 else STEEL if x < 12 else STEEL_DARK
        _rect(px, x, 0, x, 15, shade)
    for y in (0, 7, 15):
        _rect(px, 0, y, 15, y, OUTLINE)
    _dots(px, [(2, 3), (8, 3), (13, 3), (2, 11), (8, 11), (13, 11)], BOLT)
    return img


def turbine_body():
    """Nacelle cladding with a cooling louvre band."""
    img = _blank(STEEL)
    px = img.load()
    _rect(px, 0, 0, 15, 15, STEEL)
    _rect(px, 0, 5, 15, 10, STEEL_DARK)
    for x in range(1, 15, 2):
        _rect(px, x, 6, x, 9, STEEL_LIT)
    _rect(px, 0, 0, 15, 0, OUTLINE)
    _rect(px, 0, 15, 15, 15, OUTLINE)
    _dots(px, [(2, 2), (13, 2), (2, 13), (13, 13)], BOLT)
    return img


def manifold():
    """Grease distribution block: a valve body with radiating feed lines."""
    img = _blank(STEEL_DARK)
    px = img.load()
    _rect(px, 2, 2, 13, 13, STEEL)
    _rect(px, 2, 2, 13, 2, STEEL_LIT)
    # Feed lines out to each face.
    _rect(px, 7, 0, 8, 15, STEEL_DARK)
    _rect(px, 0, 7, 15, 8, STEEL_DARK)
    # Valve body.
    _rect(px, 5, 5, 10, 10, ORANGE)
    _rect(px, 6, 6, 9, 9, RUST)
    _dots(px, [(3, 3), (12, 3), (3, 12), (12, 12)], BOLT)
    return img


BOTTLE_WHITE = (222, 222, 218, 255)
BOTTLE_SHADE = (188, 188, 184, 255)
BOTTLE_DARK = (150, 150, 148, 255)
BRASS = (176, 140, 68, 255)


def propane_cylinder():
    """The barbecue bottle: white steel, a brass valve, a rolled foot ring."""
    img = _blank(BOTTLE_SHADE)
    px = img.load()
    # Cylindrical shading, lit from the left.
    for x in range(16):
        shade = BOTTLE_WHITE if x < 6 else BOTTLE_SHADE if x < 12 else BOTTLE_DARK
        _rect(px, x, 0, x, 15, shade)
    # Rolled foot and shoulder bands.
    _rect(px, 0, 14, 15, 15, BOTTLE_DARK)
    _rect(px, 0, 0, 15, 1, BOTTLE_DARK)
    # Valve fitting.
    _rect(px, 6, 2, 9, 4, BRASS)
    _dots(px, [(7, 3), (8, 3)], (206, 172, 96, 255))
    return img


FIREBOX = (94, 46, 30, 255)
EMBER = (206, 96, 38, 255)
PALE = (142, 141, 136, 255)
PALE_DARK = (112, 111, 107, 255)
GLASS = (96, 124, 138, 255)


def boiler_wall():
    """Firebox casing: refractory panel behind a steel frame, glowing at the seams."""
    img = _blank(STEEL_DARK)
    px = img.load()
    _rect(px, 2, 2, 13, 13, FIREBOX)
    # Seams between the refractory blocks, lit from behind. A boiler that does not look hot
    # is indistinguishable from a tank, and this is the one machine that is all furnace.
    for y in (5, 9):
        _rect(px, 2, y, 13, y, EMBER)
    _rect(px, 7, 2, 7, 13, EMBER)
    _rect(px, 0, 0, 15, 0, OUTLINE)
    _rect(px, 0, 15, 15, 15, OUTLINE)
    _rect(px, 0, 0, 0, 15, OUTLINE)
    _rect(px, 15, 0, 15, 15, OUTLINE)
    _dots(px, [(1, 1), (14, 1), (1, 14), (14, 14)], BOLT)
    return img


def hrsg_wall():
    """Finned tube bank: the whole machine is surface area, so the texture is stripes."""
    img = _blank(STEEL)
    px = img.load()
    for x in range(0, 16, 3):
        _rect(px, x, 1, x, 14, STEEL_DARK)
        _rect(px, x+1, 1, x+1, 14, STEEL_LIT)
    _rect(px, 0, 0, 15, 0, OUTLINE)
    _rect(px, 0, 15, 15, 15, OUTLINE)
    _rect(px, 0, 7, 15, 7, STEEL_DARK)
    _dots(px, [(3, 7), (11, 7)], BOLT)
    return img


def hall_wall():
    """Generator hall cladding: pale industrial panel with a run of clerestory glazing."""
    img = _blank(PALE)
    px = img.load()
    _rect(px, 0, 0, 15, 4, PALE_DARK)
    # The glazing band is what makes this read as a building you walk into rather than a
    # machine you stand next to, which is the whole point of the Steam Turbine Hall.
    _rect(px, 1, 1, 14, 3, GLASS)
    for x in range(4, 15, 4):
        _rect(px, x, 1, x, 3, PALE_DARK)
    for x in range(0, 16, 4):
        _rect(px, x, 5, x, 15, PALE_DARK)
    _rect(px, 0, 0, 15, 0, OUTLINE)
    _rect(px, 0, 15, 15, 15, OUTLINE)
    return img


def engine_block():
    """Cylinder heads in a row: an engine bank read end-on."""
    img = _blank(STEEL_DARK)
    px = img.load()
    for x in (2, 8):
        _rect(px, x, 3, x+5, 12, STEEL)
        _rect(px, x, 3, x+5, 3, STEEL_LIT)
        _rect(px, x+1, 5, x+4, 5, STEEL_DARK)
        _rect(px, x+1, 8, x+4, 8, STEEL_DARK)
        _dots(px, [(x, 3), (x+5, 3), (x, 12), (x+5, 12)], BOLT)
    _rect(px, 0, 0, 15, 0, OUTLINE)
    _rect(px, 0, 15, 15, 15, OUTLINE)
    _rect(px, 0, 13, 15, 13, RUST)
    return img

EXTRA_BLOCKS = {
    "petroleum_flare_stack": flare_stack,
    "petroleum_vessel": vessel,
    "petroleum_turbine_body": turbine_body,
    "petroleum_manifold": manifold,
    "petroleum_propane_cylinder": propane_cylinder,
    "petroleum_boiler_wall": boiler_wall,
    "petroleum_hrsg_wall": hrsg_wall,
    "petroleum_hall_wall": hall_wall,
    "petroleum_engine_block": engine_block,
}


def write_extra(out_dir):
    for name, builder in sorted(EXTRA_BLOCKS.items()):
        path = os.path.join(out_dir, name+".png")
        builder().save(path, "PNG", optimize=True)
        print("wrote", path)


if __name__ == "__main__":
    main()
