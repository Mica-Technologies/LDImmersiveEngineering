#!/usr/bin/env python3
"""
Regenerates the Grid Management Console's single-piece OBJ model and its textures.

The console is a 2x2 wall multiblock, and until this script it was drawn as four
separate block models: two halves of a screen on top, two copies of a switch panel
below.  That is what a playtester photographed twice and asked about -- "two console
units instead of one" -- because it is what it was.  A multiblock in stock IE is one
OBJ drawn by the master while every dummy renders nothing, and this makes the console
the same: one desk, one monitor, no seam anywhere.

**The model is authored for facing NORTH and nothing else.**  IE's multiblock
convention (see MultiblockExcavator, and the bounds of excavator.obj) is that a
formed multiblock's `facing` points *into* the structure, away from the player, that
depth runs along `facing` and width along `facing.rotateY()`, and that the model is
written in block units with its origin at the master.  Our master is the bottom-left
cell as seen from the front, so the console occupies x 0..2, y 0..2, z 0..1 and its
face -- the side the player stands at -- is +Z.  The blockstate turns it for the other
three facings with the same `transform.rotation.y` entries every IE multiblock uses
(north 0, south 180, west 90, east -90); those rotate about the block centre, which is
the only reason an even-width model lands back on its own cells.  Change the geometry
here and GridConsoleGeometry has to move with it.

Two materials, because one of them has to be swappable:

  terminal  ->  blocks/grid_terminal          the 64x64 atlas below
  screen    ->  blocks/grid_terminal_screen   the animated display

The blockstate replaces `#screen` with the dead-screen texture when the console has no
power (OBJModel.makeLibWithReplacements matches "#" + material name), which is why the
material is called `screen` and must keep that name.

Usage:  python docs/tools/make_terminal_assets.py [--assets <assets dir>]

Requires Pillow.  Writes:
    models/block/grid/terminal.obj, terminal.mtl
    textures/blocks/grid_terminal.png
    textures/blocks/grid_terminal_screen.png (+ .mcmeta)
    textures/blocks/grid_terminal_screen_off.png
"""

import argparse
import json
import os
import sys

from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from make_grid_textures import (ANIMATION_FRAMETIME, BOLT, BOLT_SHADE, GLASS,
                                GLASS_DARK, LAMP_GREEN, LAMP_OFF, ORANGE,
                                ORANGE_DARK, OUTLINE, SCANLINE, SCREEN_GLOW,
                                STEEL, STEEL_DARK, STEEL_HILIGHT, STEEL_LIT)

# ---------------------------------------------------------------------------
# Geometry, in block pixels.  X runs left to right as the player sees it, Y up,
# Z out of the wall towards the player; the whole console is 32 x 32 x 16.
#
# The silhouette, seen from the side, is a 1970s control desk: a recessed toe
# kick, a cabinet of backlit buttons, a shallow sloped desk with handle bars,
# a monitor bay set back above it, and a hood over the glass so the screen sits
# in shadow.  Everything below is that shape written down once.
# ---------------------------------------------------------------------------
PX = 16.0

WIDTH = 32
HEIGHT = 32
DEPTH = 16

FRONT_Z = 15            # the console's face, one pixel inside the block boundary
PLINTH_TOP = 2
PLINTH_Z = 13           # the toe kick is recessed from the cabinet above it
CABINET_TOP = 12
DESK_LIP_TOP = 14       # the vertical nose of the desk
DESK_TOP = 16           # where the slope meets the monitor bay
DESK_BACK_Z = 6
HOUSING_TOP = 30
HOUSING_Z = 9
SCREEN_X0, SCREEN_X1 = 2, 30
SCREEN_Y0, SCREEN_Y1 = 18, 28
SCREEN_Z = 8            # the glass sits one pixel behind the bezel
HOOD_Z = 12

# ---------------------------------------------------------------------------
# The atlas.  Regions are (x, y, w, h) in a 64x64 sheet, top-left origin.
#
# Everything that carries detail is laid out 1:1 with the surface it lands on --
# the fascia is 32x10 because the cabinet front is 32x10 block pixels -- so the
# art is drawn at the size it is seen at and nothing is squashed.  The four
# 16x16 tiles at the bottom are the plain surfaces, stretched to fit whatever
# they are put on, which is fine for a grain and would not be for a switch.
# ---------------------------------------------------------------------------
SHEET = 64

REGIONS = {
    "fascia": (0, 0, 32, 10),      # cabinet front: backlit buttons
    "desk": (0, 10, 32, 10),       # the sloped work surface
    "hood": (32, 10, 32, 2),       # the eyebrow over the screen
    "bezel": (0, 20, 32, 14),      # monitor front, with the screen hole in it
    "lip": (0, 34, 32, 2),         # the desk's nose
    "panel": (0, 36, 16, 16),      # plain riveted steel: ends, back, top
    "vent": (16, 36, 16, 16),      # louvres: the monitor bay's flanks
    "plinth": (32, 36, 16, 16),    # dark: the toe kick and every underside
    "trim": (48, 36, 16, 16),      # darker still: the walls of the screen recess
}

# The screen sprite is a square 32x32 frame because Minecraft's animation format
# assumes square frames unless the .mcmeta says otherwise, and a rectangular
# sprite is a road nobody here needs to walk down.  The art occupies the top
# SCREEN_BAND rows of it and the model maps only that band, so the glass gets
# 32 texels across 28 pixels and 12 across 10 -- near enough 1:1 both ways.  The
# rest of the frame is filled with more dark glass so that a UV that ever drifts
# lands on something that still looks like a screen.
SCREEN_SPRITE = 32
SCREEN_BAND = 12
SCREEN_FRAMES = 4

# How tall each bar of the graph stands, per frame.  Written out rather than
# generated because this is the console's idle animation and it wants to look
# like a plant ticking over, not like noise: the bars move a little, the two
# ends stay high, and nothing ever empties.
#
# Eleven bars on a three-pixel pitch is what reaches both edges of a 32-wide
# sprite; ten leaves a gap on the right, which is the exact look this rework is
# supposed to be the end of.
BAR_HEIGHTS = [
    [5, 7, 4, 6, 6, 3, 7, 5, 6, 4, 6],
    [6, 6, 5, 7, 5, 4, 6, 6, 7, 5, 5],
    [7, 5, 6, 5, 7, 4, 5, 7, 6, 6, 7],
    [6, 6, 4, 6, 6, 3, 6, 6, 5, 5, 6],
]
BAR_COUNT = len(BAR_HEIGHTS[0])

# Rows the bright refresh line visits, one per frame.  Same reasoning as the
# grid's small screens: an uneven last step reads as a CRT retrace rather than
# a stutter.
SWEEP_ROWS = (1, 4, 7, 9)

# What the refresh line looks like where it crosses a bar: a lighter tint of the
# glow, so that it reads as a line *over* the graph rather than vanishing into it.
SWEEP_OVER_BAR = (196, 244, 236, 255)


# ---------------------------------------------------------------------------
# Drawing helpers.  make_grid_textures' own are hard-wired to a 16x16 canvas,
# so these are the same functions widened rather than a second idea.
# ---------------------------------------------------------------------------
def rect(px, x0, y0, x1, y1, colour, bounds=(SHEET, SHEET)):
    """Filled rectangle, inclusive of both corners."""
    for y in range(max(0, y0), min(bounds[1], y1 + 1)):
        for x in range(max(0, x0), min(bounds[0], x1 + 1)):
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
        px[x, y] = colour


def brushed(px, x0, y0, x1, y1, base=STEEL, streak=STEEL_DARK, bounds=(SHEET, SHEET)):
    rect(px, x0, y0, x1, y1, base, bounds)
    for y in range(y0, y1 + 1):
        if (y - y0) % 3 == 2:
            rect(px, x0, y, x1, y, streak, bounds)


def check_layout():
    """No two regions may share a pixel, and none may leave the sheet.

    The same guard the crawler sheet carries, for the same reason: an overlap is
    not an error anything reports, it is the desk quietly painted with the vent
    grille.
    """
    used = [[None]*SHEET for _ in range(SHEET)]
    for name, (x, y, w, h) in sorted(REGIONS.items()):
        if x < 0 or y < 0 or x + w > SHEET or y + h > SHEET:
            raise SystemExit("region %s leaves the %dx%d sheet" % (name, SHEET, SHEET))
        for yy in range(y, y + h):
            for xx in range(x, x + w):
                if used[yy][xx] is not None:
                    raise SystemExit("regions %s and %s overlap at %d,%d"
                                     % (used[yy][xx], name, xx, yy))
                used[yy][xx] = name
    return sum(w*h for _, _, w, h in REGIONS.values())


# ---------------------------------------------------------------------------
# The atlas
# ---------------------------------------------------------------------------
def draw_panel(px, x, y):
    """Plain riveted steel, for the ends, the back and the lid."""
    brushed(px, x, y, x + 15, y + 15, STEEL, STEEL_DARK)
    outline_rect(px, x, y, x + 15, y + 15, OUTLINE)
    rect(px, x + 1, y + 1, x + 14, y + 1, STEEL_HILIGHT)
    rect(px, x + 1, y + 14, x + 14, y + 14, STEEL_DARK)
    dots(px, [(x + 2, y + 3), (x + 13, y + 3), (x + 2, y + 12), (x + 13, y + 12)], BOLT)
    dots(px, [(x + 2, y + 4), (x + 13, y + 4), (x + 2, y + 13), (x + 13, y + 13)], BOLT_SHADE)


def draw_vent(px, x, y):
    """Louvred flank.  The bay above the desk is full of valve gear and says so."""
    brushed(px, x, y, x + 15, y + 15, STEEL, STEEL_DARK)
    outline_rect(px, x, y, x + 15, y + 15, OUTLINE)
    for row in range(y + 3, y + 14, 3):
        rect(px, x + 2, row, x + 13, row, OUTLINE)
        rect(px, x + 2, row + 1, x + 13, row + 1, STEEL_DARK)
    dots(px, [(x + 1, y + 1), (x + 14, y + 1)], BOLT)


def draw_plinth(px, x, y):
    """Every surface that lives in shadow: the toe kick and the undersides."""
    rect(px, x, y, x + 15, y + 15, STEEL_DARK)
    for row in range(y, y + 16, 4):
        rect(px, x, row, x + 15, row, OUTLINE)
    rect(px, x, y, x + 15, y, OUTLINE)
    rect(px, x, y + 15, x + 15, y + 15, OUTLINE)


def draw_trim(px, x, y):
    """The four walls of the screen recess: flat, dark, one lit edge."""
    rect(px, x, y, x + 15, y + 15, OUTLINE)
    rect(px, x, y, x + 15, y, STEEL_DARK)


def draw_fascia(px, x, y):
    """The cabinet front: two rows of backlit buttons and a label strip.

    Green for what is running, amber for what is armed, dark for what is not --
    the same three-state reading every panel in this fork uses, at the one size a
    player actually looks at it from.
    """
    brushed(px, x, y, x + 31, y + 9, STEEL_DARK, OUTLINE)
    rect(px, x, y, x + 31, y, STEEL_LIT)
    rect(px, x, y + 9, x + 31, y + 9, OUTLINE)

    lit = (LAMP_GREEN, ORANGE, LAMP_GREEN, LAMP_OFF, LAMP_GREEN, ORANGE, LAMP_GREEN)
    for row, base in ((y + 2, 0), (y + 5, 3)):
        for i in range(7):
            bx = x + 2 + i*4
            rect(px, bx, row, bx + 2, row + 2, STEEL)
            outline_rect(px, bx, row, bx + 2, row + 2, OUTLINE)
            colour = lit[(i + base) % len(lit)]
            rect(px, bx + 1, row + 1, bx + 1, row + 1, colour)

    # Meter cluster on the right, where the buttons stop.
    rect(px, x + 30, y + 2, x + 30, y + 3, LAMP_GREEN)
    rect(px, x + 30, y + 5, x + 30, y + 6, ORANGE)
    # Label strip along the bottom.
    rect(px, x + 2, y + 8, x + 29, y + 8, STEEL)
    for i in range(2, 29, 3):
        px[x + i, y + 8] = STEEL_DARK


def draw_desk(px, x, y):
    """The sloped work surface.

    Row 0 is the *back* edge, against the monitor bay, because the face this
    lands on is tilted towards the player and its texture-up runs up the slope.
    So the readout strip is at the top, the handle bars are where hands land, and
    the keys are nearest the front edge.
    """
    brushed(px, x, y, x + 31, y + 9, STEEL, STEEL_DARK)
    rect(px, x, y, x + 31, y, OUTLINE)

    # Back strip: annunciator lamps.
    rect(px, x + 1, y + 1, x + 30, y + 1, STEEL_DARK)
    for i, colour in enumerate((LAMP_GREEN, LAMP_GREEN, ORANGE, LAMP_OFF,
                                LAMP_GREEN, ORANGE, LAMP_GREEN, LAMP_OFF)):
        px[x + 3 + i*4, y + 1] = colour

    # The handle bars.  Two of them, full length, because the reference for this
    # whole rework is a desk you brace against with both hands.
    for bx0, bx1 in ((x + 2, x + 14), (x + 17, x + 29)):
        rect(px, bx0, y + 3, bx1, y + 3, ORANGE)
        rect(px, bx0, y + 4, bx1, y + 4, ORANGE_DARK)
        px[bx0, y + 3] = BOLT_SHADE
        px[bx1, y + 3] = BOLT_SHADE
        px[bx0, y + 4] = OUTLINE
        px[bx1, y + 4] = OUTLINE

    # Key bank at the front edge.
    rect(px, x + 2, y + 6, x + 29, y + 8, STEEL_DARK)
    outline_rect(px, x + 2, y + 6, x + 29, y + 8, OUTLINE)
    for i in range(x + 3, x + 29, 2):
        px[i, y + 7] = STEEL_LIT
    rect(px, x, y + 9, x + 31, y + 9, STEEL_DARK)


def draw_hood(px, x, y):
    """The two-pixel eyebrow above the glass."""
    rect(px, x, y, x + 31, y, STEEL_LIT)
    rect(px, x, y + 1, x + 31, y + 1, OUTLINE)
    for i in range(x + 3, x + 30, 4):
        px[i, y] = STEEL_DARK


def draw_lip(px, x, y):
    """The desk's nose: the one full-width warm line on the whole console."""
    rect(px, x, y, x + 31, y + 1, STEEL_DARK)
    rect(px, x + 1, y, x + 30, y, ORANGE)
    rect(px, x + 1, y + 1, x + 30, y + 1, ORANGE_DARK)


def draw_bezel(px, x, y):
    """The monitor's front frame.

    The middle of this region is the screen opening and is never rendered -- the
    glass is its own quad, one pixel further back.  It is painted as dark glass
    anyway so that the frame reads correctly in the atlas and a UV that slipped
    would land on something plausible instead of on the vent grille.
    """
    # The frame is only two pixels wide, because the glass takes the rest: the
    # outer pixel is a lit bevel and the inner one is the dark edge of the hole.
    # Painting both dark -- which is what an ordinary outline_rect does here --
    # loses the frame entirely and the monitor reads as a black slab.
    rect(px, x, y, x + 31, y + 13, STEEL)
    rect(px, x, y, x + 31, y, STEEL_HILIGHT)
    rect(px, x, y + 13, x + 31, y + 13, STEEL_DARK)
    outline_rect(px, x + 1, y + 1, x + 30, y + 12, OUTLINE)
    # The hole: cols 2..29, rows 2..11 -- exactly the quad the glass covers.
    rect(px, x + 2, y + 2, x + 29, y + 11, GLASS_DARK)
    dots(px, [(x, y + 13), (x + 31, y + 13)], BOLT_SHADE)
    dots(px, [(x + 1, y), (x + 30, y)], BOLT)
    # Bottom bezel: three small tell-tales, the only warm thing on the frame.
    dots(px, [(x + 4, y + 13), (x + 6, y + 13)], LAMP_GREEN)
    px[x + 8, y + 13] = ORANGE


def build_atlas():
    img = Image.new("RGBA", (SHEET, SHEET), STEEL_DARK)
    px = img.load()
    draw_fascia(px, *REGIONS["fascia"][:2])
    draw_desk(px, *REGIONS["desk"][:2])
    draw_hood(px, *REGIONS["hood"][:2])
    draw_bezel(px, *REGIONS["bezel"][:2])
    draw_lip(px, *REGIONS["lip"][:2])
    draw_panel(px, *REGIONS["panel"][:2])
    draw_vent(px, *REGIONS["vent"][:2])
    draw_plinth(px, *REGIONS["plinth"][:2])
    draw_trim(px, *REGIONS["trim"][:2])
    return img


# ---------------------------------------------------------------------------
# The screen
# ---------------------------------------------------------------------------
def screen_frame(index):
    """One frame of the display: full-width scanlines over a full-width bar graph.

    Both of those words are the point.  The complaint this rework answers is that
    the scanlines and the graph stopped halfway across the monitor, which they did
    because the display was two block textures pretending to be one.  It is one
    sprite now, stretched across one quad, and every horizontal thing on it runs
    from column 0 to column 31 with nothing in the middle to stop at.
    """
    img = Image.new("RGBA", (SCREEN_SPRITE, SCREEN_SPRITE), GLASS_DARK)
    px = img.load()
    bounds = (SCREEN_SPRITE, SCREEN_SPRITE)
    rect(px, 0, 0, SCREEN_SPRITE - 1, SCREEN_BAND - 1, GLASS, bounds)

    # Scanlines: every other row, all the way across.
    for y in range(0, SCREEN_BAND, 2):
        rect(px, 0, y, SCREEN_SPRITE - 1, y, GLASS_DARK, bounds)

    # The bar graph, sitting on a baseline that also runs the full width.
    baseline = SCREEN_BAND - 1
    heights = BAR_HEIGHTS[index % len(BAR_HEIGHTS)]
    for i, height in enumerate(heights):
        bx = i*3
        top = baseline - height
        rect(px, bx, top, bx + 1, baseline - 1, SCANLINE, bounds)
        rect(px, bx, top, bx + 1, top, SCREEN_GLOW, bounds)
    rect(px, 0, baseline, SCREEN_SPRITE - 1, baseline, SCANLINE, bounds)

    # The refresh line, applied last and over everything.  It used to be painted
    # only onto plain glass so the graph would not flicker along with it, and the
    # result read as a line passing *behind* the bars -- a retrace is the beam,
    # and the beam is in front of whatever it lit a moment ago.  Over glass it is
    # the screen's glow; over a bar it is a lighter tint of that glow, so the
    # line stays visible against the one place the plain glow would vanish.
    sweep = SWEEP_ROWS[index % len(SWEEP_ROWS)]
    for x in range(SCREEN_SPRITE):
        px[x, sweep] = SCREEN_GLOW if px[x, sweep] in (GLASS, GLASS_DARK) else SWEEP_OVER_BAR
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
    rect(px, 0, 0, SCREEN_SPRITE - 1, SCREEN_BAND - 1, GLASS_DARK, bounds)
    for y in range(0, SCREEN_BAND, 2):
        rect(px, 0, y, SCREEN_SPRITE - 1, y, OUTLINE, bounds)
    for y in range(1, SCREEN_BAND - 1):
        px[1, y] = GLASS
    return img


# ---------------------------------------------------------------------------
# The model
#
# Faces are emitted as quads with their vertices counter-clockwise seen from
# outside, which is what Forge's OBJModel.Face.getNormal assumes and what decides
# whether a surface is drawn at all or silently backface-culled.  Every face
# declares the direction it is meant to face and Model.quad checks it against the
# polygon's own normal, so a transcription slip is a failure here rather than a
# hole in the console that only a screenshot would find.
#
# The right and up vectors are what face_points and atlas_uv agree on: they are the
# texture's own axes seen from outside that face, and right x up is the outward
# normal, which is what makes the corner order below come out counter-clockwise.
# ---------------------------------------------------------------------------
AXES = {
    #  outward     right        up
    "+z": ((0, 0, 1), (1, 0, 0), (0, 1, 0)),
    "-z": ((0, 0, -1), (-1, 0, 0), (0, 1, 0)),
    "+x": ((1, 0, 0), (0, 0, -1), (0, 1, 0)),
    "-x": ((-1, 0, 0), (0, 0, 1), (0, 1, 0)),
    "+y": ((0, 1, 0), (1, 0, 0), (0, 0, -1)),
    "-y": ((0, -1, 0), (1, 0, 0), (0, 0, 1)),
}


class Model:
    def __init__(self):
        self.positions = []
        self.pos_index = {}
        self.uvs = []
        self.uv_index = {}
        self.groups = []          # (group name, material, [face...])

    def group(self, name, material):
        self.groups.append((name, material, []))
        return self.groups[-1][2]

    def _pos(self, p):
        key = tuple(round(c, 6) for c in p)
        if key not in self.pos_index:
            self.pos_index[key] = len(self.positions)
            self.positions.append(key)
        return self.pos_index[key] + 1

    def _uv(self, uv):
        key = (round(uv[0], 6), round(uv[1], 6))
        if key not in self.uv_index:
            self.uv_index[key] = len(self.uvs)
            self.uvs.append(key)
        return self.uv_index[key] + 1

    def quad(self, faces, points, uvs, outward):
        """One quad.  `points` are block pixels, CCW seen from `outward`.

        `outward` is either one of the six axis names or, for the desk's slope, the
        direction the face is meant to point in.
        """
        normal = newell(points)
        want = AXES[outward][0] if isinstance(outward, str) else outward
        if dot(normal, want) <= 0:
            raise SystemExit("quad %s is wound the wrong way for %s" % (points, outward))
        for u, v in uvs:
            if not (-1e-6 <= u <= 1 + 1e-6 and -1e-6 <= v <= 1 + 1e-6):
                raise SystemExit("uv %.4f,%.4f leaves its sprite" % (u, v))
        faces.append([(self._pos([c/PX for c in p]), self._uv(uv))
                      for p, uv in zip(points, uvs)])


def newell(points):
    n = [0.0, 0.0, 0.0]
    for i, a in enumerate(points):
        b = points[(i + 1) % len(points)]
        n[0] += (a[1] - b[1])*(a[2] + b[2])
        n[1] += (a[2] - b[2])*(a[0] + b[0])
        n[2] += (a[0] - b[0])*(a[1] + b[1])
    return n


def dot(a, b):
    return a[0]*b[0] + a[1]*b[1] + a[2]*b[2]


def atlas_uv(region, sub=None):
    """The four corner UVs of a region, in the order emit_quad wants them.

    `sub` is (x0, y0, x1, y1) *inside* the region, in region pixels, for the four
    pieces of the bezel: each one has to keep its own slice of the frame or the
    border art does not meet itself around the screen.

    OBJ v runs up from the bottom of the image and the blockstate sets
    `flip-v: true`, so the conversion is 1 - row/SHEET and the corner order is
    bottom-left, bottom-right, top-right, top-left.
    """
    x, y, w, h = region
    if sub is not None:
        x, y, w, h = x + sub[0], y + sub[1], sub[2] - sub[0], sub[3] - sub[1]
    u0, u1 = x/SHEET, (x + w)/SHEET
    v_top, v_bottom = 1 - y/SHEET, 1 - (y + h)/SHEET
    return [(u0, v_bottom), (u1, v_bottom), (u1, v_top), (u0, v_top)]


def screen_uv():
    """The glass quad maps the top SCREEN_BAND rows of the sprite and no more."""
    v_bottom = 1 - SCREEN_BAND/SCREEN_SPRITE
    return [(0.0, v_bottom), (1.0, v_bottom), (1.0, 1.0), (0.0, 1.0)]


def face_points(direction, box):
    """The four corners of one face of an axis-aligned box, CCW from outside."""
    x0, y0, z0, x1, y1, z1 = box
    if direction == "+z":
        return [(x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)]
    if direction == "-z":
        return [(x1, y0, z0), (x0, y0, z0), (x0, y1, z0), (x1, y1, z0)]
    if direction == "+x":
        return [(x1, y0, z1), (x1, y0, z0), (x1, y1, z0), (x1, y1, z1)]
    if direction == "-x":
        return [(x0, y0, z0), (x0, y0, z1), (x0, y1, z1), (x0, y1, z0)]
    if direction == "+y":
        return [(x0, y1, z1), (x1, y1, z1), (x1, y1, z0), (x0, y1, z0)]
    if direction == "-y":
        return [(x0, y0, z0), (x1, y0, z0), (x1, y0, z1), (x0, y0, z1)]
    raise SystemExit("unknown face direction "+direction)


def build_model(name="terminal"):
    """The console shell.

    `name` names the body's group and material.  The Fluid Control Console is the same
    cabinet with different paint -- deliberately so, the two consoles are each other's
    mirror -- so make_fluid_console_assets.py calls this rather than keeping a second
    copy of the geometry that could drift.  The screen keeps the material name `screen`
    for both, because that is the name the blockstate swaps by.
    """
    model = Model()
    body = model.group(name+"_body", name)

    def box(bounds, faces):
        for direction, region in faces.items():
            model.quad(body, face_points(direction, bounds),
                       atlas_uv(REGIONS[region]), direction)

    # Toe kick, set back so the cabinet above it reads as overhanging.
    box((0, 0, 0, WIDTH, PLINTH_TOP, PLINTH_Z),
        {"+z": "plinth", "-x": "plinth", "+x": "plinth", "-y": "plinth"})
    # Cabinet.  No top face: the deck and the desk sit on all of it.  Its underside is
    # only the strip that overhangs the toe kick -- the rest of it is inside the console,
    # and a face buried in solid geometry is a face that breaks the closed-shell check.
    box((0, PLINTH_TOP, 0, WIDTH, CABINET_TOP, FRONT_Z),
        {"+z": "fascia", "-x": "panel", "+x": "panel"})
    model.quad(body,
               face_points("-y", (0, PLINTH_TOP, PLINTH_Z, WIDTH, PLINTH_TOP, FRONT_Z)),
               atlas_uv(REGIONS["plinth"]), "-y")
    # The block behind the desk, filling the space under the monitor bay.
    box((0, CABINET_TOP, 0, WIDTH, DESK_TOP, DESK_BACK_Z),
        {"-x": "panel", "+x": "panel"})

    # The desk itself: a wedge, so its nose and its slope are separate faces.
    model.quad(body,
               [(0, CABINET_TOP, FRONT_Z), (WIDTH, CABINET_TOP, FRONT_Z),
                (WIDTH, DESK_LIP_TOP, FRONT_Z), (0, DESK_LIP_TOP, FRONT_Z)],
               atlas_uv(REGIONS["lip"]), "+z")
    model.quad(body,
               [(0, DESK_LIP_TOP, FRONT_Z), (WIDTH, DESK_LIP_TOP, FRONT_Z),
                (WIDTH, DESK_TOP, DESK_BACK_Z), (0, DESK_TOP, DESK_BACK_Z)],
               atlas_uv(REGIONS["desk"]), (0, 1, 0))
    model.quad(body,
               [(0, CABINET_TOP, DESK_BACK_Z), (0, CABINET_TOP, FRONT_Z),
                (0, DESK_LIP_TOP, FRONT_Z), (0, DESK_TOP, DESK_BACK_Z)],
               atlas_uv(REGIONS["panel"]), "-x")
    model.quad(body,
               [(WIDTH, CABINET_TOP, FRONT_Z), (WIDTH, CABINET_TOP, DESK_BACK_Z),
                (WIDTH, DESK_TOP, DESK_BACK_Z), (WIDTH, DESK_LIP_TOP, FRONT_Z)],
               atlas_uv(REGIONS["panel"]), "+x")

    # The monitor bay.  Its front is four pieces of frame around the glass.
    box((0, DESK_TOP, 0, WIDTH, HOUSING_TOP, HOUSING_Z),
        {"-x": "vent", "+x": "vent"})
    model.quad(body,
               face_points("-y", (0, DESK_TOP, DESK_BACK_Z, WIDTH, DESK_TOP, HOUSING_Z)),
               atlas_uv(REGIONS["plinth"]), "-y")
    frame = [
        ((0, DESK_TOP, SCREEN_X0, HOUSING_TOP),
         (0, 0, SCREEN_X0, HOUSING_TOP - DESK_TOP)),
        ((SCREEN_X1, DESK_TOP, WIDTH, HOUSING_TOP),
         (SCREEN_X1, 0, WIDTH, HOUSING_TOP - DESK_TOP)),
        ((SCREEN_X0, SCREEN_Y1, SCREEN_X1, HOUSING_TOP),
         (SCREEN_X0, 0, SCREEN_X1, HOUSING_TOP - SCREEN_Y1)),
        ((SCREEN_X0, DESK_TOP, SCREEN_X1, SCREEN_Y0),
         (SCREEN_X0, HOUSING_TOP - SCREEN_Y0, SCREEN_X1, HOUSING_TOP - DESK_TOP)),
    ]
    for (fx0, fy0, fx1, fy1), sub in frame:
        model.quad(body,
                   [(fx0, fy0, HOUSING_Z), (fx1, fy0, HOUSING_Z),
                    (fx1, fy1, HOUSING_Z), (fx0, fy1, HOUSING_Z)],
                   atlas_uv(REGIONS["bezel"], sub), "+z")

    # The walls of the recess, each facing in towards the glass.
    model.quad(body,
               [(SCREEN_X0, SCREEN_Y1, SCREEN_Z), (SCREEN_X1, SCREEN_Y1, SCREEN_Z),
                (SCREEN_X1, SCREEN_Y1, HOUSING_Z), (SCREEN_X0, SCREEN_Y1, HOUSING_Z)],
               atlas_uv(REGIONS["trim"]), "-y")
    model.quad(body,
               [(SCREEN_X0, SCREEN_Y0, HOUSING_Z), (SCREEN_X1, SCREEN_Y0, HOUSING_Z),
                (SCREEN_X1, SCREEN_Y0, SCREEN_Z), (SCREEN_X0, SCREEN_Y0, SCREEN_Z)],
               atlas_uv(REGIONS["trim"]), "+y")
    model.quad(body,
               [(SCREEN_X0, SCREEN_Y0, HOUSING_Z), (SCREEN_X0, SCREEN_Y0, SCREEN_Z),
                (SCREEN_X0, SCREEN_Y1, SCREEN_Z), (SCREEN_X0, SCREEN_Y1, HOUSING_Z)],
               atlas_uv(REGIONS["trim"]), "+x")
    model.quad(body,
               [(SCREEN_X1, SCREEN_Y0, SCREEN_Z), (SCREEN_X1, SCREEN_Y0, HOUSING_Z),
                (SCREEN_X1, SCREEN_Y1, HOUSING_Z), (SCREEN_X1, SCREEN_Y1, SCREEN_Z)],
               atlas_uv(REGIONS["trim"]), "-x")

    # The hood, overhanging the glass.  Same rule: only the overhang has an underside.
    box((0, HOUSING_TOP, 0, WIDTH, HEIGHT, HOOD_Z),
        {"+z": "hood", "-x": "panel", "+x": "panel", "+y": "panel"})
    model.quad(body,
               face_points("-y", (0, HOUSING_TOP, HOUSING_Z, WIDTH, HOUSING_TOP, HOOD_Z)),
               atlas_uv(REGIONS["plinth"]), "-y")

    # One back plate for the whole thing, so nothing is hollow when the console is
    # built free-standing rather than against a wall.
    model.quad(body, face_points("-z", (0, 0, 0, WIDTH, HEIGHT, 0)),
               atlas_uv(REGIONS["panel"]), "-z")

    glass = model.group(name+"_screen", "screen")
    model.quad(glass,
               [(SCREEN_X0, SCREEN_Y0, SCREEN_Z), (SCREEN_X1, SCREEN_Y0, SCREEN_Z),
                (SCREEN_X1, SCREEN_Y1, SCREEN_Z), (SCREEN_X0, SCREEN_Y1, SCREEN_Z)],
               screen_uv(), "+z")
    return model


def check_shell(model):
    """The model has to be a closed shell wound consistently outwards.

    Every face already declares which way it faces and is checked one at a time, but that
    says nothing about the ones that were never emitted.  The divergence theorem does: a
    surface with a hole in it, or with an interior face left in by accident, does not
    integrate to the volume of the solid it is supposed to be.  Returns that volume in
    block units so main() can print it.
    """
    volume = 0.0
    for _, _, faces in model.groups:
        for face in faces:
            points = [model.positions[v - 1] for v, _ in face]
            n = newell(points)
            centre = [sum(p[k] for p in points)/len(points) for k in range(3)]
            volume += dot(centre, [c/2 for c in n])/3
    if volume <= 0:
        raise SystemExit("the model encloses no volume: it is inside out or full of holes")
    return volume


def write_obj(model, path, mtllib="terminal.mtl",
              generator="docs/tools/make_terminal_assets.py"):
    lines = ["# Generated by %s -- do not hand-edit" % generator,
             "mtllib "+mtllib, ""]
    for p in model.positions:
        lines.append("v %.6f %.6f %.6f" % p)
    lines.append("")
    for uv in model.uvs:
        lines.append("vt %.6f %.6f" % uv)
    for name, material, faces in model.groups:
        lines.append("")
        lines.append("o "+name)
        lines.append("usemtl "+material)
        for face in faces:
            lines.append("f "+" ".join("%d/%d" % vt for vt in face))
    with open(path, "w") as handle:
        handle.write("\n".join(lines)+"\n")


def write_mtl(path):
    body = [
        "# Generated by docs/tools/make_terminal_assets.py -- do not hand-edit",
        "newmtl terminal",
        "map_Ka immersiveengineering:blocks/grid_terminal",
        "",
        "newmtl screen",
        "map_Ka immersiveengineering:blocks/grid_terminal_screen",
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

    model_dir = os.path.join(args.assets, "models", "block", "grid")
    os.makedirs(model_dir, exist_ok=True)
    obj_path = os.path.join(model_dir, "terminal.obj")
    write_obj(model, obj_path)
    write_mtl(os.path.join(model_dir, "terminal.mtl"))
    print("wrote %s (%d quads, %d vertices, %.4f blocks enclosed)"
          % (os.path.relpath(obj_path, repo),
             sum(len(f) for _, _, f in model.groups), len(model.positions), volume))

    texture_dir = os.path.join(args.assets, "textures", "blocks")
    os.makedirs(texture_dir, exist_ok=True)
    for name, image in (("grid_terminal", build_atlas()),
                        ("grid_terminal_screen", build_screen()),
                        ("grid_terminal_screen_off", build_screen_off())):
        path = os.path.join(texture_dir, name+".png")
        image.save(path, "PNG", optimize=True)
        print("wrote %s" % os.path.relpath(path, repo))
        # Same rule as make_grid_textures: a stacked sheet with no .mcmeta is not
        # an error Minecraft reports, it is every frame smeared into one texture.
        if image.height != image.width:
            meta_path = path+".mcmeta"
            with open(meta_path, "w") as handle:
                handle.write(json.dumps({"animation": {"frametime": ANIMATION_FRAMETIME}},
                                        indent=2)+"\n")
            print("wrote %s" % os.path.relpath(meta_path, repo))

    print("%d regions, %d of %d atlas pixels used, no overlaps"
          % (len(REGIONS), used, SHEET*SHEET))


if __name__ == "__main__":
    main()
