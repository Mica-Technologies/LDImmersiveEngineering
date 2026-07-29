#!/usr/bin/env python3
"""Regenerates the Hydraulic Crawler's entity sheet and its carried-item icon.

Generated for the same reason every other texture in this fork is: the machine's proportions
are still moving, and a hand-painted sheet is wrong the moment a box changes size with nobody
able to tell by looking.

**The UV layout here has to agree with ModelHydraulicCrawler.** A ModelBase box of size
(dx, dy, dz) unwraps into a cross occupying 2*(dz+dx) by (dz+dy) pixels from its declared
offset, and the offsets are declared in the Java. REGIONS below is that same list, and the
script asserts the regions do not overlap and fit the sheet -- which is the failure the layout
actually has, because nothing at runtime complains about two boxes sharing pixels. It just
paints the tracks with the cab's glass.

The sheet is deliberately uniform panelled steel per region rather than a painted-on detail
map. A box whose UVs are slightly off then lands on valid-looking metal instead of on somebody
else's texture, which is the difference between a cosmetic error and an obvious one.
"""

import argparse
import os

from PIL import Image

SHEET = 256

# Excavator yellow, and the greys of a machine that works for a living.
YELLOW = (214, 158, 32, 255)
YELLOW_LIT = (236, 182, 54, 255)
YELLOW_DARK = (152, 108, 18, 255)
STEEL = (104, 106, 110, 255)
STEEL_LIT = (132, 134, 138, 255)
STEEL_DARK = (68, 70, 74, 255)
OUTLINE = (34, 34, 38, 255)
GLASS = (74, 104, 120, 255)
GLASS_LIT = (108, 146, 164, 255)
HAZARD = (232, 226, 214, 255)

# name -> (u, v, dx, dy, dz, palette)
#
# Kept in the same order the Java declares its boxes, so the two read side by side. Both tracks
# share one region on purpose: they are the same object twice and there is nothing to gain from
# painting it twice.
REGIONS = [
    ("track", 0, 0, 12, 12, 56, "steel"),
    ("belly", 0, 70, 24, 6, 40, "steel"),
    ("slew_ring", 136, 0, 28, 3, 28, "steel"),
    ("engine_deck", 136, 34, 28, 14, 30, "yellow"),
    ("counterweight", 0, 118, 26, 16, 10, "hazard"),
    ("cab", 74, 118, 14, 18, 18, "cab"),
    ("exhaust", 140, 118, 4, 8, 4, "steel"),
    ("boom", 0, 158, 6, 7, 26, "yellow"),
    ("stick", 66, 158, 5, 6, 20, "yellow"),
    ("tool", 118, 158, 7, 7, 10, "steel"),
]


def footprint(dx, dy, dz):
    """The pixel box a ModelRenderer cube's unwrapped cross occupies from its offset."""
    return 2*(dz+dx), dz+dy


def check_layout():
    """Fail loudly on the one mistake this layout can make silently."""
    placed = []
    for name, u, v, dx, dy, dz, _ in REGIONS:
        w, h = footprint(dx, dy, dz)
        assert u+w <= SHEET and v+h <= SHEET, \
            "%s runs off the %dpx sheet: needs %d x %d at (%d, %d)" % (name, SHEET, w, h, u, v)
        for other, ou, ov, ow, oh in placed:
            if u < ou+ow and ou < u+w and v < ov+oh and ov < v+h:
                raise AssertionError(
                    "%s overlaps %s -- two boxes would share pixels, which nothing at runtime "
                    "reports and which paints one part with another's texture" % (name, other))
        placed.append((name, u, v, w, h))
    return placed


def rect(px, x0, y0, x1, y1, colour):
    for y in range(y0, y1+1):
        for x in range(x0, x1+1):
            px[x, y] = colour


def panel(px, u, v, w, h, base, lit, dark):
    """One region: flat paint, a lit top edge, a shaded bottom, and an outline.

    The outline is what makes every face of every box read as a discrete panel rather than as
    a continuous smear of colour, which at this scale is most of what sells it as machinery.
    """
    rect(px, u, v, u+w-1, v+h-1, base)
    # Horizontal streaks, so large flat faces are not dead flat.
    for y in range(v+2, v+h-2, 4):
        rect(px, u+1, y, u+w-2, y, dark)
    for y in range(v+1, v+h-2, 7):
        rect(px, u+1, y, u+w-2, y, lit)
    # Outline.
    rect(px, u, v, u+w-1, v, OUTLINE)
    rect(px, u, v+h-1, u+w-1, v+h-1, OUTLINE)
    rect(px, u, v, u, v+h-1, OUTLINE)
    rect(px, u+w-1, v, u+w-1, v+h-1, OUTLINE)


def hazard_panel(px, u, v, w, h):
    """The counterweight: diagonal warning stripes, because that is what a counterweight wears."""
    panel(px, u, v, w, h, YELLOW, YELLOW_LIT, YELLOW_DARK)
    for y in range(v+1, v+h-1):
        for x in range(u+1, u+w-1):
            if ((x-u)+(y-v))%8 < 4:
                px[x, y] = HAZARD
    rect(px, u, v, u+w-1, v, OUTLINE)
    rect(px, u, v+h-1, u+w-1, v+h-1, OUTLINE)
    rect(px, u, v, u, v+h-1, OUTLINE)
    rect(px, u+w-1, v, u+w-1, v+h-1, OUTLINE)


def cab_panel(px, u, v, w, h, dx, dy, dz):
    """The cab: steel frame with glazing on the four side faces.

    The unwrapped cross puts the four sides in a row across the middle band, below the top face
    and above the bottom -- so glazing that band, inset from the frame, glazes the sides and
    leaves the roof solid. Doing it by geometry rather than by hand is what keeps it right if
    the cab ever changes size.
    """
    panel(px, u, v, w, h, STEEL, STEEL_LIT, STEEL_DARK)
    band_top = v+dz+2
    band_bottom = v+dz+dy-3
    if band_bottom <= band_top:
        return
    for x in range(u+2, u+w-2):
        # Skip the seams where one side face meets the next, so the frame posts survive.
        offset = x-u
        if offset%(dz+dx) < 2 or offset%(dz+dx) > (dz+dx)-3:
            continue
        for y in range(band_top, band_bottom+1):
            px[x, y] = GLASS_LIT if y==band_top else GLASS


def build_sheet():
    img = Image.new("RGBA", (SHEET, SHEET), (0, 0, 0, 0))
    px = img.load()
    for name, u, v, dx, dy, dz, palette in REGIONS:
        w, h = footprint(dx, dy, dz)
        if palette=="yellow":
            panel(px, u, v, w, h, YELLOW, YELLOW_LIT, YELLOW_DARK)
        elif palette=="hazard":
            hazard_panel(px, u, v, w, h)
        elif palette=="cab":
            cab_panel(px, u, v, w, h, dx, dy, dz)
        else:
            panel(px, u, v, w, h, STEEL, STEEL_LIT, STEEL_DARK)
    return img


def build_item():
    """The carried item: a small side-on silhouette of the machine.

    Drawn rather than rendered from the model. An item icon baked off a 3D model at 16 pixels
    turns to mush, and the thing a player needs from this icon is only "that is the digger".
    """
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    # Tracks.
    rect(px, 1, 12, 12, 14, STEEL)
    rect(px, 1, 12, 12, 12, STEEL_LIT)
    rect(px, 1, 14, 12, 14, OUTLINE)
    # House and cab.
    rect(px, 4, 8, 11, 11, YELLOW)
    rect(px, 4, 8, 11, 8, YELLOW_LIT)
    rect(px, 9, 6, 11, 8, STEEL)
    px[10, 7] = GLASS_LIT
    # Counterweight.
    rect(px, 11, 9, 12, 11, HAZARD)
    # Boom and stick, reaching up and forward.
    for i in range(4):
        px[7-i, 7-i] = YELLOW
    for i in range(3):
        px[3, 4+i] = YELLOW
    # Bucket.
    rect(px, 1, 7, 3, 8, STEEL)
    px[1, 8] = OUTLINE
    return img


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    repo = os.path.dirname(os.path.dirname(here))
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets", default=os.path.join(
        repo, "src", "main", "resources", "assets", "immersiveengineering"))
    args = parser.parse_args()

    placed = check_layout()

    entity_dir = os.path.join(args.assets, "textures", "entity")
    os.makedirs(entity_dir, exist_ok=True)
    sheet_path = os.path.join(entity_dir, "hydraulic_crawler.png")
    build_sheet().save(sheet_path, "PNG", optimize=True)
    print("wrote %s" % os.path.relpath(sheet_path, repo))

    item_path = os.path.join(args.assets, "textures", "items", "hydraulic_crawler.png")
    build_item().save(item_path, "PNG", optimize=True)
    print("wrote %s" % os.path.relpath(item_path, repo))

    used = sum(w*h for _, _, _, w, h in placed)
    print("%d regions, %d of %d sheet pixels used, no overlaps"
          % (len(placed), used, SHEET*SHEET))


if __name__=="__main__":
    main()
