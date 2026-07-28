#!/usr/bin/env python3
"""
Regenerates the virtual grid's block textures.

The grid hardware is meant to read as ordinary utility gear in IE's style: flat
sheet-metal greys, a dark outline, one orange accent, and a status lamp. Keeping the
art in a script rather than only as committed PNGs means the whole set stays
consistent -- change a palette entry here and every box, panel and console face moves
with it -- and that a tweak is a diff someone can read.

Usage:  python docs/tools/make_grid_textures.py [--out <assets dir>]

Requires Pillow. Writes 16x16 PNGs into
src/main/resources/assets/immersiveengineering/textures/blocks/.

Geometry note: the utility box model (models/block/grid/utility_box.json) spans
[3,2] to [13,14] in block pixels, so the *front* face samples only that region of the
texture. Everything meaningful on a front texture therefore lives inside x 3..12,
y 2..13; the border outside it is never seen but is filled anyway so the item model
and any future re-UV stay sane.
"""

import argparse
import json
import os

from PIL import Image

# ---------------------------------------------------------------------------
# Palette -- IE leans on desaturated gunmetal with a single warm accent.
# ---------------------------------------------------------------------------
OUTLINE = (28, 28, 31, 255)
STEEL_DARK = (58, 58, 64, 255)
STEEL = (78, 78, 85, 255)
STEEL_LIT = (99, 99, 107, 255)
STEEL_HILIGHT = (124, 124, 132, 255)
BOLT = (150, 150, 158, 255)
BOLT_SHADE = (46, 46, 51, 255)

ORANGE = (233, 118, 43, 255)
ORANGE_DARK = (176, 84, 28, 255)
REDSTONE = (196, 48, 48, 255)
REDSTONE_DARK = (128, 28, 28, 255)

LAMP_OFF = (62, 70, 62, 255)
LAMP_GREEN = (108, 214, 108, 255)

GLASS_DARK = (26, 32, 34, 255)
GLASS = (38, 52, 56, 255)
SCANLINE = (72, 132, 128, 255)
SCREEN_GLOW = (126, 214, 200, 255)

SIZE = 16

# Ticks per frame for every animated sprite this file writes. MC's animation
# format has no independent "speed" knob -- frametime times frame count is the
# whole loop -- so this one constant is what stands between "a console that
# looks alive" and "a console that strobes." Four frames at 8 ticks is a 1.6s
# loop, slow enough to read as idle machinery from across a room.
ANIMATION_FRAMETIME = 8


def new_image(fill=STEEL):
    return Image.new("RGBA", (SIZE, SIZE), fill)


def rect(px, x0, y0, x1, y1, colour):
    """Filled rectangle, inclusive of both corners."""
    for y in range(max(0, y0), min(SIZE, y1 + 1)):
        for x in range(max(0, x0), min(SIZE, x1 + 1)):
            px[x, y] = colour


def outline_rect(px, x0, y0, x1, y1, colour):
    for x in range(x0, x1 + 1):
        px[x, y0] = colour
        px[x, y1] = colour
    for y in range(y0, y1 + 1):
        px[x0, y] = colour
        px[x1, y] = colour


def dots(px, coords, colour):
    for x, y in coords:
        if 0 <= x < SIZE and 0 <= y < SIZE:
            px[x, y] = colour


def brushed_metal(px, x0, y0, x1, y1, base=STEEL, streak=STEEL_DARK):
    """A faint horizontal grain, so large flats do not read as dead colour."""
    rect(px, x0, y0, x1, y1, base)
    for y in range(y0, y1 + 1):
        if (y - y0) % 3 == 2:
            rect(px, x0, y, x1, y, streak)


# ---------------------------------------------------------------------------
# Shared front-panel chrome
# ---------------------------------------------------------------------------
def panel_base():
    """The body every utility box front shares: bezel, latch, bolts, lamp."""
    img = new_image(STEEL_DARK)
    px = img.load()

    # Body and bezel. The visible window is x 3..12, y 2..13.
    brushed_metal(px, 3, 2, 12, 13, STEEL, STEEL_DARK)
    outline_rect(px, 3, 2, 12, 13, OUTLINE)
    # Inner recess, one pixel in, catching light from the top left.
    rect(px, 4, 3, 11, 3, STEEL_HILIGHT)
    rect(px, 4, 4, 4, 12, STEEL_LIT)
    rect(px, 5, 12, 11, 12, STEEL_DARK)
    rect(px, 11, 4, 11, 12, STEEL_DARK)

    # Corner bolts.
    dots(px, [(5, 4), (10, 4), (5, 11), (10, 11)], BOLT)
    dots(px, [(5, 5), (10, 5), (5, 12), (10, 12)], BOLT_SHADE)

    # Latch handle down the right-hand edge.
    rect(px, 12, 6, 12, 9, BOLT)
    px[12, 5] = BOLT_SHADE
    px[12, 10] = BOLT_SHADE

    # Status lamp, top left of the decal area. Lit green: these boxes are only
    # interesting when they are running, and an unlit lamp reads as "not linked".
    px[6, 4] = LAMP_OFF
    px[7, 4] = LAMP_GREEN

    return img, px


def conduit_stub(px):
    """The short conduit entering from below -- shared by all three boxes."""
    rect(px, 7, 14, 8, 15, STEEL_LIT)
    px[6, 14] = OUTLINE
    px[9, 14] = OUTLINE
    px[7, 15] = STEEL_DARK
    px[8, 15] = STEEL_DARK


def _arrow(px, down, colour, shade):
    """One stencilled arrow filling the decal window, y 6..11.

    A single large arrow rather than stacked chevrons: at 10 px across, two shapes
    merge into an orange smudge the moment the block is more than a few metres away,
    and the whole point of the decal is telling feed from service at a glance.
    """
    head = (9, 10, 11) if down else (8, 7, 6)
    widths = ((5, 10), (6, 9), (7, 8))
    for y, (x0, x1) in zip(head, widths):
        rect(px, x0, y, x1, y, colour)
    # Darken the trailing edge of the head so it reads as bevelled metal.
    rect(px, 5, head[0], 10, head[0], colour)
    px[5, head[0]] = shade
    px[10, head[0]] = shade
    # Shaft, on the opposite side of the head.
    shaft = range(6, 9) if down else range(9, 12)
    for y in shaft:
        rect(px, 7, y, 8, y, colour)


def feed_front():
    """Arrow pointing *into* the box: power leaving the world for the grid."""
    img, px = panel_base()
    _arrow(px, True, ORANGE, ORANGE_DARK)
    conduit_stub(px)
    return img


def service_front():
    """Arrow pointing *out of* the box: power returning to the world."""
    img, px = panel_base()
    _arrow(px, False, ORANGE, ORANGE_DARK)
    conduit_stub(px)
    return img


def signal_front():
    """A redstone tap: a terminal diamond with two leads. No orange -- this box
    moves no flux at all, and the colour is the fastest way to say so."""
    img, px = panel_base()
    # Diamond, centred on (8, 8).
    px[8, 6] = REDSTONE
    rect(px, 7, 7, 9, 7, REDSTONE)
    rect(px, 6, 8, 10, 8, REDSTONE)
    rect(px, 7, 9, 9, 9, REDSTONE)
    px[8, 10] = REDSTONE
    # Shading on the lower right, matching the panel's top-left light.
    px[9, 9] = REDSTONE_DARK
    px[9, 8] = REDSTONE_DARK
    # Leads out to the terminal screws.
    px[5, 8] = REDSTONE_DARK
    px[11, 8] = REDSTONE_DARK
    px[5, 6] = BOLT
    px[10, 11] = BOLT
    conduit_stub(px)
    return img


def device_side():
    """Ribbed flank with a vent slot."""
    img = new_image(STEEL_DARK)
    px = img.load()
    brushed_metal(px, 3, 2, 12, 13, STEEL, STEEL_DARK)
    outline_rect(px, 3, 2, 12, 13, OUTLINE)
    rect(px, 4, 3, 11, 3, STEEL_HILIGHT)
    # Vent louvres.
    for y in (6, 8, 10):
        rect(px, 5, y, 10, y, STEEL_DARK)
        rect(px, 5, y + 1, 10, y + 1, OUTLINE)
    dots(px, [(4, 4), (11, 4), (4, 12), (11, 12)], BOLT)
    conduit_stub(px)
    return img


def device_top():
    """Lid: a plain plate with four bolts and a lifting seam.

    Content stays off the outermost row and column. The block's top face samples
    y 0..6 of this texture, so the lid does lose its first pixel row to the frame --
    but every other grid texture keeps a clean one-pixel border, and a lid that broke
    the pattern would read as a rendering fault rather than a design choice.
    """
    img = new_image(STEEL_DARK)
    px = img.load()
    brushed_metal(px, 3, 1, 12, 7, STEEL, STEEL_DARK)
    outline_rect(px, 3, 1, 12, 7, OUTLINE)
    rect(px, 4, 4, 11, 4, STEEL_DARK)
    dots(px, [(5, 2), (10, 2), (5, 6), (10, 6)], BOLT)
    return img


# ---------------------------------------------------------------------------
# Console
# ---------------------------------------------------------------------------
def console_housing():
    """The unformed housing block: a riveted cabinet panel."""
    img = new_image(STEEL)
    px = img.load()
    brushed_metal(px, 0, 0, 15, 15, STEEL, STEEL_DARK)
    outline_rect(px, 0, 0, 15, 15, OUTLINE)
    rect(px, 1, 1, 14, 1, STEEL_HILIGHT)
    rect(px, 1, 14, 14, 14, STEEL_DARK)
    # Rivet border.
    for x in (2, 7, 13):
        dots(px, [(x, 3), (x, 12)], BOLT)
    for y in (3, 12):
        dots(px, [(2, y), (13, y)], BOLT)
    # A blank plate in the middle, where the screen goes once formed.
    rect(px, 4, 5, 11, 10, STEEL_DARK)
    outline_rect(px, 4, 5, 11, 10, OUTLINE)
    return img


def console_side():
    """Cabinet flank: vent grille over brushed steel."""
    img = new_image(STEEL)
    px = img.load()
    brushed_metal(px, 0, 0, 15, 15, STEEL, STEEL_DARK)
    outline_rect(px, 0, 0, 15, 15, OUTLINE)
    for y in range(4, 13, 2):
        rect(px, 3, y, 12, y, OUTLINE)
        rect(px, 3, y + 1, 12, y + 1, STEEL_DARK)
    dots(px, [(2, 2), (13, 2), (2, 13), (13, 13)], BOLT)
    return img


def console_top():
    """Cabinet lid with a cable gland."""
    img = new_image(STEEL)
    px = img.load()
    brushed_metal(px, 0, 0, 15, 15, STEEL, STEEL_DARK)
    outline_rect(px, 0, 0, 15, 15, OUTLINE)
    rect(px, 6, 6, 9, 9, STEEL_DARK)
    outline_rect(px, 6, 6, 9, 9, OUTLINE)
    dots(px, [(7, 7), (8, 7), (7, 8), (8, 8)], BOLT)
    dots(px, [(2, 2), (13, 2), (2, 13), (13, 13)], BOLT)
    return img


def _console_screen_frame(sweep_row):
    """One frame of the lit screen: dark glass, scanlines, a fake trace and
    status LEDs, plus -- if sweep_row lands inside the glass -- a brighter
    scanline standing in for a CRT's refresh bar.

    The sweep is applied last and only ever brightens plain glass pixels
    (GLASS or GLASS_DARK). Skipping the trace, baseline and LEDs keeps those
    readouts fixed every frame; if the sweep touched them too the screen would
    read as flickering data rather than an idling display.
    """
    img = new_image(GLASS_DARK)
    px = img.load()
    outline_rect(px, 0, 0, 15, 15, OUTLINE)
    rect(px, 1, 1, 14, 14, GLASS)
    # Scanlines.
    for y in range(2, 14, 2):
        rect(px, 2, y, 13, y, GLASS_DARK)
    # A load trace across the middle -- the thing the Stats tab draws for real.
    trace = [(2, 10), (3, 9), (4, 9), (5, 7), (6, 8), (7, 6), (8, 5),
             (9, 6), (10, 4), (11, 5), (12, 4), (13, 6)]
    dots(px, trace, SCREEN_GLOW)
    dots(px, [(x, y + 1) for x, y in trace], SCANLINE)
    # Baseline and status LEDs along the bottom.
    rect(px, 2, 12, 13, 12, SCANLINE)
    dots(px, [(3, 14), (5, 14)], LAMP_GREEN)
    dots(px, [(7, 14)], ORANGE)
    dots(px, [(9, 14), (11, 14)], LAMP_OFF)
    if 2 <= sweep_row <= 13:
        for x in range(2, 14):
            if px[x, sweep_row] in (GLASS, GLASS_DARK):
                px[x, sweep_row] = SCANLINE
    return img


# Rows the sweep visits, one per frame, spaced across the glass (y 2..13) but
# not evenly dividing it -- the last frame sits at 11, not 13, so the loop's
# wrap from bottom back to top reads as a CRT's vertical retrace rather than
# a stutter.
_SWEEP_ROWS = (2, 5, 8, 11)


def console_screen():
    """The lit screen half, stacked into a 4-frame Minecraft animation sheet.

    A static screen next to a lit status lamp and a live Stats tab reads as
    broken hardware, so this is animated. MC's animated sprite format is just
    N square frames stacked top to bottom in one PNG with a sibling .mcmeta
    (written by main()) -- there is no per-frame file, so the sheet has to be
    assembled here rather than saved frame by frame.
    """
    sheet = Image.new("RGBA", (SIZE, SIZE*len(_SWEEP_ROWS)))
    for i, sweep_row in enumerate(_SWEEP_ROWS):
        sheet.paste(_console_screen_frame(sweep_row), (0, i*SIZE))
    return sheet


def console_panel():
    """The lower half: switch bank, breaker levers and a keyboard shelf."""
    img = new_image(STEEL)
    px = img.load()
    brushed_metal(px, 0, 0, 15, 15, STEEL, STEEL_DARK)
    outline_rect(px, 0, 0, 15, 15, OUTLINE)
    # Keyboard shelf across the top.
    rect(px, 1, 1, 14, 3, STEEL_DARK)
    outline_rect(px, 1, 1, 14, 3, OUTLINE)
    for x in range(3, 13, 2):
        px[x, 2] = STEEL_HILIGHT
    # Breaker levers: a row of small switches, one thrown.
    for i, x in enumerate(range(3, 13, 3)):
        rect(px, x, 6, x + 1, 9, STEEL_DARK)
        outline_rect(px, x, 6, x + 1, 9, OUTLINE)
        # The odd one out sits down, so the bank does not read as a repeat pattern.
        lever_y = 8 if i == 2 else 7
        rect(px, x, lever_y, x + 1, lever_y, ORANGE if i != 2 else REDSTONE)
    # Label strip.
    rect(px, 2, 12, 13, 13, STEEL_DARK)
    for x in range(3, 13, 2):
        px[x, 12] = STEEL_LIT
    return img


TEXTURES = {
    "grid_device_feed_front": feed_front,
    "grid_device_service_front": service_front,
    "grid_device_signal_front": signal_front,
    "grid_device_side": device_side,
    "grid_device_top": device_top,
    "grid_console_housing": console_housing,
    "grid_console_side": console_side,
    "grid_console_top": console_top,
    "grid_console_screen": console_screen,
    "grid_console_panel": console_panel,
}

DEFAULT_OUT = os.path.join("src", "main", "resources", "assets", "immersiveengineering",
                           "textures", "blocks")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", default=DEFAULT_OUT,
                        help="directory to write the PNGs into")
    args = parser.parse_args()

    os.makedirs(args.out, exist_ok=True)
    for name, builder in sorted(TEXTURES.items()):
        path = os.path.join(args.out, name + ".png")
        image = builder()
        image.save(path, "PNG", optimize=True)
        print("wrote", path)
        # Anything taller than it is wide is a stacked animation sheet, not a
        # block face. Minecraft doesn't need to be told the frame size -- it
        # divides by the width on its own -- but with no .mcmeta at all it
        # just squashes every frame into one texture, silently, which is a
        # far worse failure than a missing file would be.
        if image.height!=image.width:
            meta_path = path+".mcmeta"
            with open(meta_path, "w") as f:
                f.write(json.dumps({"animation": {"frametime": ANIMATION_FRAMETIME}}, indent=2)+"\n")
            print("wrote", meta_path)


if __name__ == "__main__":
    main()
