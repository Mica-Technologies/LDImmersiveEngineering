#!/usr/bin/env python3
"""
Regenerates the virtual fluid network's block textures.

The fittings are the grid's siblings and are meant to read that way: same box, same
bezel, same bolts, same lamp. Everything here is imported from make_grid_textures
rather than re-drawn, so the two sets cannot drift apart -- which matters, because a
wall carrying both a Grid Feed Unit and a Fluid Inlet should look like one
installation rather than two mods.

The one thing that differs is the accent colour. The grid's is orange; this is a
cool blue, and it is doing real work: at a glance across a room, "orange box" means
power and "blue box" means fluid. That is the only cue a player gets from four
metres away, so it is the only thing worth changing.

Usage:  python docs/tools/make_fluidnet_textures.py [--out <assets dir>]

Requires Pillow. Writes 16x16 PNGs into
src/main/resources/assets/immersiveengineering/textures/blocks/.

Geometry note: these use the grid's utility_box model, which spans [3,2] to [13,14]
in block pixels, so the *front* face samples only x 3..12, y 2..13 of the texture.
Everything meaningful lives inside that window.
"""

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import make_grid_textures as grid
from make_grid_textures import (BOLT, BOLT_SHADE, OUTLINE, STEEL, STEEL_DARK,
                                STEEL_HILIGHT, STEEL_LIT, brushed_metal, dots,
                                new_image, outline_rect, rect)

DEFAULT_OUT = os.path.join("src", "main", "resources", "assets", "immersiveengineering",
                           "textures", "blocks")

# ---------------------------------------------------------------------------
# The one palette change. Cool where the grid is warm.
# ---------------------------------------------------------------------------
FLUID = (58, 150, 190, 255)
FLUID_DARK = (34, 98, 130, 255)


def inlet_front():
    """Arrow pointing *into* the box: fluid leaving the world for a main."""
    img, px = grid.panel_base()
    grid._arrow(px, True, FLUID, FLUID_DARK)
    grid.conduit_stub(px)
    return img


def outlet_front():
    """Arrow pointing *out of* the box: fluid returning to the world."""
    img, px = grid.panel_base()
    grid._arrow(px, False, FLUID, FLUID_DARK)
    grid.conduit_stub(px)
    return img


def valve_front():
    """A handwheel. No arrow, because this fitting moves nothing at all -- and no
    blue either, for the same reason the grid's Signal Unit is not orange."""
    img, px = grid.panel_base()
    # Spokes, centred on (8, 8). Drawn before the rim so the rim caps them cleanly.
    rect(px, 8, 6, 8, 10, BOLT)
    rect(px, 6, 8, 10, 8, BOLT)
    # Rim: an octagon, which at this size is the only shape that reads as round.
    rect(px, 7, 5, 9, 5, STEEL_HILIGHT)
    rect(px, 7, 11, 9, 11, STEEL_DARK)
    rect(px, 5, 7, 5, 9, STEEL_HILIGHT)
    rect(px, 11, 7, 11, 9, STEEL_DARK)
    dots(px, [(6, 6), (10, 6)], STEEL_HILIGHT)
    dots(px, [(6, 10), (10, 10)], STEEL_DARK)
    # Hub.
    px[8, 8] = BOLT_SHADE
    grid.conduit_stub(px)
    return img


def device_side():
    """Ribbed flank. The grid's, verbatim -- the fittings are the same hardware."""
    return grid.device_side()


def device_top():
    """Lid. The grid's, verbatim."""
    return grid.device_top()


def console_housing():
    """The unformed housing block. The grid's cabinet panel with a blue plate, so a
    half-built fluid console is not mistaken for a half-built power one."""
    img = grid.console_housing()
    px = img.load()
    rect(px, 5, 6, 10, 9, FLUID_DARK)
    outline_rect(px, 5, 6, 10, 9, OUTLINE)
    return img


def _recolour(img):
    """Turn the grid's screen into the fluid console's.

    The only warm pixels on that screen are the orange status LEDs (ORANGE) and
    their unused shade (ORANGE_DARK) -- everything else is already grey or teal
    glass -- so recolouring those two is enough without redrawing anything.
    """
    px = img.load()
    for y in range(img.height):
        for x in range(img.width):
            if px[x, y]==grid.ORANGE:
                px[x, y] = FLUID
            elif px[x, y]==grid.ORANGE_DARK:
                px[x, y] = FLUID_DARK
    return img


def console_screen():
    """The whole screen on one block, for the item model."""
    return _recolour(grid.console_screen())


def console_screen_left():
    """The left-hand block of the display, split the same way the grid's is.

    The fluid console is the grid console's mirror down to the block count, so
    it had the same two-complete-screens bug and gets the same fix. Anything
    here that stops mirroring is a bug in one of them.
    """
    return _recolour(grid.console_screen_left())


def console_screen_right():
    """The right-hand block of the display."""
    return _recolour(grid.console_screen_right())


TEXTURES = {
    "fluidnet_device_side": device_side,
    "fluidnet_device_top": device_top,
    "fluidnet_device_inlet_front": inlet_front,
    "fluidnet_device_outlet_front": outlet_front,
    "fluidnet_device_valve_front": valve_front,
    "fluidnet_console_housing": console_housing,
    "fluidnet_console_screen": console_screen,
    "fluidnet_console_screen_left": console_screen_left,
    "fluidnet_console_screen_right": console_screen_right,
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", default=DEFAULT_OUT,
                        help="directory to write the PNGs into")
    args = parser.parse_args()

    os.makedirs(args.out, exist_ok=True)
    for name, builder in sorted(TEXTURES.items()):
        path = os.path.join(args.out, name+".png")
        image = builder()
        image.save(path, "PNG", optimize=True)
        print("wrote", path)
        # Same rule as the grid script, and it has to be: this console_screen
        # is the grid's animation sheet with two pixels recoloured, so if it
        # ends up taller than it is wide it needs the exact same .mcmeta or it
        # squashes in-game just like the grid one would.
        if image.height!=image.width:
            meta_path = path+".mcmeta"
            with open(meta_path, "w") as f:
                f.write(json.dumps({"animation": {"frametime": grid.ANIMATION_FRAMETIME}}, indent=2)+"\n")
            print("wrote", meta_path)


if __name__ == "__main__":
    main()
