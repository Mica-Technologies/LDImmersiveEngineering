#!/usr/bin/env python3
"""
Regenerates the Fluid Control Console's single-piece OBJ model and its textures.

The fluid console is the power console's deliberate mirror -- same 2x2 wall, same
kit of parts, same gesture with the hammer -- and until this script it was still
what the grid console used to be: four identical housings drawn as two cabinets
side by side, with the display split across two half-sprites so nothing on it
reached the far edge.  This gives it the same treatment its twin got in
`make_terminal_assets.py`: one OBJ drawn by the master, dummies rendering nothing,
one animated sprite across the whole glass.

**The shell is imported, not copied.**  `make_terminal_assets.build_model` is the
one description of that cabinet; if the two consoles are supposed to be the same
furniture then there must not be a second copy of it here to drift.  What differs
is the paint, and only the paint:

  * the accent is the fluid network's cool blue instead of the grid's orange --
    the same "orange means power, blue means fluid" cue the fittings already use
    from four metres away;
  * the display shows the plant rather than the load: a header main across the
    top, eleven tank level gauges hanging off it, flow pips moving along the
    bottom, and the delivery main under them.  A power bar graph on a fluid
    console would be the wrong readout drawn beautifully.

**Authored for facing NORTH.**  See make_terminal_assets for the whole convention
and the reason for it; the blockstate turns the model with the same
`transform.rotation.y` entries (north 0, south 180, west 90, east -90) about the
block centre.

Two materials, because one of them has to be swappable:

  terminal  ->  blocks/fluidnet_terminal          the 64x64 atlas below
  screen    ->  blocks/fluidnet_terminal_screen   the animated display

The blockstate replaces `#screen` with the dead-screen texture when the console
has no power (OBJModel.makeLibWithReplacements matches "#" + material name), which
is why that material must keep the name `screen`.

Usage:  python docs/tools/make_fluid_console_assets.py [--assets <assets dir>]

Requires Pillow.  Writes:
    models/block/fluidnet/terminal.obj, terminal.mtl
    textures/blocks/fluidnet_terminal.png
    textures/blocks/fluidnet_terminal_screen.png (+ .mcmeta)
    textures/blocks/fluidnet_terminal_screen_off.png
"""

import argparse
import json
import os
import sys

from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from make_grid_textures import (ANIMATION_FRAMETIME, BOLT, BOLT_SHADE, GLASS,
                                GLASS_DARK, LAMP_GREEN, LAMP_OFF, OUTLINE,
                                STEEL, STEEL_DARK, STEEL_HILIGHT, STEEL_LIT)
from make_fluidnet_textures import FLUID, FLUID_DARK
from make_terminal_assets import (REGIONS, SCREEN_BAND, SCREEN_FRAMES,
                                  SCREEN_SPRITE, SHEET, brushed, build_model,
                                  check_layout, check_shell, dots,
                                  outline_rect, rect, write_obj)
from make_terminal_assets import (draw_panel, draw_plinth, draw_trim, draw_vent)

# The one colour this file adds. FLUID and FLUID_DARK are the fittings' blue; the
# glow is what a lit meniscus and a moving flow pip are drawn in, and it is the
# cool counterpart of the grid screen's SCREEN_GLOW rather than a second idea.
FLUID_GLOW = (126, 206, 236, 255)

# ---------------------------------------------------------------------------
# The display.
#
# Same sprite shape as the grid's -- a square 32x32 frame because Minecraft's
# animation format assumes square frames, with the art in the top SCREEN_BAND
# rows and the rest filled with dark glass so a drifting UV still lands on
# something screen-shaped.
#
# Rows, top to bottom:
#     0..1    clear glass and a scanline: the head of the screen
#     2       the header main, full width
#     3..10   eleven tank level gauges hanging off it, on a three-pixel pitch
#     10      flow pips in the gaps between gauges, moving frame to frame
#     11      the delivery main, full width -- what the gauges stand on
#
# Eleven gauges on a three-pixel pitch is what reaches both edges of a 32-wide
# sprite: the first occupies columns 0..1 and the last 30..31.  Ten would leave a
# gap on the right, which is the exact fault this rework answers.
# ---------------------------------------------------------------------------
GAUGE_COUNT = 11
GAUGE_PITCH = 3
GAUGE_TOP = 3               # the highest row a gauge tube may occupy
HEADER_ROW = 2
BASELINE_ROW = SCREEN_BAND-1
PIP_ROW = SCREEN_BAND-2

# How full each tank stands, per frame, in rows.  Written out rather than
# generated for the same reason the grid's bar heights are: this is the console's
# idle animation and it should read as a plant ticking over, not as noise.  The
# two end tanks stay high because they are the ones on the edge of the glass --
# a screen whose outermost readouts sit empty looks like a screen that stops
# short, which is the complaint this whole rework exists to answer.
GAUGE_LEVELS = [
    [6, 4, 7, 5, 3, 6, 4, 7, 5, 4, 6],
    [7, 5, 6, 6, 4, 5, 5, 6, 6, 5, 7],
    [6, 6, 5, 7, 5, 4, 6, 5, 7, 6, 6],
    [7, 5, 6, 5, 4, 5, 5, 6, 6, 4, 7],
]

# Rows the bright refresh line visits, one per frame. Same reasoning as the
# grid's: an uneven last step reads as a CRT retrace rather than a stutter.
SWEEP_ROWS = (1, 4, 7, 9)

# The refresh line where it crosses a gauge or a main: a lighter tint of the glow,
# so that it reads as a line over the readout rather than vanishing into it.
SWEEP_OVER_READOUT = (200, 236, 250, 255)


# ---------------------------------------------------------------------------
# The atlas.  Region layout is the terminal's, because the model is the
# terminal's; only what is painted into each region changes.
# ---------------------------------------------------------------------------
def draw_fascia(px, x, y):
    """The cabinet front: pump telltales, valve lamps and a label strip.

    Green for what is running, blue for what is armed, dark for what is not --
    the same three-state reading the grid's fascia has, with the accent swapped.
    """
    brushed(px, x, y, x+31, y+9, STEEL_DARK, OUTLINE)
    rect(px, x, y, x+31, y, STEEL_LIT)
    rect(px, x, y+9, x+31, y+9, OUTLINE)

    lit = (LAMP_GREEN, FLUID, LAMP_GREEN, LAMP_OFF, FLUID, LAMP_GREEN, FLUID)
    for row, base in ((y+2, 0), (y+5, 3)):
        for i in range(7):
            bx = x+2+i*4
            rect(px, bx, row, bx+2, row+2, STEEL)
            outline_rect(px, bx, row, bx+2, row+2, OUTLINE)
            px[bx+1, row+1] = lit[(i+base) % len(lit)]

    # Pressure gauge pair on the right, where the buttons stop.
    rect(px, x+30, y+2, x+30, y+3, FLUID)
    rect(px, x+30, y+5, x+30, y+6, LAMP_GREEN)
    # Label strip along the bottom.
    rect(px, x+2, y+8, x+29, y+8, STEEL)
    for i in range(2, 29, 3):
        px[x+i, y+8] = STEEL_DARK


def draw_desk(px, x, y):
    """The sloped work surface.

    Row 0 is the *back* edge, against the monitor bay, because the face this
    lands on is tilted towards the player and its texture-up runs up the slope.
    Readout strip at the top, handle bars where hands land, keys at the front.
    """
    brushed(px, x, y, x+31, y+9, STEEL, STEEL_DARK)
    rect(px, x, y, x+31, y, OUTLINE)

    # Back strip: annunciator lamps.
    rect(px, x+1, y+1, x+30, y+1, STEEL_DARK)
    for i, colour in enumerate((LAMP_GREEN, FLUID, LAMP_GREEN, LAMP_OFF,
                                FLUID, LAMP_GREEN, FLUID, LAMP_OFF)):
        px[x+3+i*4, y+1] = colour

    # The handle bars. Two of them, full length: the reference for this whole
    # cabinet is a desk you brace against with both hands.
    for bx0, bx1 in ((x+2, x+14), (x+17, x+29)):
        rect(px, bx0, y+3, bx1, y+3, FLUID)
        rect(px, bx0, y+4, bx1, y+4, FLUID_DARK)
        px[bx0, y+3] = BOLT_SHADE
        px[bx1, y+3] = BOLT_SHADE
        px[bx0, y+4] = OUTLINE
        px[bx1, y+4] = OUTLINE

    # Key bank at the front edge.
    rect(px, x+2, y+6, x+29, y+8, STEEL_DARK)
    outline_rect(px, x+2, y+6, x+29, y+8, OUTLINE)
    for i in range(x+3, x+29, 2):
        px[i, y+7] = STEEL_LIT
    rect(px, x, y+9, x+31, y+9, STEEL_DARK)


def draw_hood(px, x, y):
    """The two-pixel eyebrow above the glass."""
    rect(px, x, y, x+31, y, STEEL_LIT)
    rect(px, x, y+1, x+31, y+1, OUTLINE)
    for i in range(x+3, x+30, 4):
        px[i, y] = STEEL_DARK
    # One cool tick in the middle of the eyebrow, so the hood is not the only
    # part of the console that could belong to either machine.
    px[x+15, y] = FLUID_DARK
    px[x+16, y] = FLUID_DARK


def draw_lip(px, x, y):
    """The desk's nose: the one full-width accent line on the whole console."""
    rect(px, x, y, x+31, y+1, STEEL_DARK)
    rect(px, x+1, y, x+30, y, FLUID)
    rect(px, x+1, y+1, x+30, y+1, FLUID_DARK)


def draw_bezel(px, x, y):
    """The monitor's front frame.

    The middle of this region is the screen opening and is never rendered -- the
    glass is its own quad, one pixel further back -- but it is painted as dark
    glass anyway so the frame reads correctly in the atlas and a UV that slipped
    lands on something plausible instead of on the vent grille.
    """
    rect(px, x, y, x+31, y+13, STEEL)
    rect(px, x, y, x+31, y, STEEL_HILIGHT)
    rect(px, x, y+13, x+31, y+13, STEEL_DARK)
    outline_rect(px, x+1, y+1, x+30, y+12, OUTLINE)
    # The hole: cols 2..29, rows 2..11 -- exactly the quad the glass covers.
    rect(px, x+2, y+2, x+29, y+11, GLASS_DARK)
    dots(px, [(x, y+13), (x+31, y+13)], BOLT_SHADE)
    dots(px, [(x+1, y), (x+30, y)], BOLT)
    # Bottom bezel: three small tell-tales, the only lit thing on the frame.
    dots(px, [(x+4, y+13), (x+6, y+13)], LAMP_GREEN)
    px[x+8, y+13] = FLUID


def build_atlas():
    img = Image.new("RGBA", (SHEET, SHEET), STEEL_DARK)
    px = img.load()
    draw_fascia(px, *REGIONS["fascia"][:2])
    draw_desk(px, *REGIONS["desk"][:2])
    draw_hood(px, *REGIONS["hood"][:2])
    draw_bezel(px, *REGIONS["bezel"][:2])
    draw_lip(px, *REGIONS["lip"][:2])
    # The plain steel of the ends, the back, the vents and the recess walls is
    # the terminal's own: these two consoles are the same hardware, and only the
    # surfaces a player reads information off are meant to differ.
    draw_panel(px, *REGIONS["panel"][:2])
    draw_vent(px, *REGIONS["vent"][:2])
    draw_plinth(px, *REGIONS["plinth"][:2])
    draw_trim(px, *REGIONS["trim"][:2])
    return img


# ---------------------------------------------------------------------------
# The screen
# ---------------------------------------------------------------------------
def screen_frame(index):
    """One frame of the display: level gauges between two full-width mains."""
    img = Image.new("RGBA", (SCREEN_SPRITE, SCREEN_SPRITE), GLASS_DARK)
    px = img.load()
    bounds = (SCREEN_SPRITE, SCREEN_SPRITE)
    rect(px, 0, 0, SCREEN_SPRITE-1, SCREEN_BAND-1, GLASS, bounds)

    # Scanlines: every other row, all the way across.
    for y in range(0, SCREEN_BAND, 2):
        rect(px, 0, y, SCREEN_SPRITE-1, y, GLASS_DARK, bounds)

    # The header main, edge to edge.
    rect(px, 0, HEADER_ROW, SCREEN_SPRITE-1, HEADER_ROW, FLUID_DARK, bounds)

    # The gauges. Each is a two-pixel tube from GAUGE_TOP down to the row above
    # the delivery main, dark where it is empty and blue where it is not, with a
    # lit meniscus at the surface.
    levels = GAUGE_LEVELS[index % len(GAUGE_LEVELS)]
    for i, level in enumerate(levels):
        gx = i*GAUGE_PITCH
        rect(px, gx, GAUGE_TOP, gx+1, BASELINE_ROW-1, GLASS_DARK, bounds)
        surface = BASELINE_ROW-level
        rect(px, gx, surface, gx+1, BASELINE_ROW-1, FLUID, bounds)
        rect(px, gx, surface, gx+1, surface, FLUID_GLOW, bounds)

    # Flow pips in the gaps between gauges, marching one gap per frame: the only
    # thing on the screen that says the network is moving rather than merely full.
    for gap in range(GAUGE_COUNT-1):
        if (gap+index) % 3==0:
            px[gap*GAUGE_PITCH+2, PIP_ROW] = FLUID_GLOW

    # The delivery main the gauges stand on, edge to edge.
    rect(px, 0, BASELINE_ROW, SCREEN_SPRITE-1, BASELINE_ROW, FLUID, bounds)

    # The refresh line, applied last and over everything, for the same reason the
    # grid terminal's is (see make_terminal_assets.screen_frame): a retrace that
    # skips the readouts reads as a line passing behind them.  Over glass it is the
    # fluid glow; over a gauge or main it is a lighter tint of it, so it stays
    # visible where the plain glow would vanish into the readout.
    sweep = SWEEP_ROWS[index % len(SWEEP_ROWS)]
    for x in range(SCREEN_SPRITE):
        px[x, sweep] = FLUID_GLOW if px[x, sweep] in (GLASS, GLASS_DARK) else SWEEP_OVER_READOUT
    return img


def build_screen():
    sheet = Image.new("RGBA", (SCREEN_SPRITE, SCREEN_SPRITE*SCREEN_FRAMES), GLASS_DARK)
    for i in range(SCREEN_FRAMES):
        sheet.paste(screen_frame(i), (0, i*SCREEN_SPRITE))
    return sheet


def build_screen_off():
    """The unpowered display: cold glass, no trace, one reflection.

    The console keeps working without power -- the GUI opens read-only -- so the
    screen has to say "dark", not "broken".  A single highlight down the left is
    what tells a player they are looking at glass rather than at a hole.
    """
    img = Image.new("RGBA", (SCREEN_SPRITE, SCREEN_SPRITE), GLASS_DARK)
    px = img.load()
    bounds = (SCREEN_SPRITE, SCREEN_SPRITE)
    rect(px, 0, 0, SCREEN_SPRITE-1, SCREEN_BAND-1, GLASS_DARK, bounds)
    for y in range(0, SCREEN_BAND, 2):
        rect(px, 0, y, SCREEN_SPRITE-1, y, OUTLINE, bounds)
    for y in range(1, SCREEN_BAND-1):
        px[1, y] = GLASS
    return img


# ---------------------------------------------------------------------------
# Self-checks.  None of what this script writes is validated by anything at
# runtime: a hole in the mesh, a quad wound inside out, a UV off its sprite or a
# readout that stops halfway across the glass all render silently as something
# wrong-looking.  The model checks live in make_terminal_assets (winding and UV
# bounds per quad, then the divergence theorem over the whole shell); these are
# the display's.
# ---------------------------------------------------------------------------
def check_display(sheet):
    """The complaint this rework answers, asserted rather than eyeballed.

    Mirrors GridAssetsTest.displayRunsEdgeToEdge, in the generator, so a bad
    screen is caught before it is written rather than after it is committed:
    the mains have to cross the whole display, several rows have to run the full
    width, and both outermost columns have to carry a readout.
    """
    frame = sheet.width
    px = sheet.load()
    dead = px[0, frame-1]
    band = 0
    for y in range(frame):
        if any(px[x, y]!=dead for x in range(frame)):
            band = y+1
    if band <= 0 or band >= frame:
        raise SystemExit("the display's art band is %d rows of %d" % (band, frame))

    for f in range(sheet.height//frame):
        top = f*frame
        flat_rows, flat_colours = [], set()
        for y in range(band):
            first = px[0, top+y]
            if all(px[x, top+y]==first for x in range(frame)):
                flat_rows.append(y)
                flat_colours.add(first)
        if band-1 not in flat_rows:
            raise SystemExit("frame %d: the delivery main does not cross the display" % f)
        if len(flat_rows) < 3:
            raise SystemExit("frame %d: only %d full-width rows; the scanlines are supposed to be"
                             % (f, len(flat_rows)))
        if len(flat_colours) < 2:
            raise SystemExit("frame %d: every full-width row is one colour, so there is nothing "
                             "to see across the glass" % f)
        empty = {px[0, top+y] for y in flat_rows if y!=band-1}
        for x in (0, frame-1):
            drawn = sum(1 for y in range(band-1) if px[x, top+y] not in empty)
            if drawn < 2:
                raise SystemExit("frame %d: column %d is empty glass, so the readout stops short "
                                 "of the edge of the screen" % (f, x))
    return band


def check_opaque(image, name):
    px = image.load()
    for y in range(image.height):
        for x in range(image.width):
            if px[x, y][3]!=255:
                raise SystemExit("%s: pixel %d,%d is not opaque" % (name, x, y))


def check_determinism():
    """The same inputs have to produce the same bytes.

    Everything here is meant to be regenerable: `git status` after a run is the
    only review this artwork gets, and a generator that shuffles a dict or seeds
    a random would make every run a diff nobody can read.
    """
    if build_atlas().tobytes()!=build_atlas().tobytes():
        raise SystemExit("the atlas is not deterministic")
    if build_screen().tobytes()!=build_screen().tobytes():
        raise SystemExit("the screen sheet is not deterministic")
    if build_screen_off().tobytes()!=build_screen_off().tobytes():
        raise SystemExit("the dark screen is not deterministic")
    if serialise(build_model())!=serialise(build_model()):
        raise SystemExit("the model is not deterministic")


def serialise(model):
    return (tuple(model.positions), tuple(model.uvs),
            tuple((name, material, tuple(tuple(f) for f in faces))
                  for name, material, faces in model.groups))


def write_mtl(path):
    body = [
        "# Generated by docs/tools/make_fluid_console_assets.py -- do not hand-edit",
        "newmtl terminal",
        "map_Ka immersiveengineering:blocks/fluidnet_terminal",
        "",
        "newmtl screen",
        "map_Ka immersiveengineering:blocks/fluidnet_terminal_screen",
    ]
    with open(path, "w") as handle:
        handle.write("\n".join(body)+"\n")


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    repo = os.path.dirname(os.path.dirname(here))
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets", default=os.path.join(
        repo, "src", "main", "resources", "assets", "immersiveengineering"))
    args = parser.parse_args()

    used = check_layout()
    model = build_model()
    volume = check_shell(model)
    check_determinism()

    model_dir = os.path.join(args.assets, "models", "block", "fluidnet")
    os.makedirs(model_dir, exist_ok=True)
    obj_path = os.path.join(model_dir, "terminal.obj")
    write_obj(model, obj_path, mtllib="terminal.mtl",
              generator="docs/tools/make_fluid_console_assets.py")
    write_mtl(os.path.join(model_dir, "terminal.mtl"))
    print("wrote %s (%d quads, %d vertices, %.4f blocks enclosed)"
          % (os.path.relpath(obj_path, repo),
             sum(len(f) for _, _, f in model.groups), len(model.positions), volume))

    screen = build_screen()
    band = check_display(screen)

    texture_dir = os.path.join(args.assets, "textures", "blocks")
    os.makedirs(texture_dir, exist_ok=True)
    for name, image in (("fluidnet_terminal", build_atlas()),
                        ("fluidnet_terminal_screen", screen),
                        ("fluidnet_terminal_screen_off", build_screen_off())):
        check_opaque(image, name)
        path = os.path.join(texture_dir, name+".png")
        image.save(path, "PNG", optimize=True)
        print("wrote %s" % os.path.relpath(path, repo))
        # Same rule as every other sheet in this fork: a stacked animation with no
        # .mcmeta is not an error Minecraft reports, it is every frame smeared into
        # one texture.
        if image.height!=image.width:
            meta_path = path+".mcmeta"
            with open(meta_path, "w") as handle:
                handle.write(json.dumps({"animation": {"frametime": ANIMATION_FRAMETIME}},
                                        indent=2)+"\n")
            print("wrote %s" % os.path.relpath(meta_path, repo))

    print("%d regions, %d of %d atlas pixels used, no overlaps; display art band %d rows"
          % (len(REGIONS), used, SHEET*SHEET, band))


if __name__ == "__main__":
    main()
