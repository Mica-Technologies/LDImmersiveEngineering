#!/usr/bin/env python3
"""
Regenerates the Hydraulic Crawler's model, its entity sheet and its carried-item icon.

This replaces `make_crawler_textures.py` and the hand-built `ModelBase` it painted for.
The complaint that started it was a playtester's, and it was fair: "the current digger
model is crude and rudimentary at least".  It was nine boxes.  A tracked excavator that
reads as one needs an undercarriage with wheels in it, a house with a cab you can see
through, a counterweight, and an arm made of more than three tapered sticks -- which is
more parts than a `ModelRenderer` hierarchy is worth writing by hand, and exactly what a
generator is for.

**Nothing here changes where anything pivots.**  The boom, stick, tool and slew pivots
are the same numbers the old model used and the same numbers `CrawlerGeometry` and
`CrawlerArm` do their arithmetic in; the arm's hitboxes are positioned from those, so
moving one would move the visible steel away from the boxes that decide what the machine
can hit.  The pivots below are duplicated in `ModelHydraulicCrawler` -- there is no way
for a Java class to read a Python table -- and `CrawlerAssetsTest` asserts the two agree.

**The model is authored in the model's own space, not in world space.**  That is sixteen
units to the block, *+Y downwards*, and -Z forwards along the arm, because that is what a
`ModelBase` is and the renderer's `scale(unit, -unit, -unit)` is what converts it.  Two
consequences worth stating: "+y" below means the underside of something, and the space is
still right-handed (that scale has determinant +1), so counter-clockwise-seen-from-outside
is meaningful and every face is checked against it.

**Every group is drawn under its own transform, so it is authored about its own pivot.**
A wheel's vertices are relative to its axle, the boom's to its pin, the house's to the
slew ring.  That is the same rule a `ModelRenderer` child follows and it is what lets the
renderer hand the model four angles and two track rolls and get a posed machine back.

Self-checks, all of them the sort of failure nothing at runtime reports:

  * every atlas region is inside the sheet and none overlaps another;
  * every face's winding agrees with the direction it says it faces;
  * every UV is inside the region it claims, and inside the sheet;
  * every solid is a closed, edge-manifold shell enclosing a positive volume, which is
    what catches a cylinder cap fanned the wrong way or a belt loop that failed to close;
  * the scrolling track belt's UVs stay inside their strip at every offset it can be
    drawn at;
  * no two co-rendered faces that point the same way lie in the same plane and overlap,
    which is the check that would have caught the z-fighting a playtester reported as
    "mottled patches" on the cowl, the deck seams, the track guards and the arm's pins;
  * every part is touching whatever it says holds it up, stands out of it by exactly what
    it claims to, and is not buried inside it -- which is the check that would have caught
    the same playtester's "floating parts of the model": a cab eleven units above its deck,
    an exhaust stack four units over the cowl, two ram rods hanging off the arm, a breaker
    barrel beside its housing, and bosses built against a tapered beam's widest section
    standing a unit off the steel everywhere else;
  * the glazing region is painted with partial alpha and every other region is fully
    opaque, which is the check that would have caught a windscreen nobody can see out
    of and a hole in the bodywork respectively.

**The glazing is its own group.**  `house_glass` is authored in the house's frame, exactly
as the rest of the cab is, so it slews with it; it is separate only so the renderer can
draw it last, blended, after every opaque group of the machine.  Blending an opaque group
is a no-op and blending in the middle of the machine would leave whatever had not been
drawn yet missing from behind the glass.

No real-world manufacturer's marks: the livery is generic construction yellow and the
only lettering is "LD".

Usage:  python docs/tools/make_crawler_obj.py [--assets <assets dir>]

Requires Pillow.  Writes:
    models/entity/hydraulic_crawler.obj, .mtl
    textures/entity/hydraulic_crawler.png
    textures/items/hydraulic_crawler.png
"""

import argparse
import math
import os
import random

from PIL import Image

# ---------------------------------------------------------------------------
# The palette.  Carried over from make_crawler_textures so the machine keeps the
# colour it had -- the complaint was about the shape, not the paint.
# ---------------------------------------------------------------------------
YELLOW = (214, 158, 32, 255)
YELLOW_LIT = (236, 182, 54, 255)
YELLOW_DARK = (152, 108, 18, 255)
YELLOW_SHADE = (108, 76, 12, 255)
STEEL = (104, 106, 110, 255)
STEEL_LIT = (132, 134, 138, 255)
STEEL_DARK = (68, 70, 74, 255)
IRON = (86, 88, 94, 255)
OUTLINE = (34, 34, 38, 255)
RUBBER = (46, 46, 50, 255)
RUBBER_LIT = (64, 64, 70, 255)

# The glazing, and the one colour on the sheet that is not opaque.
#
# **The alpha is the fix for a playtest report**: the cab windows were painted at full
# opacity, so a machine whose windows are real holes in real steel still could not be
# seen out of from the seat.  ~38% is enough tint to read as glass against the sky and
# little enough to drive by.  The seal rows are more opaque, which is what makes a pane
# read as glazed into a frame rather than as a coloured film.
#
# Everything that samples these colours *outside* the glass region -- the window painted
# on the door skin, the cab window on the item icon -- takes opaque() of them, because
# those are opaque steel wearing a picture of a window and a partial alpha there is a
# hole in the bodywork.
GLASS_ALPHA = 96
GLASS_SEAL_ALPHA = 180
GLASS = (122, 152, 170, GLASS_ALPHA)
GLASS_LIT = (168, 198, 214, GLASS_ALPHA)
GLASS_DARK = (86, 116, 134, GLASS_ALPHA)
GLASS_SEAL = (86, 116, 134, GLASS_SEAL_ALPHA)

HAZARD = (232, 226, 214, 255)
LAMP = (248, 226, 140, 255)
RUST = (128, 84, 46, 255)

SHEET = 256


def opaque(colour):
    """The same colour with nothing to see through.  See the note on GLASS_ALPHA."""
    return colour[0], colour[1], colour[2], 255


def shaded(colour, factor):
    """The same colour, that much lighter or darker, and just as opaque.

    **Every line drawn on painted steel is now a multiple of the colour it is drawn on**,
    rather than one of the four palette entries picked by hand.  The complaint this
    answers is "very harsh lines in the textures": the panels were seamed with
    YELLOW_SHADE and trimmed with OUTLINE, which against the livery is a contrast ratio of
    four to one and reads at any distance as a machine built out of planks.  A seam is a
    join in a sheet of steel and a join catches slightly less light than the sheet does;
    0.9 of it is a shade or two, which is all a seam ever is.
    """
    return (max(0, min(255, int(round(colour[0]*factor)))),
            max(0, min(255, int(round(colour[1]*factor)))),
            max(0, min(255, int(round(colour[2]*factor)))),
            colour[3])


#  How much light a seam, a bolt head and a scuff lose or gain against the sheet they are
#  on.  These three numbers are the whole of the painted detail's contrast, and they are
#  deliberately small: see shaded().
SEAM = 0.90
BOLT = 1.07
SCUFF = 0.94


# ---------------------------------------------------------------------------
# The atlas.
#
# Regions are (x, y, w, h) in the sheet, with y from the top.  Every face takes a
# *sub-rectangle* of a region the same size in pixels as the face is in model units,
# so the whole machine is textured at one texel to the unit and nothing is stretched.
# That is why the panel regions are painted as a repeating pattern rather than as a
# framed panel: a sub-rectangle may be cut from anywhere inside them, so a border
# would land in the middle of some other face.
#
# The largest face on the machine is the track frame at 38 units long, so 64 is the
# smallest round region that never has to stretch.
# ---------------------------------------------------------------------------
REGIONS = {
    "yellow": (0, 0, 64, 64),        # the livery: everything painted
    "yellow_dark": (64, 0, 64, 64),  # the same in shadow: undersides, inner faces
    "steel": (128, 0, 64, 64),       # unpainted structure
    "steel_dark": (192, 0, 64, 64),  # and its undersides

    "hazard": (0, 64, 64, 32),       # counterweight stripes
    "plate": (0, 96, 64, 32),        # machined steel: rams, bosses, pins
    "vent": (64, 64, 32, 32),        # engine cowl louvres
    "grille": (96, 64, 32, 32),      # the cowl's roof grating
    "glass": (128, 64, 32, 32),      # cab glazing
    "hub": (160, 64, 32, 32),        # a wheel seen face on
    "belt": (192, 64, 16, 64),       # the track's tread strip -- see BELT_PERIOD
    "rim": (208, 64, 32, 16),        # a wheel's rolling surface
    "nameplate": (208, 80, 48, 16),  # "LD", which is the only lettering on the machine
    "edge": (64, 96, 64, 32),        # wear steel: cutting edges, teeth, the chisel
    "walkway": (128, 96, 64, 32),    # chequer plate: the deck you stand on
    "lamp": (208, 96, 32, 16),       # work lights
    "trim": (208, 112, 32, 16),      # dark interior walls, seat, recesses

    "cowl": (0, 128, 64, 64),        # the engine cowl's flanks, with hatch and louvres
    "cab_side": (64, 128, 64, 64),   # the cab's outer skin: door, handle, glazing bar
    "counterweight": (128, 128, 64, 64),
    "bucket": (192, 128, 64, 64),    # the bucket's back, ribbed

    # A pin seen end on, and the reason it is not simply a crop of "plate".
    #
    # Every cylinder's cap is mapped *radially about the centre of its region*, so whatever
    # is painted at the middle of the region lands at the middle of every disc.  The middle
    # of "plate" is where two of its panel seams cross, so every pin on the machine wore a
    # dark cross through its face -- which is what the playtester saw as "dark cross-hatched
    # discs" hovering off the arm.  A region painted in rings puts a machined pin face there
    # instead, at any radius from the tool pins' 2.4 to the slew ring's 13.
    "boss": (0, 192, 32, 32),
}

# The belt strip repeats every BELT_PERIOD pixels down its length, and every quad of
# the belt is drawn from the *top* BELT_SEG_MAX pixels of it with a scroll offset of up
# to one period added.  So the strip has to be at least one period plus one segment
# tall, and check_belt() says so.
BELT_PERIOD = 8

# ---------------------------------------------------------------------------
# Geometry, in model units.  See the module docstring for the axes.
# ---------------------------------------------------------------------------

# How far a detail stands proud of -- or sinks into -- the surface it is attached to.
#
# **This is the fix for the z-fighting a playtester reported**: mottled patches that
# flickered as the camera moved, on the cowl flank, along the deck seams, on the track
# guards and at the arm's pins.  Every one of them was two faces that point the same way
# lying in exactly the same plane: a handrail flush with the posts holding it up, a
# mudguard exactly as wide as the belt under it, a stick eye exactly the radius of the
# boom pin it turns on.  Nothing decides which of two coincident faces wins; the depth
# buffer's rounding does, per pixel, per frame, and it changes its mind as the machine
# moves.  See check_coplanar, which now refuses to write a model with any of it left.
#
# 0.1 of a unit, and both bounds of that are worth stating.  Downwards: the depth buffer
# resolves about z**2/8e5 blocks at a distance of z blocks, so at the 64 blocks an entity
# is still drawn at it separates about 0.005 of a block -- 0.08 of a unit.  A tenth clears
# that everywhere the machine is visible at all; the 0.05 that would do at arm's length
# would still shimmer across a build site.  Upwards: a unit is one texel of the sheet, so
# a tenth of one is a tenth of a texel.  There is no distance at which that reads as a
# panel floating off the bodywork -- it is well under the width of the seam lines painted
# on the panels either side of it.
RELIEF = 0.1

# How far a pin's boss stands out of the beam it is pinned through.
#
# **This is the other half of the same playtest report**: "floating parts of the model".
# The bosses were sized against the beams' *nominal* half width -- the widest a tapered
# beam ever gets -- so on a beam that is 3.5 wide at its head and 4.5 at its foot a boss
# built for 4.5 stands a full unit out of the steel wherever the steel is narrower, and a
# disc a unit off the surface it belongs to does not read as a boss.  It reads as a disc
# hovering beside the arm.  Every boss now asks the beam how wide it is *where the boss
# actually is* -- see Beam.half_width -- and stands exactly this far out of that.
#
# Half a unit, which is the smallest step that still reads as a raised face at ten blocks
# and the largest that never reads as a gap.  check_anchoring holds every one of them to it.
BOSS_PROUD = 0.5

# ---------------------------------------------------------------------------
# THE PIVOTS.  Duplicated in ModelHydraulicCrawler, compared by CrawlerAssetsTest.
# ---------------------------------------------------------------------------
SLEW_HEIGHT = -14.0                     # the ring the house turns on
BOOM_PIVOT = (-5.0, -10.0, -16.0)       # in the house's frame
BOOM_LENGTH = 26.0                      # = CrawlerGeometry.BOOM_LENGTH
STICK_LENGTH = 20.0                     # = CrawlerGeometry.STICK_LENGTH
TOOL_LENGTH = 10.0                      # = CrawlerGeometry.TOOL_LENGTH

# The undercarriage.  The tracks are 12 wide each and their outer edges are 48 apart,
# which is CrawlerGeometry.WIDTH; the machine's collision box is that number.
TRACK_X0 = 12.0
TRACK_X1 = 24.0
WHEEL_X = 18.0                          # the centreline of a track
TRACK_TOP = -12.0                       # 12 units of track, as the old model had
WHEEL_Z = 19.0                          # sprocket and idler centres
BELT_THICKNESS = 2.0

# (z, y, radius, half-width).  The order matters twice over: the group names carry the
# index, and ModelHydraulicCrawler.WHEELS is the same table in the same order with the
# half-width dropped, which CrawlerAssetsTest compares row by row.  Written out in full
# rather than in terms of WHEEL_Z so that both copies are plain numbers a test can read.
WHEELS = [
    (19.0, -6.0, 4.0, 4.0),             # 0: drive sprocket, at the back
    (-19.0, -6.0, 4.0, 4.0),            # 1: idler, at the front
    (-11.0, -5.0, 3.0, 3.5),            # 2..5: road wheels
    (-4.0, -5.0, 3.0, 3.5),
    (4.0, -5.0, 3.0, 3.5),
    (11.0, -5.0, 3.0, 3.5),
]
assert WHEELS[0][0]==WHEEL_Z and WHEELS[1][0]==-WHEEL_Z, \
    "the sprocket and the idler are not where the belt is wrapped round them"

BELT_RUN_SEGMENTS = 8
BELT_ARC_SEGMENTS = 5

# The house, in its own frame: y 0 is the slew ring and up is negative.
DECK_TOP = -14.0                        # 28 units above the ground, as before
CAB_TOP = -32.0                         # 46, which is CrawlerGeometry.HEIGHT
CAB_X0, CAB_X1 = 1.0, 15.0              # CrawlerGeometry.CAB_MIN_X/MAX_X read off this
CAB_Z0, CAB_Z1 = -14.0, 4.0
CAB_FLOOR = -16.0                       # the seat's floor: CAB_HEIGHT is 28 = -14 + -2

MTL = "crawler"


# ---------------------------------------------------------------------------
# The mesh
# ---------------------------------------------------------------------------
class Mesh:
    """
    An OBJ under construction: one shared vertex table, one UV table, faces per group.

    Groups are what the renderer transforms independently, so the group list is a
    contract with `ModelHydraulicCrawler` rather than an organisational nicety.
    """

    def __init__(self):
        self.positions = []
        self.pos_index = {}
        self.uvs = []
        self.uv_index = {}
        self.groups = []            # (name, [face...]) in file order
        self.by_name = {}
        #  The same faces again as plain points, per group, for check_coplanar: that check
        #  is about where two faces are in space, and the file's shared vertex table is the
        #  one form of the mesh that has thrown that away.
        self.polygons = {}          # name -> [(what, [point...])...]
        self.group_of = {}          # id(face list) -> name
        #  Every closed piece, kept whole: check_anchoring asks which solid a face belongs
        #  to and where that solid's surface is, and neither question can be answered from
        #  a pile of faces.
        self.solids = []            # (group, what, [polygon...]) in build order
        self.quads = 0

    def group(self, name):
        if name in self.by_name:
            raise SystemExit("group %s declared twice" % name)
        faces = []
        self.groups.append((name, faces))
        self.by_name[name] = faces
        self.polygons[name] = []
        self.group_of[id(faces)] = name
        return faces

    def _pos(self, point):
        key = tuple(round(c, 5) for c in point)
        if key not in self.pos_index:
            self.pos_index[key] = len(self.positions)
            self.positions.append(key)
        return self.pos_index[key]+1

    def _uv(self, uv):
        key = (round(uv[0], 6), round(uv[1], 6))
        if key not in self.uv_index:
            self.uv_index[key] = len(self.uvs)
            self.uvs.append(key)
        return self.uv_index[key]+1

    def add(self, faces, points, uvs, what="?"):
        faces.append([(self._pos(p), self._uv(uv)) for p, uv in zip(points, uvs)])
        self.polygons[self.group_of[id(faces)]].append((what, [tuple(p) for p in points]))
        self.quads += 1


def newell(points):
    """The polygon's own normal, from its winding."""
    n = [0.0, 0.0, 0.0]
    for i, a in enumerate(points):
        b = points[(i+1)%len(points)]
        n[0] += (a[1]-b[1])*(a[2]+b[2])
        n[1] += (a[2]-b[2])*(a[0]+b[0])
        n[2] += (a[0]-b[0])*(a[1]+b[1])
    return n


def dot(a, b):
    return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]


class Solid:
    """
    One closed piece of the machine, checked as it is finished.

    **Every primitive below is built through this, and the check is the reason.**  A
    face wound inside out is invisible from the side you meant to look at it from and
    perfectly solid from the side you did not; a cylinder cap fanned across the wrong
    diagonal is a wheel with a dent in it; a belt loop that failed to close is a hole
    you only find in a screenshot.  None of those are errors anything reports.

    Two checks together catch all of it.  Edge-manifoldness -- every edge used exactly
    twice, once in each direction -- says the surface is closed and consistently wound.
    The divergence theorem says which way: a shell wound outwards encloses a positive
    volume and the same shell reversed encloses the negative of it.
    """

    def __init__(self, mesh, faces, what):
        self.mesh = mesh
        self.faces = faces
        self.what = what
        self.polygons = []

    def quad(self, points, uvs, want, region=None):
        if len(points)!=4:
            raise SystemExit("%s: a face with %d corners; this loader reads quads only"
                             % (self.what, len(points)))
        normal = newell(points)
        if dot(normal, want) <= 0:
            raise SystemExit("%s: a face at %s is wound the wrong way for %s"
                             % (self.what, points[0], want))
        for u, v in uvs:
            if region is not None:
                rx, ry, rw, rh = REGIONS[region]
                if not (rx-1e-6 <= u <= rx+rw+1e-6 and ry-1e-6 <= v <= ry+rh+1e-6):
                    raise SystemExit("%s: uv %.2f,%.2f leaves region %s"
                                     % (self.what, u, v, region))
            if not (-1e-6 <= u <= SHEET+1e-6 and -1e-6 <= v <= SHEET+1e-6):
                raise SystemExit("%s: uv %.2f,%.2f leaves the sheet" % (self.what, u, v))
        self.polygons.append(points)
        self.mesh.add(self.faces, points, [obj_uv(uv) for uv in uvs], self.what)

    def finish(self):
        edges = {}
        volume = 0.0
        for points in self.polygons:
            for i, a in enumerate(points):
                b = points[(i+1)%len(points)]
                key = (tuple(round(c, 4) for c in a), tuple(round(c, 4) for c in b))
                if key in edges:
                    raise SystemExit("%s: the edge %s -> %s is used twice in the same "
                                     "direction, so two faces there are wound against "
                                     "each other" % (self.what, key[0], key[1]))
                edges[key] = True
            n = newell(points)
            centre = [sum(p[k] for p in points)/len(points) for k in range(3)]
            volume += dot(centre, [c/2 for c in n])/3
        for (a, b) in edges:
            if (b, a) not in edges:
                raise SystemExit("%s: the edge %s -> %s has nothing on its other side, "
                                 "so the shell is open there" % (self.what, a, b))
        if volume <= 0:
            raise SystemExit("%s: encloses %.3f cubic units -- it is inside out"
                             % (self.what, volume))
        self.mesh.solids.append((self.mesh.group_of[id(self.faces)], self.what,
                                 [list(points) for points in self.polygons]))
        return volume


def obj_uv(uv):
    """Sheet pixels, v from the top, to OBJ's own coordinates, v from the bottom."""
    return uv[0]/SHEET, 1.0-uv[1]/SHEET


# ---------------------------------------------------------------------------
# UVs
#
# A face asks for a sub-rectangle of a region the same size it is, and gets the four
# corners in the order every face_points() below returns its corners: the two nearest
# the top of the world first, left to right.  That single ordering is what keeps side
# faces upright without a per-face decision -- see the module docstring on +Y being
# downwards.
# ---------------------------------------------------------------------------
def uv_rect(region, w, h, ox=0.0, oy=0.0):
    rx, ry, rw, rh = REGIONS[region]
    if w > rw+1e-6 or h > rh+1e-6:
        raise SystemExit("a %gx%g face does not fit region %s (%gx%g)"
                         % (w, h, region, rw, rh))
    ox = min(ox, rw-w)
    oy = min(oy, rh-h)
    u0, v0 = rx+ox, ry+oy
    return [(u0, v0), (u0+w, v0), (u0+w, v0+h), (u0, v0+h)]


def yrect(half_width, ya, yb):
    """A cross-section, with its y edges put in order.

    Worth a function: a rectangle written with its edges the wrong way round is a face
    wound inside out, which the winding check catches but only after saying so about a
    corner nobody can place. Mirrored parts -- the two grapple jaws -- are where it
    happens, because the sign that mirrors them also swaps which edge is which.
    """
    return -half_width, min(ya, yb), half_width, max(ya, yb)


def face_points(direction, box):
    """The four corners of one face of an axis-aligned box, counter-clockwise outside."""
    x0, y0, z0, x1, y1, z1 = box
    if direction=="+z":
        return [(x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)]
    if direction=="-z":
        return [(x1, y0, z0), (x0, y0, z0), (x0, y1, z0), (x1, y1, z0)]
    if direction=="+x":
        return [(x1, y0, z1), (x1, y0, z0), (x1, y1, z0), (x1, y1, z1)]
    if direction=="-x":
        return [(x0, y0, z0), (x0, y0, z1), (x0, y1, z1), (x0, y1, z0)]
    if direction=="+y":
        return [(x0, y1, z1), (x1, y1, z1), (x1, y1, z0), (x0, y1, z0)]
    if direction=="-y":
        return [(x0, y0, z0), (x1, y0, z0), (x1, y0, z1), (x0, y0, z1)]
    raise SystemExit("unknown face direction "+direction)


AXES = {"+x": (1, 0, 0), "-x": (-1, 0, 0), "+y": (0, 1, 0), "-y": (0, -1, 0),
        "+z": (0, 0, 1), "-z": (0, 0, -1)}

# Which two of the box's dimensions a face is measured in, and in which order.
FACE_SIZE = {"+z": (0, 1), "-z": (0, 1), "+x": (2, 1), "-x": (2, 1),
             "+y": (0, 2), "-y": (0, 2)}


def sides(region, top=None, bottom=None, ends=None):
    """A face-to-region map: the flanks, and optionally a different lid and floor."""
    faces = {"+x": region, "-x": region, "+z": region, "-z": region}
    if ends is not None:
        faces["+z"] = faces["-z"] = ends
    #  -y is the top of the world here, +y its underside.  See the docstring.
    faces["-y"] = top if top is not None else region
    faces["+y"] = bottom if bottom is not None else region
    return faces


# ---------------------------------------------------------------------------
# Primitives
# ---------------------------------------------------------------------------
def add_box(mesh, faces, what, box, regions, uv_origin=(0.0, 0.0)):
    return add_frustum(mesh, faces, what, box[2], box[5],
                       (box[0], box[1], box[3], box[4]),
                       (box[0], box[1], box[3], box[4]), regions, uv_origin)


def add_frustum(mesh, faces, what, z0, z1, rect0, rect1, regions, uv_origin=(0.0, 0.0)):
    """
    A box whose cross-section at z0 may differ from the one at z1.

    Which is what every tapered thing on the machine is: the boom, the stick, the
    counterweight's shoulder, the bucket's shell.  Both cross-sections are rectangles
    with edges parallel to the axes, so every side face is still planar -- two parallel
    segments always are -- and the winding check would say so if they were not.
    """
    if isinstance(regions, str):
        regions = sides(regions)
    solid = Solid(mesh, faces, what)
    a0, b0, a1, b1 = rect0            # x0, y0, x1, y1 at z0
    c0, d0, c1, d1 = rect1            # and at z1
    corners = {
        "-z": [(a1, b0, z0), (a0, b0, z0), (a0, b1, z0), (a1, b1, z0)],
        "+z": [(c0, d0, z1), (c1, d0, z1), (c1, d1, z1), (c0, d1, z1)],
        "+x": [(c1, d0, z1), (a1, b0, z0), (a1, b1, z0), (c1, d1, z1)],
        "-x": [(a0, b0, z0), (c0, d0, z1), (c0, d1, z1), (a0, b1, z0)],
        "+y": [(c0, d1, z1), (c1, d1, z1), (a1, b1, z0), (a0, b1, z0)],
        "-y": [(a0, b0, z0), (a1, b0, z0), (c1, d0, z1), (c0, d0, z1)],
    }
    span = (max(a1, c1)-min(a0, c0), max(b1, d1)-min(b0, d0), z1-z0)
    for direction, region in regions.items():
        if region is None:
            continue
        wi, hi = FACE_SIZE[direction]
        solid.quad(corners[direction],
                   uv_rect(region, span[wi], span[hi], *uv_origin),
                   AXES[direction], region)
    return solid.finish()


#  Which two coordinates a prism's cross-section is drawn in, for each axis it can run
#  along, in the order that keeps the frame right-handed -- so "counter-clockwise seen
#  from outside" means the same thing whichever way the prism points and the winding check
#  is as sharp on a vertical exhaust stack as it is on a wheel.
CROSS_SECTION = {"x": (1, 2), "y": (2, 0), "z": (0, 1)}


def add_cylinder(mesh, faces, what, x0, x1, cz, cy, radius, count, side, cap,
                 twist=0.0):
    """A prism about the X axis: a wheel, a pin boss, a rotator.  See add_tube."""
    return add_tube(mesh, faces, what, "x", x0, x1, cy, cz, radius, count, side, cap,
                    twist)


def add_tube(mesh, faces, what, axis, a0, a1, cu, cv, radius, count, side, cap,
             twist=0.0):
    """
    A prism about any one of the three axes, with its cross-section at (cu, cv).

    **The axis is a parameter because three things on this machine are not wheels.**  The
    rams along the arm, the exhaust stack out of the cowl and the breaker's barrel all run
    somewhere other than across the machine, and with only an X-axis primitive to hand they
    had been built as short fat drums lying on their sides -- which is what a playtester saw
    as discs floating beside the arm, and in the breaker's case as a barrel hanging in the
    air a body's length from the tool it belongs to.

    (cu, cv) are the centre in the two coordinates the prism does *not* run along, named in
    the order CROSS_SECTION gives them, which for the X axis is (y, z).

    The caps are fanned into quads rather than left as one many-sided face because the
    renderer draws quads and nothing else -- one format, one loop, no triangle path to
    get wrong.  An even facet count is what makes that fan come out exact.
    """
    if count%2:
        raise SystemExit("%s: %d facets; the cap fan needs an even count" % (what, count))
    if axis not in CROSS_SECTION:
        raise SystemExit("%s: %s is not an axis a prism can run along" % (what, axis))
    ai = "xyz".index(axis)
    ui, vi = CROSS_SECTION[axis]

    def point(along, u, v):
        p = [0.0, 0.0, 0.0]
        p[ai], p[ui], p[vi] = along, u, v
        return tuple(p)

    def outward(u, v):
        return point(0.0, u-cu, v-cv)

    solid = Solid(mesh, faces, what)
    ring = []
    for i in range(count):
        angle = twist+2*math.pi*i/count
        ring.append((cu+radius*math.cos(angle), cv+radius*math.sin(angle)))
    arc = 2*math.pi*radius/count
    for i in range(count):
        (u0, v0), (u1, v1) = ring[i], ring[(i+1)%count]
        solid.quad([point(a0, u0, v0), point(a0, u1, v1),
                    point(a1, u1, v1), point(a1, u0, v0)],
                   uv_rect(side, a1-a0, arc),
                   outward((u0+u1)/2, (v0+v1)/2), side)
    # The caps, radially mapped so the hub's art lands square on the wheel.
    rx, ry, rw, rh = REGIONS[cap]
    hub = (rx+rw/2, ry+rh/2)

    def cap_uv(u, v):
        return hub[0]+(v-cv), hub[1]+(u-cu)

    ahead = point(1.0, 0.0, 0.0)
    behind = point(-1.0, 0.0, 0.0)
    for i in range(0, count-2, 2):
        a, b, c, d = ring[0], ring[i+1], ring[i+2], ring[(i+3)%count]
        solid.quad([point(a1, *a), point(a1, *b), point(a1, *c), point(a1, *d)],
                   [cap_uv(*p) for p in (a, b, c, d)], ahead, cap)
        solid.quad([point(a0, *d), point(a0, *c), point(a0, *b), point(a0, *a)],
                   [cap_uv(*p) for p in (d, c, b, a)], behind, cap)
    return solid.finish()


class Beam:
    """
    A tapered beam, and the question every detail bolted to one has to ask it.

    **This is the anchoring fix.**  The boom and the stick are frusta: 8 units across at
    the head and 10 at the foot for the boom, 5.5 and 7 for the stick.  Everything bolted
    to them -- the pins, the hose run -- was placed against the *nominal* half width, the
    number in the widest cross-section, because that is the number written in the call that
    builds the beam.  Anywhere the beam is narrower than that, and that is everywhere but
    one end of it, the detail hangs that far off the steel.

    So a detail asks for the surface *over the span it actually covers* and puts itself
    that far out, and check_anchoring measures the answer back off the finished mesh.  The
    span matters: a boss is a disc, it covers a stretch of the beam rather than a station
    on it, and the taper means the widest point of that stretch is the one it has to clear.
    """

    def __init__(self, z0, z1, rect0, rect1):
        self.z0, self.z1 = z0, z1
        self.rect0, self.rect1 = rect0, rect1

    def rect(self, z):
        """The cross-section at z, as (x0, y0, x1, y1), clamped to the beam's own ends."""
        t = (min(max(z, self.z0), self.z1)-self.z0)/(self.z1-self.z0)
        return tuple(a+(b-a)*t for a, b in zip(self.rect0, self.rect1))

    def _span(self, za, zb, index, sign):
        if zb is None:
            zb = za
        za, zb = min(za, zb), max(za, zb)
        if zb < self.z0 or za > self.z1:
            raise SystemExit("a detail over z %g..%g is not on a beam that runs %g..%g"
                             % (za, zb, self.z0, self.z1))
        #  Linear in z, so the extreme over a span is at one end of it.
        return max(sign*self.rect(z)[index] for z in (za, zb))

    def half_width(self, za, zb=None):
        """The half width of the widest cross-section over a span: what a boss must clear."""
        return self._span(za, zb, 2, 1)

    def lies_along(self, above, z0, z1, radius, sink):
        """Where the axis of a ram lying along this beam goes.

        A ram is a straight round thing on a surface that tapers away under it, so it can
        only be bedded into the *shallowest* end of what it lies on; sunk to there, it is
        sunk further everywhere else and there is no length of it standing off the steel.
        """
        edges = [self.rect(z)[1 if above else 3] for z in (z0, z1)]
        return max(edges)+sink-radius if above else min(edges)-sink+radius

    def build(self, mesh, faces, what, regions):
        return add_frustum(mesh, faces, what, self.z0, self.z1, self.rect0, self.rect1,
                           regions)


#  The two beams of the arm.  Their cross-sections are the geometry; BOOM_LENGTH and
#  STICK_LENGTH are the kinematics, and the two agree here and nowhere else.
BOOM = Beam(-BOOM_LENGTH, 2, (-3.5, -4.0, 3.5, 4.0), (-4.5, -5.0, 4.5, 5.0))
STICK = Beam(-STICK_LENGTH, 2, (-2.75, -3.0, 2.75, 3.0), (-3.5, -4.0, 3.5, 4.0))


# ---------------------------------------------------------------------------
# The track belt
#
# A closed loop of tread wrapped round the sprocket and the idler.  It is one shell,
# and the manifold check is what proves the loop actually closed rather than nearly.
#
# **The belt is the group whose UVs scroll**, which is what makes the tracks appear to
# drive the machine rather than slide under it.  Every segment maps the same slice from
# the top of the belt strip, and the renderer adds one offset to the whole group; since
# the strip repeats every BELT_PERIOD pixels, an offset wrapped into a period is
# indistinguishable from the belt having travelled any multiple of one.  Doing it that
# way rather than per-vertex is what keeps the wrap from tearing a quad in half.
# ---------------------------------------------------------------------------
def belt_path():
    """The outer surface of the belt as (z, y, outward z, outward y), once round.

    Traversed so that the outward normal of a segment is always (0, dz, -dy): along the
    bottom towards the back, up over the sprocket, forward along the top, down over the
    idler.  Which is also the direction the tread travels when the machine drives
    forwards, so the scroll offset is the distance driven and nothing has to be negated.
    """
    radius = WHEELS[0][2]+BELT_THICKNESS
    bottom = -WHEELS[0][1]-radius       # the outer surface of the bottom run, at y = 0
    path = []
    for i in range(BELT_RUN_SEGMENTS):
        t = i/BELT_RUN_SEGMENTS
        path.append((-WHEEL_Z+2*WHEEL_Z*t, WHEELS[0][1]+radius, 0.0, 1.0))
    for i in range(BELT_ARC_SEGMENTS):
        phi = math.pi*i/BELT_ARC_SEGMENTS
        nz, ny = math.sin(phi), math.cos(phi)
        path.append((WHEEL_Z+radius*nz, WHEELS[0][1]+radius*ny, nz, ny))
    for i in range(BELT_RUN_SEGMENTS):
        t = i/BELT_RUN_SEGMENTS
        path.append((WHEEL_Z-2*WHEEL_Z*t, WHEELS[0][1]-radius, 0.0, -1.0))
    for i in range(BELT_ARC_SEGMENTS):
        psi = math.pi*i/BELT_ARC_SEGMENTS
        nz, ny = -math.sin(psi), -math.cos(psi)
        path.append((-WHEEL_Z+radius*nz, WHEELS[0][1]+radius*ny, nz, ny))
    assert abs(bottom) < 1e-9, "the bottom of the belt is not on the ground"
    return path


def add_belt(mesh, faces, what, x0, x1):
    solid = Solid(mesh, faces, what)
    path = belt_path()
    rx, ry, _, _ = REGIONS["belt"]
    width = x1-x0
    tread_u = (rx, rx+width)                 # the cleated columns
    edge_u = (rx+width, rx+width+BELT_THICKNESS)   # plain rubber, for the flanks
    longest = 0.0
    for i, point in enumerate(path):
        z0, y0, nz0, ny0 = point
        z1, y1, nz1, ny1 = path[(i+1)%len(path)]
        length = math.hypot(z1-z0, y1-y0)
        longest = max(longest, length)
        ao = (z0, y0)
        bo = (z1, y1)
        ai = (z0-BELT_THICKNESS*nz0, y0-BELT_THICKNESS*ny0)
        bi = (z1-BELT_THICKNESS*nz1, y1-BELT_THICKNESS*ny1)
        #  The rule the traversal was chosen for: see belt_path.
        outward = (0, z1-z0, -(y1-y0))
        # The tread itself.
        solid.quad([(x0, ao[1], ao[0]), (x0, bo[1], bo[0]),
                    (x1, bo[1], bo[0]), (x1, ao[1], ao[0])],
                   [(tread_u[0], ry), (tread_u[0], ry+length),
                    (tread_u[1], ry+length), (tread_u[1], ry)],
                   outward, "belt")
        # Its back, facing into the frame.
        solid.quad([(x1, ai[1], ai[0]), (x1, bi[1], bi[0]),
                    (x0, bi[1], bi[0]), (x0, ai[1], ai[0])],
                   [(edge_u[1], ry), (edge_u[1], ry+length),
                    (edge_u[0], ry+length), (edge_u[0], ry)],
                   (-outward[0], -outward[1], -outward[2]), "belt")
        # The two edges of the belt.
        solid.quad([(x1, ao[1], ao[0]), (x1, bo[1], bo[0]),
                    (x1, bi[1], bi[0]), (x1, ai[1], ai[0])],
                   [(edge_u[0], ry), (edge_u[0], ry+length),
                    (edge_u[1], ry+length), (edge_u[1], ry)],
                   (1, 0, 0), "belt")
        solid.quad([(x0, ai[1], ai[0]), (x0, bi[1], bi[0]),
                    (x0, bo[1], bo[0]), (x0, ao[1], ao[0])],
                   [(edge_u[1], ry), (edge_u[1], ry+length),
                    (edge_u[0], ry+length), (edge_u[0], ry)],
                   (-1, 0, 0), "belt")
    check_belt(longest)
    return solid.finish()


def check_belt(longest):
    """The scroll must never run a quad off the bottom of the strip.

    A UV that leaves its region does not fail: it samples whatever is painted next
    door, which here would be the tracks briefly wearing the cab's glazing every time
    the machine moved a few units.
    """
    _, _, rw, rh = REGIONS["belt"]
    if BELT_PERIOD+longest > rh+1e-6:
        raise SystemExit("the belt strip is %g tall but a segment of %g scrolled by up "
                         "to %g needs %g" % (rh, longest, BELT_PERIOD,
                                             BELT_PERIOD+longest))
    if BELT_THICKNESS+(TRACK_X1-TRACK_X0) > rw+1e-6:
        raise SystemExit("the belt strip is not wide enough for the tread and its edge")


# ---------------------------------------------------------------------------
# The machine
# ---------------------------------------------------------------------------
def build_undercarriage(mesh):
    """The tracks' frames and the body between them.  Never moves relative to the entity."""
    faces = mesh.group("undercarriage")
    volume = 0.0

    # The belly: the body slung between the tracks, holding the slew ring up.
    volume += add_box(mesh, faces, "belly", (-12, -9, -15, 12, -2, 15),
                      sides("steel", top="steel", bottom="steel_dark"))
    # Two cross members out to the track frames, which is what an excavator's
    # undercarriage is: an H seen from above.
    for z0, z1 in ((-13, -7), (7, 13)):
        volume += add_box(mesh, faces, "cross member", (-TRACK_X1, -8, z0, TRACK_X1, -3, z1),
                          sides("steel", bottom="steel_dark"))
    # The slew ring, and the collar the house sits down over.
    #
    # A hair narrower than the counterweight above it, which is 13 to a side: the ring's
    # end caps and the counterweight's flanks are both flat, both face outwards and both
    # would sit on x = +-13, and where they overlap -- a sliver at the back of the ring --
    # that is two same-facing faces in one plane.  See RELIEF.  The ring loses the tenth
    # rather than the counterweight gaining one, because the counterweight's width is part
    # of the silhouette and the ring is mostly hidden under the house.
    #
    # There was a second, narrower collar inside this one.  It was entirely inside it --
    # a ten-unit disc 2.5 above the centre of a thirteen-unit one, which is 12.5 from the
    # middle against a facet 12.56 out -- so it was twenty-two faces drawn every frame
    # inside another solid, and check_anchoring says so now.
    volume += add_cylinder(mesh, faces, "slew ring", -13+RELIEF, 13-RELIEF, 0, -11.5, 13,
                           12, "plate", "boss", twist=math.pi/12)

    # A track frame per side: the beam the wheels hang off, inside the belt loop.
    for sign in (-1, 1):
        x0, x1 = sorted((sign*(TRACK_X0+1), sign*(TRACK_X1-1)))
        volume += add_frustum(mesh, faces, "track frame", -WHEEL_Z, WHEEL_Z,
                              (x0, -9.5, x1, -2.5), (x0, -9.5, x1, -2.5),
                              sides("steel", top="steel", bottom="steel_dark"))
        # The guard over the top run, which is where the mud goes.  It stops RELIEF short
        # of the belt's own edge rather than being exactly as wide as it: flush -- which
        # is how it was built, both at 12 and 24 -- the guard's flanks and the belt's are
        # one plane down the whole length of both tracks, and that is the grey flickering
        # reported between the runs.  Short rather than proud, so that nothing on the
        # machine is drawn outside the hull TRACK_X1 sets; it still overhangs the frame it
        # is bolted to by most of a unit, which is what makes it read as a guard.
        volume += add_box(mesh, faces, "track guard",
                          (min(x0, x1)-1+RELIEF, -13.5, -12, max(x0, x1)+1-RELIEF, -11.5, 12),
                          sides("yellow", top="yellow", bottom="yellow_dark"))
        # A tensioner block at the idler, so the front of the frame is not a bare end.
        # Proud of the frame by RELIEF, for the same reason and to the same effect: flush
        # with it, its flanks and the frame's are one plane over the length they share.
        volume += add_box(mesh, faces, "tensioner",
                          (min(x0, x1)-RELIEF, -8, -WHEEL_Z-3,
                           max(x0, x1)+RELIEF, -4, -WHEEL_Z+1),
                          sides("plate", bottom="steel_dark"))
    return volume


def build_tracks(mesh):
    """The two belts, and the twelve wheels inside them."""
    volume = 0.0
    #  Left is +x.  Working it through: the renderer's scale sends model -Z to the machine's
    #  heading, and a machine facing Minecraft's south has east -- +X -- on its left.  It is
    #  also which side the cab is on, which is the left on every real machine.
    for side, sign in (("left", 1), ("right", -1)):
        faces = mesh.group("track_"+side)
        x0, x1 = sorted((sign*TRACK_X0, sign*TRACK_X1))
        volume += add_belt(mesh, faces, "belt", x0, x1)
        for index, (z, y, radius, half) in enumerate(WHEELS):
            wheel = mesh.group("wheel_%s_%d"%(side, index))
            # Authored about the axle, because the renderer spins it about the axle.
            count = 12 if radius >= 4 else 8
            volume += add_cylinder(mesh, wheel, "wheel %s %d"%(side, index),
                                   sign*WHEEL_X-half, sign*WHEEL_X+half, 0, 0,
                                   radius, count, "rim", "hub")
    return volume


def build_house(mesh):
    """Everything that slews: deck, engine, counterweight, cab, and the arm off the front."""
    faces = mesh.group("house")
    # The glazing, in the same frame and drawn under the same transform, kept apart only
    # so it can be drawn last and blended.  Declared here, next to the group it belongs
    # to, so that the two are never accidentally authored about different pivots.
    glass = mesh.group("house_glass")
    volume = 0.0

    # The deck: the floor everything else stands on, and the turntable under it.
    volume += add_box(mesh, faces, "deck", (-14, -3, -18, 14, 0, 12),
                      sides("yellow", top="walkway", bottom="steel_dark"))
    # The counterweight, hung off the back, with its shoulder chamfered.  It is why a
    # real one does not tip over and the fastest way to make a silhouette read as an
    # excavator rather than as a tractor.
    volume += add_frustum(mesh, faces, "counterweight", 12, 22,
                          (-13, -15, 13, 0), (-13, -15, 13, 0),
                          {"+x": "counterweight", "-x": "counterweight",
                           "+z": "hazard", "-z": "steel_dark",
                           "-y": "steel_dark", "+y": "steel_dark"})
    volume += add_frustum(mesh, faces, "counterweight shoulder", 12, 22,
                          (-13, -18, 13, -15), (-11, -17, 11, -15),
                          {"+x": "counterweight", "-x": "counterweight",
                           "+z": "hazard", "-z": "steel_dark",
                           "-y": "steel_dark", "+y": "steel_dark"})

    # The engine cowl, on the side away from the cab, louvred down both flanks.
    volume += add_box(mesh, faces, "engine cowl", (-14, -14, -2, 0, -3, 12),
                      {"+x": "cowl", "-x": "cowl", "+z": "vent", "-z": "cowl",
                       "-y": "yellow", "+y": "steel_dark"})
    # The grating over it: a real machine's radiator breathes upwards.
    volume += add_box(mesh, faces, "radiator grating", (-8, -15, 1, -2, -14, 9),
                      sides("vent", top="grille", bottom="steel_dark"))
    # The front deck, lower, so the boom and its ram are not buried in bodywork.  Its
    # inboard edge runs RELIEF into the cab's base rather than stopping flush against it:
    # the boom's foot pin, which stands BOSS_PROUD out of ears that are themselves 4.5
    # either side of the pivot, puts its face on exactly the plane those two would have
    # shared, and three surfaces in one plane at the busiest joint on the machine is the
    # flicker this generator refuses to write.
    volume += add_box(mesh, faces, "front deck", (-14, -9, -18, RELIEF, -3, -2),
                      sides("yellow", top="walkway", bottom="yellow_dark"))
    # A toolbox filling the corner behind the cab.
    volume += add_box(mesh, faces, "toolbox", (1, -9, 5, 14, -3, 12),
                      sides("yellow", top="walkway", bottom="yellow_dark"))
    # The block the cab stands on: tanks and pumps on a real machine, and the reason the
    # operator sits where CrawlerGeometry says they do.
    #
    # **Without it the whole cab floated.**  CAB_FLOOR is 30 units above the ground and the
    # deck it is meant to stand on is 17, and nothing at all filled the thirteen units
    # between them on the cab's side of the machine -- the nearest steel was the engine
    # cowl, a unit away across a gap you could see the sky through.  Eighteen solids, the
    # entire cab, hanging in the air; the second thing the report "floating parts of the
    # model" was about.  It runs to the cowl's face at x=0 so that the two read as one
    # superstructure rather than as two blocks with a slot between them.
    # Its foot goes RELIEF into the deck slab, as the boom's ears and the rail posts do:
    # level with it, its underside and the front deck's are one plane facing one way over
    # the strip the two share.
    volume += add_box(mesh, faces, "cab base",
                      (0, DECK_TOP, CAB_Z0, CAB_X1, -3+RELIEF, 5),
                      sides("yellow", top="walkway", bottom="yellow_dark"))

    # The ears the boom is pinned between, straddling the pivot.  Their feet go RELIEF
    # into the deck rather than stopping level with the front deck's, which is where they
    # were: two undersides in one plane, both facing down, over the whole footprint the
    # ears share with the deck they stand on.  Buried, they are simply not drawn.
    for x0, x1 in ((-9.5, -6.5), (-3.5, -0.5)):
        volume += add_frustum(mesh, faces, "boom ear", -19, -8,
                              (x0, -13, x1, -3+RELIEF), (x0, -13, x1, -3+RELIEF),
                              sides("plate", bottom="steel_dark"))

    # The exhaust stack and its rain cap, standing *up* out of the cowl.
    #
    # It used to lie across the machine four units above the cowl's roof, touching nothing:
    # a drum and a lid floating over the engine, because the only prism this generator could
    # build ran across the machine and a stack that runs across the machine is not a stack.
    # Its foot is a unit inside the cowl, which is where a pipe out of an engine begins.
    volume += add_tube(mesh, faces, "exhaust", "y", -24.0, -13.0, 6.5, -10.5, 1.4, 8,
                       "steel", "steel_dark")
    volume += add_tube(mesh, faces, "exhaust cap", "y", -25.4, -23.4, 6.5, -10.5, 2.0, 8,
                       "steel_dark", "steel_dark")

    volume += build_cab(mesh, faces, glass)
    volume += build_handrails(mesh, faces)
    return volume


def build_cab(mesh, faces, glass):
    """
    The operator's cab: a steel frame with glass in the holes, not a painted box.

    **The openings are real geometry.**  The old cab was one box wearing a texture with
    glazing painted on its middle band, which at this scale reads as a sticker; four
    posts, a roof, a floor and separate panes read as a cab because the light gets
    through the gaps between them.  The seat is inside it -- CrawlerGeometry.CAB_* is
    read off these numbers, and a test asserts the operator ends up between these walls.

    **Every pane goes into `glass`, not into `faces`.**  They are still closed thin
    boxes rather than single quads, deliberately: a box has an outward-facing surface on
    each side of the pane, so it is lit and visible from inside the cab and from outside
    it without the renderer having to turn face culling off, and the closed-solid check
    below stays true of every piece of the machine without an exemption.
    """
    volume = 0.0
    volume += add_box(mesh, faces, "cab floor",
                      (CAB_X0, CAB_FLOOR, CAB_Z0, CAB_X1, DECK_TOP, CAB_Z1),
                      sides("steel", top="walkway", bottom="steel_dark"))
    volume += add_box(mesh, faces, "cab roof",
                      (CAB_X0-0.5, CAB_TOP, CAB_Z0-0.5, CAB_X1+0.5, CAB_TOP+2, CAB_Z1+0.5),
                      sides("yellow", top="yellow", bottom="steel_dark"))
    for x0, x1 in ((CAB_X0, CAB_X0+2), (CAB_X1-2, CAB_X1)):
        for z0, z1 in ((CAB_Z0, CAB_Z0+2), (CAB_Z1-2, CAB_Z1)):
            volume += add_box(mesh, faces, "cab post",
                              (x0, CAB_TOP+2, z0, x1, CAB_FLOOR, z1), "yellow")
    # The back wall is solid: it is the side the engine is on and nobody looks that way.
    #
    # It spans *between* the two rear posts rather than across them.  Across them it
    # enclosed them: both rear posts were inside this box with all six of their faces in
    # its faces' planes, so every one of them was a coincident pair and the whole back
    # corner of the cab flickered.  Between them, the wall meets each post edge to edge --
    # face against opposing face, which is what a box against a box is and is fine -- and
    # the posts read as the corner pillars they are meant to be.
    volume += add_box(mesh, faces, "cab back",
                      (CAB_X0+2, CAB_TOP+2, CAB_Z1-2, CAB_X1-2, CAB_FLOOR, CAB_Z1),
                      sides("yellow", ends="cab_side"))
    # The inner wall, half height: a rail to lean on, and clear glass above it so the
    # operator can see the arm they are working with.
    volume += add_box(mesh, faces, "cab inner wall",
                      (CAB_X0, -24, CAB_Z0+2, CAB_X0+1.5, CAB_FLOOR, CAB_Z1-2), "yellow")
    volume += add_box(mesh, glass, "cab inner glass",
                      (CAB_X0+0.4, CAB_TOP+2, CAB_Z0+2, CAB_X0+1.1, -24, CAB_Z1-2),
                      "glass")
    # The windscreen, which on an excavator runs the full height of the front.
    volume += add_box(mesh, glass, "windscreen",
                      (CAB_X0+2, CAB_TOP+2, CAB_Z0+0.4, CAB_X1-2, CAB_FLOOR, CAB_Z0+1.1),
                      "glass")
    # The door: glazed above the handle, panelled below it.
    volume += add_box(mesh, glass, "door glass",
                      (CAB_X1-1.1, CAB_TOP+2, CAB_Z0+2, CAB_X1-0.4, -22, CAB_Z1-2),
                      "glass")
    volume += add_box(mesh, faces, "door panel",
                      (CAB_X1-1.5, -22, CAB_Z0+2, CAB_X1, CAB_FLOOR, CAB_Z1-2),
                      sides("cab_side", ends="yellow"))
    volume += add_box(mesh, faces, "door handle",
                      (CAB_X1, -23, CAB_Z1-5, CAB_X1+0.6, -21.5, CAB_Z1-3), "plate")
    # The seat, visible through the glass, which is most of what makes a cab look
    # occupied rather than empty.
    volume += add_box(mesh, faces, "seat", (5, -19, -6, 11, CAB_FLOOR, -2), "trim")
    volume += add_box(mesh, faces, "seat back", (5, -25, -2, 11, -19, 0), "trim")
    # Two work lights over the windscreen, and one over the door.
    volume += add_box(mesh, faces, "work light", (3, CAB_TOP+0.5, CAB_Z0-1.5, 6,
                                                  CAB_TOP+2.5, CAB_Z0-0.5),
                      sides("lamp", ends="steel_dark"))
    volume += add_box(mesh, faces, "work light", (10, CAB_TOP+0.5, CAB_Z0-1.5, 13,
                                                  CAB_TOP+2.5, CAB_Z0-0.5),
                      sides("lamp", ends="steel_dark"))
    # A beacon, because everything on a site has one.  Upright, and its foot inside the
    # roof it is bolted to rather than resting on the skin of it.
    volume += add_tube(mesh, faces, "beacon", "y", CAB_TOP-2.5, CAB_TOP+0.5, CAB_Z1-2, 8,
                       1.1, 8, "lamp", "steel_dark")
    return volume


def build_handrails(mesh, faces):
    """
    The rails round the deck.  Small, and they do more for the silhouette than the
    counterweight does: they are the only thin thing on an otherwise slab-sided machine.

    **Each rail stands on the deck it guards, not on the deck slab far below it.**  Both
    were built six units tall from the slab at y=-3, which put the front pair two thirds
    buried in the front deck and the rear pair -- bar and all -- entirely inside the engine
    cowl, six units of steel drawn every frame inside a box nobody can see into.  They
    start from whatever they actually stand on now, which is what makes them visible and
    what makes them read as the walkway edge rather than as posts growing out of bodywork.
    """
    volume = 0.0

    def post(name, z, floor, height):
        #  The foot goes RELIEF into the deck it stands on.  Level with it -- which is how
        #  these were built -- the post's underside and the underside of whatever else
        #  stands on that patch of deck are one plane facing one way, and that is half of
        #  the flickering reported down the machine's right-hand flank.
        return add_box(mesh, faces, name+" rail post",
                       (-13.5, floor-height, z, -12.5, floor+RELIEF, z+1), "plate")

    def rail(name, z0, z1, floor, height):
        #  And the bar stands RELIEF proud of its posts on every side.  Flush, it shared
        #  four planes with each post it crosses -- both flanks, the top, and the end --
        #  which is the other half of it.  A handrail is welded to the outside of its
        #  stanchions in any case; this is what that looks like.
        top = floor-height
        return add_box(mesh, faces, name+" rail",
                       (-13.5-RELIEF, top-RELIEF, z0-RELIEF,
                        -12.5+RELIEF, top+1, z1+RELIEF), "plate")

    #  The front pair stand on the front deck, the rear pair on the roof of the cowl.
    for z in (-16, -6):
        volume += post("front", z, -9, 6)
    volume += rail("front", -16, -5, -9, 6)
    for z in (2, 10):
        volume += post("rear", z, DECK_TOP, 6)
    volume += rail("rear", 2, 11, DECK_TOP, 6)
    # A step up onto the deck, on the cab's side, where the door is.
    volume += add_box(mesh, faces, "step", (14, -2, -12, 16, -1, -6),
                      sides("walkway", bottom="steel_dark"))
    # The nameplate.  Generic: this machine belongs to no real manufacturer.
    volume += add_box(mesh, faces, "nameplate", (-14.4, -12, 2, -14, -8, 12),
                      {"-x": "nameplate", "+x": "plate", "+z": "plate", "-z": "plate",
                       "-y": "plate", "+y": "plate"})
    return volume


def build_boom(mesh):
    """
    The boom: a tapered beam between two pins, with the stick's ram on top of it.

    **Straight along its own centreline, and that is not a shortcut.**  The arm's
    hitboxes are placed by CrawlerArm.alongArm at fixed distances from the boom's pivot,
    following the centreline of each section in turn.  A curved "banana" boom looks
    better standing still and would put the visible steel a couple of units off the
    boxes that decide what the machine can hit, everywhere except at the two pins.

    **Two discs on this section, not five.**  The arm had a boss at each pin and then a
    drum for each ram and each ram rod besides -- nine round faces down its length, all of
    them the same dark plate, and from ten blocks away a playtester read the lot as
    barnacles rather than as machinery.  A disc is now what a *pin* looks like and nothing
    else does: the rams lie along the beam as rams, which is both what they are and the
    one shape on an arm that cannot be mistaken for a joint.
    """
    faces = mesh.group("boom")
    volume = 0.0
    volume += BOOM.build(mesh, faces, "boom beam",
                         sides("yellow", top="yellow", bottom="yellow_dark"))
    # The pin at each end, standing BOSS_PROUD out of the beam *as it is where the pin is*.
    volume += add_cylinder(mesh, faces, "boom foot pin",
                           -pin_half(BOOM, 0, 4.5), pin_half(BOOM, 0, 4.5), 0, 0,
                           4.5, 12, "plate", "boss")
    volume += add_cylinder(mesh, faces, "boom head pin",
                           -pin_half(BOOM, -BOOM_LENGTH, 3.5),
                           pin_half(BOOM, -BOOM_LENGTH, 3.5), -BOOM_LENGTH, 0,
                           3.5, 12, "plate", "boss")
    # The ram that folds the stick: a body pinned to the anchor on the boom's back and a
    # rod out of it towards the knuckle, both lying *along* the boom rather than across it.
    volume += add_box(mesh, faces, "ram anchor", (-3, -8, -4, 3, -3, 0), "plate")
    ram = BOOM.lies_along(True, -14, -4, 2.0, 0.8)
    volume += add_tube(mesh, faces, "stick ram", "z", -14, -4, 0, ram, 2.0, 8,
                       "steel", "steel_dark")
    volume += add_tube(mesh, faces, "stick ram rod", "z", -21, -13.5, 0, ram, 1.1, 8,
                       "plate", "steel_dark")
    # And the boom's own ram, bedded into the underside near the foot, where the pair of
    # cylinders that lift a real boom are pinned.
    volume += add_tube(mesh, faces, "boom ram", "z", -12, -1, 0,
                       BOOM.lies_along(False, -12, -1, 2.2, 0.8), 2.2, 8,
                       "steel", "steel_dark")
    # The hydraulic run down the flank, which follows the taper rather than crossing it:
    # a straight box lifts off the steel as the beam narrows under it.
    volume += add_hose_run(mesh, faces, "hose run", BOOM, -22, -2, -2.5, 0.5, 0.8)
    return volume


def pin_half(beam, z, radius):
    """How far out a pin of that radius, pinned there, reaches: the boss's own half length."""
    return beam.half_width(z-radius, z+radius)+BOSS_PROUD


def add_hose_run(mesh, faces, what, beam, z0, z1, y0, y1, thickness):
    """A bundle of hose down a beam's -x flank, following it as it tapers."""
    def rect(z):
        surface = beam.half_width(z)
        return -surface-thickness, y0, -surface+thickness/2, y1

    return add_frustum(mesh, faces, what, z0, z1, rect(z0), rect(z1), "steel_dark")


def build_stick(mesh):
    """The stick, and the linkage the tool hangs off."""
    faces = mesh.group("stick")
    volume = 0.0
    volume += STICK.build(mesh, faces, "stick beam",
                          sides("yellow", top="yellow", bottom="yellow_dark"))
    # The eye the stick turns on, RELIEF larger than the boom's head pin it turns *on*.
    #
    # **The two were the same radius on the same axis**, which is a pair of cylinders whose
    # every facet is one surface -- and being coaxial with the joint they turn about, it
    # stayed one surface at every angle the arm can be folded to.  That is the flicker
    # reported at the boom-stick knuckle.  Larger rather than smaller: smaller would put
    # this eye entirely inside the boom's pin, drawing twenty-two faces nobody can ever
    # see, and a boss around a pin is the larger of the two on a real machine anyway.
    volume += add_cylinder(mesh, faces, "stick foot pin",
                           -pin_half(STICK, 0, 3.5+RELIEF),
                           pin_half(STICK, 0, 3.5+RELIEF), 0, 0, 3.5+RELIEF, 12,
                           "plate", "boss")
    volume += add_cylinder(mesh, faces, "stick head pin",
                           -pin_half(STICK, -STICK_LENGTH, 2.6),
                           pin_half(STICK, -STICK_LENGTH, 2.6), -STICK_LENGTH, 0,
                           2.6, 12, "plate", "boss")
    # The tool's ram, lying along the stick's back, and the two link plates down to the pin.
    ram = STICK.lies_along(True, -12, -3, 1.8, 0.8)
    volume += add_tube(mesh, faces, "tool ram", "z", -12, -3, 0, ram, 1.8, 8,
                       "steel", "steel_dark")
    volume += add_tube(mesh, faces, "tool ram rod", "z", -17, -11.5, 0, ram, 1.0, 8,
                       "plate", "steel_dark")
    for x0, x1 in ((-3.2, -2.0), (2.0, 3.2)):
        volume += add_frustum(mesh, faces, "tool link", -STICK_LENGTH+1, -14,
                              (x0, -3.0, x1, 0.5), (x0, -6.0, x1, -3.0), "plate")
    return volume


# The ears every attachment hangs off, and the pin through them.  All three tools carry
# the same one -- they hang off the same stick -- so it is built once; the names differ
# only because a group's parts are named after the group in the anchoring table.
TOOL_MOUNT = (-4, -4, -3, 4, 2, 1)
TOOL_MOUNT_HALF = TOOL_MOUNT[3]


def add_tool_mount(mesh, faces, tool):
    volume = add_box(mesh, faces, tool+" mount", TOOL_MOUNT, "plate")
    volume += add_cylinder(mesh, faces, tool+" pin",
                           -TOOL_MOUNT_HALF-BOSS_PROUD, TOOL_MOUNT_HALF+BOSS_PROUD,
                           0, 0, 2.4, 8, "plate", "boss")
    return volume


def build_bucket(mesh):
    """
    The digging bucket: a curved shell with a toothed lip.

    Its mouth faces +Y, which after the tool's ninety degrees of hang is towards the
    machine -- a backhoe bucket digs by being pulled in, so that is the way round it
    has to be to look like it is doing what it does.
    """
    faces = mesh.group("tool_bucket")
    volume = 0.0
    volume += add_tool_mount(mesh, faces, "bucket")
    # The back, in four slabs that between them curve away from the mouth and back to
    # the lip.  A curve at this scale is three straight bits and a decision.
    # z, half width, and the two y edges of the slab at that z.
    shell = [(-3, 6.0, -6.0, -3.0), (-6, 7.0, -7.0, -4.5), (-9, 7.0, -7.0, -3.0),
             (-11, 6.0, -6.5, 1.0)]
    for i in range(len(shell)-1):
        z0, w0, y0a, y0b = shell[i]
        z1, w1, y1a, y1b = shell[i+1]
        volume += add_frustum(mesh, faces, "bucket back", z1, z0,
                              (-w1, y1a, w1, y1b), (-w0, y0a, w0, y0b),
                              {"+x": "plate", "-x": "plate", "+z": "bucket",
                               "-z": "bucket", "-y": "bucket", "+y": "trim"})
    # The sides, which close the shell the slabs left open.  Each stands RELIEF proud of
    # it: flush -- 7.0, which is the shell's own half width and the lip's -- the side
    # plate, the widest slab of the back and the lip all put an outward face on the same
    # plane, three deep in places.  Wear plates are bolted to the outside of a real
    # bucket's sides, so proud is also what they look like.
    for x0, x1 in ((-7.0-RELIEF, -5.5), (5.5, 7.0+RELIEF)):
        volume += add_frustum(mesh, faces, "bucket side", -12, -2,
                              (x0, -7.0, x1, 3.0), (x0, -6.0, x1, 2.0), "plate")
    # The lip, and five teeth on it.
    volume += add_box(mesh, faces, "bucket lip", (-7, -6.5, -12.5, 7, 1.0, -10.5),
                      sides("edge", top="edge", bottom="edge"))
    for i in range(5):
        x = -6.4+i*2.9
        volume += add_frustum(mesh, faces, "bucket tooth", -15.0, -12.5,
                              (x, -3.4, x+1.6, -0.6), (x-0.3, -4.6, x+1.9, 0.6),
                              sides("edge", top="edge", bottom="edge"))
    return volume


def build_breaker(mesh):
    """
    The hydraulic breaker: a housing, a barrel and a chisel, in a line down the tool.

    **The barrel used to hang in mid-air beside the housing.**  It was built with the only
    primitive there was, a prism across the machine, and given the tool's centreline as its
    *height* instead of its length: a drum three and a half units below the mount and four
    off the nearest steel, which is the part a playtester saw floating detached beside the
    bucket end of the arm.  It runs along the tool now, out of the housing and into the
    chisel, which is where a breaker's barrel is.
    """
    faces = mesh.group("tool_breaker")
    volume = 0.0
    volume += add_tool_mount(mesh, faces, "breaker")
    volume += add_frustum(mesh, faces, "breaker housing", -9, -1,
                          (-4.5, -5.0, 4.5, 5.0), (-5.0, -5.5, 5.0, 5.5),
                          sides("yellow", top="yellow", bottom="yellow_dark"))
    volume += add_tube(mesh, faces, "breaker barrel", "z", -12.5, -8.5, 0, 0, 3.2, 12,
                       "steel", "steel_dark")
    volume += add_frustum(mesh, faces, "chisel", -17, -12,
                          (-1.0, -1.0, 1.0, 1.0), (-2.2, -2.2, 2.2, 2.2),
                          sides("edge", top="edge", bottom="edge"))
    return volume


def build_grapple(mesh):
    """The grapple: a rotator and two jaws, held a little open."""
    faces = mesh.group("tool_grapple")
    volume = 0.0
    volume += add_tool_mount(mesh, faces, "grapple")
    # The rotator, a shade narrower than the stick's head pin it hangs behind.  At the
    # same width its end discs and the pin's were in the same two planes and overlapped --
    # the two are only 4.5 apart and together they are 5.6 across -- and the pair survived
    # every angle the tool can be curled to, because curling it turns it about the very
    # axis those planes are square to.
    volume += add_cylinder(mesh, faces, "rotator", -3.0+RELIEF, 3.0-RELIEF, -4.5, 0, 3.0,
                           12, "steel", "steel_dark")
    volume += add_box(mesh, faces, "jaw beam", (-5, -2, -8, 5, 2, -6), "plate")
    # Each jaw is three segments hinged outwards, so the pair reads as a claw about to
    # close rather than as two flat plates.
    for sign in (-1, 1):
        y0 = sign*2
        volume += add_frustum(mesh, faces, "jaw", -12, -8,
                              yrect(4.5, y0+sign*3.5, y0+sign*5.0),
                              yrect(5.0, y0-0.8, y0+0.8), "plate")
        volume += add_frustum(mesh, faces, "jaw tip", -15.5, -12,
                              yrect(3.5, y0+sign*1.0, y0+sign*2.4),
                              yrect(4.5, y0+sign*3.5, y0+sign*5.0),
                              sides("edge", top="edge", bottom="edge"))
    return volume


# ---------------------------------------------------------------------------
# Z-fighting
#
# **The check that would have caught every one of the mottled patches a playtester
# reported.**  Two faces that point the same way, lie in the same plane and cover any of
# the same ground are a coin flip resolved by the depth buffer's rounding: per pixel, per
# frame, changing its mind as the camera moves.  It is not an error anything reports and
# it does not look like a modelling mistake from the seat -- it looks like the texture is
# broken.  It was five separate places on this machine and every one of them was steel
# built flush against steel.
#
# The rules, and each of them is load bearing:
#
#   * **Same-facing only.**  Two boxes stood against each other share a plane by
#     definition -- one's +x face and the other's -x face.  Those never fight: whichever
#     side you are on, one of them is facing away and is culled, and the other is in
#     front of it.  Only faces pointing the *same* way can both be drawn at once.
#   * **Real polygon overlap, not bounding boxes.**  A cylinder's cap is fanned into
#     quads that share edges and whose bounding boxes overlap almost completely, and
#     every wheel on the machine would be reported by a box test.  This projects both
#     faces onto their shared plane and separates them properly, so a shared edge is
#     contact and not overlap.
#   * **The alternates never meet.**  Exactly one of the three attachments is drawn, so
#     a pair between two different tool_* groups is geometry that is never on screen
#     together.  Pairs *within* one of them are still checked.
#   * **The rest pose is enough, and this is why.**  Groups move relative to one another,
#     so in principle a coincidence could appear at one angle and not another.  But a
#     rotation leaves exactly the planes square to its own axis where they were, so a
#     coincidence that survives being posed is a coincidence in one of those planes --
#     and it is therefore already there at every angle, this one included.  What this
#     cannot see is the transient sort: the boom's flank sweeping through the cowl's
#     plane at one particular angle on the way past.  Nothing can be done about those
#     and they flicker for a frame rather than sitting there.
# ---------------------------------------------------------------------------
#  Two planes closer than this are the same plane as far as a depth buffer at any
#  sensible viewing distance is concerned; see RELIEF, which is ten times it.
COPLANAR_TOL = 0.01
#  Faces have to be *parallel*, not nearly so: the boom's flanks and the stick's taper at
#  1.6 milliradians apart are two different surfaces and converge to nothing.
PARALLEL_TOL = 1e-7
#  Slack for faces that touch along an edge or a corner, which is contact, not overlap.
TOUCH_TOL = 1e-4

#  One of these is drawn at a time -- see ModelHydraulicCrawler.groupFor -- so two of
#  them sharing a plane is two things that are never in the same frame.
ALTERNATES = {"tool_bucket", "tool_breaker", "tool_grapple"}


def rest_pose(name):
    """Where a group's own space sits when every joint is at zero.

    The renderer's transform stack, flattened: the house is translated to the slew ring,
    the boom to its pin in the house, the stick to the boom's head, the tool to the
    stick's, and a wheel to its axle.  Angles are all zero, which is what makes this a
    translation -- see check_coplanar on why that is the pose worth checking.
    """
    house = (0.0, SLEW_HEIGHT, 0.0)
    boom = (house[0]+BOOM_PIVOT[0], house[1]+BOOM_PIVOT[1], house[2]+BOOM_PIVOT[2])
    stick = (boom[0], boom[1], boom[2]-BOOM_LENGTH)
    if name in ("undercarriage", "track_left", "track_right"):
        return 0.0, 0.0, 0.0
    if name in ("house", "house_glass"):
        return house
    if name=="boom":
        return boom
    if name=="stick":
        return stick
    if name.startswith("tool_"):
        return stick[0], stick[1], stick[2]-STICK_LENGTH
    if name.startswith("wheel_"):
        z, y, _, _ = WHEELS[int(name.rsplit("_", 1)[1])]
        return 0.0, y, z
    raise SystemExit("no rest pose for the group %s; a new group has to say where the "
                     "renderer puts it before its faces can be checked against the "
                     "rest of the machine" % name)


def separated(poly, other):
    """Whether an edge of `poly` has all of `other` outside it: one separating axis."""
    count = len(poly)
    twice_area = sum(poly[i][0]*poly[(i+1)%count][1]-poly[(i+1)%count][0]*poly[i][1]
                     for i in range(count))
    turn = 1.0 if twice_area > 0 else -1.0
    for i in range(count):
        x0, y0 = poly[i]
        x1, y1 = poly[(i+1)%count]
        ex, ey = x1-x0, y1-y0
        length = math.hypot(ex, ey)
        if length < 1e-9:
            continue
        nx, ny = turn*ey/length, -turn*ex/length      # outwards, whichever way it winds
        if min(nx*(vx-x0)+ny*(vy-y0) for vx, vy in other) >= -TOUCH_TOL:
            return True
    return False


def overlaps(a, b, axis):
    """Whether two convex polygons in one plane cover any of the same ground.

    Separating axis, over the edges of both: two convex shapes miss each other if and
    only if one of their own edges has the other entirely on its outside.  Every face
    this generator makes is convex -- boxes, frusta, and cap quads whose corners are in
    order round a circle -- which is what makes that true here.
    """
    keep = [i for i in range(3) if i!=axis]
    pa = [(p[keep[0]], p[keep[1]]) for p in a]
    pb = [(p[keep[0]], p[keep[1]]) for p in b]
    return not separated(pa, pb) and not separated(pb, pa)


def check_coplanar(mesh):
    """Refuse to write a machine with two co-rendered faces fighting over one plane."""
    quads = []
    for name, _ in mesh.groups:
        ox, oy, oz = rest_pose(name)
        for what, points in mesh.polygons[name]:
            placed = [(p[0]+ox, p[1]+oy, p[2]+oz) for p in points]
            normal = newell(placed)
            length = math.sqrt(dot(normal, normal))
            unit = (normal[0]/length, normal[1]/length, normal[2]/length)
            quads.append((name, what, unit, dot(unit, placed[0]), placed))

    #  Bucketed by which way the face points, and sorted by how far along that direction
    #  its plane is, so each face is only ever compared with the handful whose planes are
    #  within a hundredth of a unit of its own.  Faces pointing opposite ways land in
    #  different buckets, which is the "same-facing only" rule falling out for free.
    buckets = {}
    for quad in quads:
        axis = max(range(3), key=lambda i: abs(quad[2][i]))
        buckets.setdefault((axis, quad[2][axis] > 0), []).append(quad)

    clashes = []
    for (axis, _), bucket in sorted(buckets.items()):
        bucket.sort(key=lambda q: q[3])
        for i, a in enumerate(bucket):
            for b in bucket[i+1:]:
                if b[3]-a[3] > COPLANAR_TOL:
                    break
                if dot(a[2], b[2]) < 1-PARALLEL_TOL:
                    continue
                if a[0]!=b[0] and a[0] in ALTERNATES and b[0] in ALTERNATES:
                    continue
                if overlaps(a[4], b[4], axis):
                    clashes.append((axis, a, b))
    if clashes:
        lines = ["%d pairs of faces point the same way and share a plane, which is a "
                 "machine that flickers:" % len(clashes)]
        for axis, a, b in clashes[:40]:
            lines.append("  %s in %s and %s in %s, both facing %s%s at %.3f"
                         % (a[1], a[0], b[1], b[0],
                            "+" if a[2][axis] > 0 else "-", "xyz"[axis],
                            a[3] if a[2][axis] > 0 else -a[3]))
        if len(clashes) > 40:
            lines.append("  ... and %d more" % (len(clashes)-40))
        lines.append("Give whichever of the two is the detail a RELIEF of clearance --")
        lines.append("proud of the surface it decorates, or sunk into it -- or shrink it")
        lines.append("until the two no longer overlap.  See the note on RELIEF.")
        raise SystemExit("\n".join(lines))
    return len(quads)


# ---------------------------------------------------------------------------
# Anchoring
#
# **The check that would have caught the floating discs.**  A playtester's second report
# on this machine was "floating parts of the model", and it was four separate things:
#
#   * the whole cab, eighteen solids of it, hanging eleven units above the deck it is
#     meant to stand on, because nothing had ever been built to fill the space between;
#   * the exhaust stack and its cap, four units over the engine cowl;
#   * the stick's and the tool's ram rods, a unit off the beams they run along, because
#     they were drums lying across the arm rather than rods running down it;
#   * the breaker's barrel, hanging beside its housing rather than out of it.
#
# And, everywhere else, a subtler version of the same thing: a boss sized against the
# *nominal* half width of a tapered beam stands proud of it by a whole unit wherever the
# beam is narrower than its widest cross-section, which at ten blocks is a disc hovering
# beside the arm rather than a pin through it.
#
# None of it is an error anything reports.  A part with nothing under it draws perfectly.
#
# So every solid on the machine says what holds it up, and this checks that the answer is
# true of the mesh that was actually written:
#
#   * **it touches its parent.**  Two convex solids are separated by the largest gap any
#     of their face normals or edge-pair normals can find, which for convex shapes is the
#     distance between them; anything above a hundredth of a unit is air you can see.
#   * **it stands out of its parent by exactly what it says it does.**  Measured, not
#     assumed: rays cast from the part's own outer face back into the parent's solid, at a
#     quarter-unit pitch, and the *smallest* distance any of them finds is the protrusion
#     where the parent is closest.  On a tapered beam that is the widest station under the
#     part, which is the number a boss has to be built against and the one that was wrong.
#   * **it is not buried.**  A part entirely inside another solid is drawn every frame and
#     never seen; the machine had a handrail, posts and bar, inside the engine cowl.
#
# Two honest limits.  Solids are treated as their convex hulls, which is exact for every
# primitive here except the belt loop, and errs towards saying nothing rather than towards
# a false alarm.  And it is all measured in the rest pose, for the reason check_coplanar
# gives: joints turn about their pins, so what is seated at rest stays seated.
# ---------------------------------------------------------------------------
CONTACT_TOL = 0.01          # air thinner than this is a rounding error, not a gap
PROUD_TOL = 0.05            # how far a measured protrusion may miss what it claims
SAMPLE_PITCH = 0.25         # how finely an outer face is sampled to find its parent
FACING = 0.95               # how squarely a face must point a way to be measured that way


class Mount:
    """What holds one solid up, and how far it stands out of it."""

    def __init__(self, parents, faces=(), proud=0.0):
        self.parents = (parents,) if isinstance(parents, str) else tuple(parents)
        self.faces = (faces,) if isinstance(faces, str) else tuple(faces)
        self.proud = proud


#  Groups that never move relative to one another, and may therefore hold each other up.
#  Everything else is checked against its own group only: the boom's pins are on the boom
#  whatever angle it is at, but the boom against the cowl is true at one angle and false
#  at the next, and a check that believed otherwise would be checking the rest pose rather
#  than the machine.  A wheel counts as part of the chassis because it is round about the
#  axle it spins on, so its surface is where it is at every angle.
def frame_of(group):
    if group in ("undercarriage", "track_left", "track_right") or group.startswith("wheel_"):
        return "chassis"
    if group in ("house", "house_glass"):
        return "house"
    return group


#  The pieces that are not attached to anything: each is the thing its own group is built
#  around, and the renderer's transform is what holds it up.
ROOTS = {("undercarriage", "belly"), ("track_left", "belt"), ("track_right", "belt"),
         ("house", "deck"), ("boom", "boom beam"), ("stick", "stick beam"),
         ("tool_bucket", "bucket mount"), ("tool_breaker", "breaker mount"),
         ("tool_grapple", "grapple mount")}
ROOTS.update((("wheel_%s_%d"%(side, i), "wheel %s %d"%(side, i))
              for side in ("left", "right") for i in range(len(WHEELS))))

#  Everything else, and what it is bolted to.  Named by (group, part name), so the five
#  bucket teeth are one row and both track guards are one row -- parts built in a loop are
#  bolted to the same thing in the same way, which is why they are built in a loop.
ATTACHMENTS = {
    ("undercarriage", "cross member"): Mount("belly"),
    ("undercarriage", "slew ring"): Mount("belly"),
    ("undercarriage", "track frame"): Mount("cross member"),
    #  The guard is over the top run of the belt and rests on it; the frame it is bolted
    #  to is two units below, which is what makes it a mudguard and not a lid.
    ("undercarriage", "track guard"): Mount("belt"),
    ("undercarriage", "tensioner"): Mount("track frame", ("+x", "-x"), RELIEF),

    ("house", "counterweight"): Mount("deck"),
    ("house", "counterweight shoulder"): Mount("counterweight"),
    ("house", "engine cowl"): Mount("deck"),
    ("house", "radiator grating"): Mount("engine cowl"),
    ("house", "front deck"): Mount("deck"),
    ("house", "toolbox"): Mount("deck"),
    ("house", "cab base"): Mount("deck"),
    ("house", "boom ear"): Mount("deck"),
    ("house", "exhaust"): Mount("engine cowl"),
    ("house", "exhaust cap"): Mount("exhaust"),
    ("house", "cab floor"): Mount("cab base"),
    ("house", "cab post"): Mount("cab floor"),
    ("house", "cab roof"): Mount("cab post"),
    ("house", "cab back"): Mount("cab post"),
    ("house", "cab inner wall"): Mount("cab floor"),
    ("house", "door panel"): Mount("cab post"),
    ("house", "door handle"): Mount("door panel", "+x", 0.6),
    ("house", "seat"): Mount("cab floor"),
    ("house", "seat back"): Mount("seat"),
    ("house", "work light"): Mount("cab roof"),
    ("house", "beacon"): Mount("cab roof"),
    ("house", "front rail post"): Mount("front deck"),
    ("house", "front rail"): Mount("front rail post", "-x", RELIEF),
    ("house", "rear rail post"): Mount("engine cowl"),
    ("house", "rear rail"): Mount("rear rail post", "-x", RELIEF),
    ("house", "step"): Mount("deck", "+x", 2.0),
    ("house", "nameplate"): Mount("engine cowl", "-x", 0.4),

    ("house_glass", "windscreen"): Mount("cab post"),
    ("house_glass", "door glass"): Mount("cab post"),
    ("house_glass", "cab inner glass"): Mount("cab inner wall"),

    ("boom", "boom foot pin"): Mount("boom beam", ("+x", "-x"), BOSS_PROUD),
    ("boom", "boom head pin"): Mount("boom beam", ("+x", "-x"), BOSS_PROUD),
    ("boom", "ram anchor"): Mount("boom beam"),
    ("boom", "stick ram"): Mount(("ram anchor", "boom beam")),
    ("boom", "stick ram rod"): Mount("stick ram"),
    ("boom", "boom ram"): Mount("boom beam"),
    ("boom", "hose run"): Mount("boom beam", "-x", 0.8),

    ("stick", "stick foot pin"): Mount("stick beam", ("+x", "-x"), BOSS_PROUD),
    ("stick", "stick head pin"): Mount("stick beam", ("+x", "-x"), BOSS_PROUD),
    ("stick", "tool ram"): Mount("stick beam"),
    ("stick", "tool ram rod"): Mount("tool ram"),
    ("stick", "tool link"): Mount("stick beam"),

    ("tool_bucket", "bucket pin"): Mount("bucket mount", ("+x", "-x"), BOSS_PROUD),
    #  The shell is slabs that chain to one another, so it names itself as well as the
    #  mount the first slab hangs off.
    ("tool_bucket", "bucket back"): Mount(("bucket mount", "bucket back")),
    ("tool_bucket", "bucket side"): Mount("bucket back", ("+x", "-x"), RELIEF),
    ("tool_bucket", "bucket lip"): Mount(("bucket back", "bucket side")),
    ("tool_bucket", "bucket tooth"): Mount("bucket lip"),

    ("tool_breaker", "breaker pin"): Mount("breaker mount", ("+x", "-x"), BOSS_PROUD),
    ("tool_breaker", "breaker housing"): Mount("breaker mount"),
    ("tool_breaker", "breaker barrel"): Mount("breaker housing"),
    ("tool_breaker", "chisel"): Mount("breaker barrel"),

    ("tool_grapple", "grapple pin"): Mount("grapple mount", ("+x", "-x"), BOSS_PROUD),
    ("tool_grapple", "rotator"): Mount("grapple mount"),
    ("tool_grapple", "jaw beam"): Mount("rotator"),
    ("tool_grapple", "jaw"): Mount("jaw beam"),
    ("tool_grapple", "jaw tip"): Mount("jaw"),
}


class Piece:
    """One solid of the finished machine, placed where the renderer draws it."""

    def __init__(self, group, what, polygons):
        self.group = group
        self.what = what
        ox, oy, oz = rest_pose(group)
        self.polygons = [[(p[0]+ox, p[1]+oy, p[2]+oz) for p in poly] for poly in polygons]
        self.vertices = sorted({tuple(round(c, 5) for c in p)
                                for poly in self.polygons for p in poly})
        self.planes = []            # (unit normal, offset): the solid is n.p <= c
        self.normals = []           # the same normals, one per direction
        self.edges = []
        for poly in self.polygons:
            unit = normalise(newell(poly))
            if unit is None:
                continue
            self.planes.append((unit, dot(unit, poly[0])))
            if not any(dot(unit, other) > 1-PARALLEL_TOL for other in self.normals):
                self.normals.append(unit)
            for i, a in enumerate(poly):
                b = poly[(i+1)%len(poly)]
                edge = normalise((b[0]-a[0], b[1]-a[1], b[2]-a[2]))
                if edge is not None and not any(abs(dot(edge, other)) > 1-PARALLEL_TOL
                                                for other in self.edges):
                    self.edges.append(edge)

    def name(self):
        return "%s in %s"%(self.what, self.group)

    def span(self, axis):
        seen = [dot(axis, v) for v in self.vertices]
        return min(seen), max(seen)

    def contains(self, other):
        """Whether every corner of `other` is inside this solid: steel nobody can see."""
        return all(dot(n, v) <= c+CONTACT_TOL for n, c in self.planes
                   for v in other.vertices)

    def entry(self, origin, direction):
        """How far along `direction` from `origin` this solid's surface is, or None.

        Ahead of the origin only.  A ray cast off the machine's left flank towards its
        middle passes out the other side and would otherwise "find" the right-hand track
        frame thirty-six units behind it, and a face that starts *inside* the solid it is
        being measured against -- the inboard face of one of a mirrored pair, which is the
        other one's outboard face -- has no protrusion to report rather than a negative one.
        """
        near, far = -1e9, 1e9
        for normal, offset in self.planes:
            along = dot(normal, direction)
            outside = dot(normal, origin)-offset
            if abs(along) < 1e-9:
                if outside > TOUCH_TOL:
                    return None
                continue
            hit = -outside/along
            if along > 0:
                far = min(far, hit)
            else:
                near = max(near, hit)
        return near if -TOUCH_TOL <= near <= far else None

    def faces_towards(self, direction):
        """The polygons of this solid that point the way asked: its outer face there.

        Nearly, rather than exactly.  The hose run down the boom's flank is built on the
        taper it follows, so its outer face leans by the same thirty-fifth the beam does,
        and a face that has to be square to the axis to be measured is a face that cannot
        follow the thing it is bolted to.
        """
        return [poly for poly in self.polygons
                if dot(normalise(newell(poly)), direction) > FACING]


def normalise(vector):
    length = math.sqrt(dot(vector, vector))
    if length < 1e-12:
        return None
    return vector[0]/length, vector[1]/length, vector[2]/length


def separation(a, b):
    """How far apart two convex solids are; zero or less if they meet.

    The separating axis theorem, used for the distance rather than for the yes-or-no:
    two convex bodies that miss each other do so along one of their face normals or along
    the normal to one edge of each, so the largest gap those axes find is the gap.
    """
    worst = -1e9
    axes = list(a.normals)+list(b.normals)
    for edge in a.edges:
        for other in b.edges:
            axis = normalise(cross(edge, other))
            if axis is not None:
                axes.append(axis)
    for axis in axes:
        a0, a1 = a.span(axis)
        b0, b1 = b.span(axis)
        worst = max(worst, b0-a1, a0-b1)
    return worst


def cross(a, b):
    return (a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0])


def face_samples(polygon, pitch):
    """Points over a convex face: its corners, its edges, and a coarse fill inside it.

    The corners alone are not enough.  A boss is a disc and the beam under it ends part
    way across that disc, so the closest the parent's surface ever comes to the disc is at
    a point on the disc's rim between two of its corners -- which is exactly the station
    the boss has to be built against.  Walking the rim at a quarter of a unit finds it to
    within a hundredth on any taper this machine has.
    """
    points = list(polygon)
    for i, a in enumerate(polygon):
        b = polygon[(i+1)%len(polygon)]
        length = math.sqrt(sum((b[k]-a[k])**2 for k in range(3)))
        for step in range(1, int(length/pitch)):
            t = step*pitch/length
            points.append(tuple(a[k]+(b[k]-a[k])*t for k in range(3)))
    #  And a fan through the middle, in case the parent under the face is stepped rather
    #  than flat and its nearest point is not on the boundary at all.
    centre = tuple(sum(p[k] for p in polygon)/len(polygon) for k in range(3))
    for corner in polygon:
        for step in (0.25, 0.5, 0.75):
            points.append(tuple(centre[k]+(corner[k]-centre[k])*step for k in range(3)))
    return points


def protrusion(piece, parents, direction):
    """How far `piece` stands out of `parents` where they are closest, or None."""
    axis = AXES[direction]
    nearest = None
    for polygon in piece.faces_towards(axis):
        for point in face_samples(polygon, SAMPLE_PITCH):
            inward = (-axis[0], -axis[1], -axis[2])
            for parent in parents:
                reach = parent.entry(point, inward)
                if reach is not None and (nearest is None or reach < nearest):
                    nearest = reach
    return nearest


def check_anchoring(mesh):
    """Refuse to write a machine with a part floating off the thing it is bolted to."""
    pieces = [Piece(group, what, polygons) for group, what, polygons in mesh.solids]
    problems = []
    checked = 0
    for piece in pieces:
        key = (piece.group, piece.what)
        if key in ROOTS:
            continue
        if key not in ATTACHMENTS:
            problems.append("%s says nothing about what holds it up; add it to "
                            "ATTACHMENTS or to ROOTS"%piece.name())
            continue
        mount = ATTACHMENTS[key]
        parents = [other for other in pieces if other is not piece
                   and other.what in mount.parents
                   and frame_of(other.group)==frame_of(piece.group)]
        if not parents:
            problems.append("%s is bolted to %s, and there is no such part in its frame"
                            % (piece.name(), " or ".join(mount.parents)))
            continue
        checked += 1
        gap = min(separation(piece, parent) for parent in parents)
        if gap > CONTACT_TOL:
            problems.append("%s stands %.3f units off %s -- it is a part floating in "
                            "mid-air"%(piece.name(), gap, " or ".join(mount.parents)))
            continue
        #  A direction with nothing under it is not measured: one of a mirrored pair faces
        #  the parent's far side, and only its outboard face is a face of the machine.  A
        #  part with nothing under *any* of the faces it names is a different matter --
        #  either it is buried or it is not where it says it is.
        measured = {direction: protrusion(piece, parents, direction)
                    for direction in mount.faces}
        if mount.faces and all(stands is None for stands in measured.values()):
            problems.append("%s has nothing under its %s face; either it is sunk inside "
                            "%s or it is nowhere near it"
                            % (piece.name(), " or ".join(mount.faces),
                               " or ".join(mount.parents)))
        for direction, stands in sorted(measured.items()):
            if stands is not None and abs(stands-mount.proud) > PROUD_TOL:
                problems.append(
                    "%s stands %.3f units out of %s on its %s face and claims %.3f -- "
                    "%s"%(piece.name(), stands, " or ".join(mount.parents), direction,
                          mount.proud,
                          "there is air under it" if stands > mount.proud
                          else "it is sunk into the steel"))
    for piece in pieces:
        for other in pieces:
            if piece is not other and frame_of(piece.group)==frame_of(other.group) \
                    and other.contains(piece):
                problems.append("%s is entirely inside %s, so it is drawn every frame and "
                                "never seen"%(piece.name(), other.name()))
    if problems:
        raise SystemExit("\n".join(
            ["%d parts of the machine are not attached to what they say they are:"
             % len(problems)]+["  "+line for line in problems[:40]]
            + (["  ... and %d more"%(len(problems)-40)] if len(problems) > 40 else [])
            + ["Seat the part against its parent's surface *where the part is* -- a beam",
               "that tapers is narrower there than the number it was built from -- and",
               "stand it out by what its Mount claims.  See the note on BOSS_PROUD."]))
    return checked


def build_model():
    mesh = Mesh()
    volume = build_undercarriage(mesh)
    volume += build_tracks(mesh)
    volume += build_house(mesh)
    volume += build_boom(mesh)
    volume += build_stick(mesh)
    volume += build_bucket(mesh)
    volume += build_breaker(mesh)
    volume += build_grapple(mesh)
    return mesh, volume


# ---------------------------------------------------------------------------
# The atlas
# ---------------------------------------------------------------------------
def check_layout():
    used = {}
    for name, (x, y, w, h) in sorted(REGIONS.items()):
        if x < 0 or y < 0 or x+w > SHEET or y+h > SHEET:
            raise SystemExit("region %s leaves the %dx%d sheet" % (name, SHEET, SHEET))
        for yy in range(y, y+h):
            for xx in range(x, x+w):
                if (xx, yy) in used:
                    raise SystemExit(
                        "regions %s and %s overlap at %d,%d -- nothing at runtime "
                        "reports that, it simply paints one part with the other's "
                        "texture" % (used[(xx, yy)], name, xx, yy))
                used[(xx, yy)] = name
    return len(used)


def rect(px, x0, y0, x1, y1, colour):
    for y in range(int(y0), int(y1)+1):
        for x in range(int(x0), int(x1)+1):
            if 0 <= x < SHEET and 0 <= y < SHEET:
                px[x, y] = colour


def paint_panel(px, region, base, seed, pitch=16):
    """
    Panelled steel, painted as a *repeating* pattern rather than as a framed panel.

    Every face takes a sub-rectangle from wherever it happens to fit, so a border drawn
    round the region would come out as a line across the middle of some face somewhere.
    A seam grid does the same job -- it says "panel" at any crop -- and what wear there is
    is seeded so the checked-in sheet is the same file every run.

    **This is the fix for "very harsh lines in the textures".**  Three things were painting
    it that way and all three are gone:

      * a full-width dark row every fourth line and a light one every eighth, which is not
        panelling at all.  At a texel to the unit that is a stripe every quarter of a foot
        down every painted surface on the machine, and it is what made the bodywork read as
        crossed planks rather than as sheet steel;
      * seams drawn in a shade three or four steps off the base -- YELLOW_SHADE against
        YELLOW, or OUTLINE, which is very nearly black -- where a join in a painted panel
        catches perhaps a tenth less light than the panel does.  They are drawn with
        shaded() now, so the contrast is a property of the paint rather than a palette
        entry picked by eye;
      * a scuff on one texel in ninety, in three tones, plus rust.  Forty-five bright
        specks on a 64x64 region reads as noise at two blocks and as dirt at ten.  What is
        left is a quarter of that, all of it within a shade of the base.
    """
    x, y, w, h = REGIONS[region]
    rng = random.Random(seed)
    seam = shaded(base, SEAM)
    bolt = shaded(base, BOLT)
    rect(px, x, y, x+w-1, y+h-1, base)
    # Seams at the pitch a real panel is built at, with bolts down them: the one thing
    # that gives a large flat face a sense of scale.
    for row in range(y, y+h, pitch):
        rect(px, x, row, x+w-1, row, seam)
        for col in range(x+3, x+w, 8):
            px[col, row] = bolt
    for col in range(x, x+w, pitch):
        rect(px, col, y, col, y+h-1, seam)
    for _ in range(w*h//360):
        sx, sy = rng.randrange(x, x+w), rng.randrange(y, y+h)
        px[sx, sy] = shaded(base, SCUFF if rng.random() < 0.5 else BOLT)


def paint_boss(px):
    """
    A pin seen end on: rings about the centre of the region, because that is where a
    cylinder's cap is mapped from.

    **The dark cross-hatched discs a playtester reported were this region's fault as much
    as the geometry's.**  Every cap on the machine took its texels radially from the middle
    of "plate", and the middle of "plate" is where a seam row crosses a seam column -- so
    every pin, every boss and the slew ring wore a dark cross through its face.  Rings are
    the only pattern that survives being sampled that way, and they happen to be what a
    machined pin face looks like.

    Sized for every disc that uses it, from a tool pin at 2.4 to the slew ring at 13: past
    the boss itself it is plain plate, so a big disc is a plate with a boss in the middle
    and a small one is all boss.
    """
    x, y, w, h = REGIONS["boss"]
    cx, cy = x+w/2-0.5, y+h/2-0.5
    shoulder = shaded(IRON, 0.74)
    for row in range(y, y+h):
        for col in range(x, x+w):
            r = math.hypot(col-cx, row-cy)
            if r < 1.6:
                colour = STEEL_LIT           # the pin's own end, turned bright
            elif r < 2.7:
                colour = STEEL
            elif r < 3.3:
                colour = shoulder            # the step down from the pin to its boss
            elif 7.0 <= r < 7.6:
                colour = shoulder            # and the edge of the boss itself, for the
            else:                            # discs big enough to have one: the slew ring
                colour = IRON
            px[col, row] = colour
    #  Six bolts round the boss, which is what holds a retaining plate on.
    for i in range(6):
        angle = 2*math.pi*i/6
        bx, by = int(round(cx+4.8*math.cos(angle))), int(round(cy+4.8*math.sin(angle)))
        px[bx, by] = STEEL_LIT
        px[bx, by+1] = shoulder


def paint_hazard(px):
    x, y, w, h = REGIONS["hazard"]
    for row in range(y, y+h):
        for col in range(x, x+w):
            px[col, row] = HAZARD if ((col-x)+(row-y))%12 < 6 else YELLOW_DARK
    #  The band between one hazard panel and the next.  It was OUTLINE, which against the
    #  cream of the stripes is the hardest line anywhere on the machine; the stripes
    #  themselves are the contrast this face is for and the join does not need to compete.
    for row in range(y, y+h, 16):
        rect(px, x, row, x+w-1, row, YELLOW_SHADE)


def paint_vent(px, region, base, dark, pitch, seed):
    paint_panel(px, region, base, seed)
    x, y, w, h = REGIONS[region]
    #  A louvre is a real slot in real steel, so this is the one place a near-black line
    #  is the truth.  It is a slot two texels deep rather than a line across a panel.
    for row in range(y+1, y+h-1, pitch):
        rect(px, x, row, x+w-1, row, shaded(base, 0.45))
        rect(px, x, row+1, x+w-1, row+1, dark)


def paint_grille(px):
    x, y, w, h = REGIONS["grille"]
    rect(px, x, y, x+w-1, y+h-1, STEEL_DARK)
    for row in range(y, y+h, 4):
        rect(px, x, row, x+w-1, row, OUTLINE)
    for col in range(x, x+w, 4):
        rect(px, col, y, col, y+h-1, STEEL)
    for row in range(y+2, y+h, 4):
        rect(px, x, row, x+w-1, row, IRON)


def paint_glass(px):
    """
    The cab glazing: a tint you can see through, not a blue-grey wall.

    **This is the region the whole blended pass exists for.**  Every other colour on the
    sheet is opaque and the renderer draws it with blending off; this one is painted at
    GLASS_ALPHA and its group is drawn last with blending on.  Painting it opaque was the
    reported bug -- the panes were real geometry with real holes around them and the
    operator still could not see out, because a fully opaque texel is opaque however it
    is blended.
    """
    x, y, w, h = REGIONS["glass"]
    rect(px, x, y, x+w-1, y+h-1, GLASS)
    # A diagonal highlight, which is the whole of what makes flat colour read as glass.
    for row in range(y, y+h):
        for col in range(x, x+w):
            d = (col-x)+(row-y)
            if d%17 < 3:
                px[col, row] = GLASS_LIT
            elif d%17 < 5:
                px[col, row] = GLASS_DARK
    # The seals.  Every glazed face takes its sub-rectangle from this region's top left
    # corner, so a line down the top and left edges of the region is a glazing bar along
    # the top and leading edge of every pane -- and being the more opaque thing on the
    # region, it is what makes the pane read as glazed into a frame.
    for row in range(y, y+h, 16):
        rect(px, x, row, x+w-1, row, GLASS_SEAL)
    for col in range(x, x+w, 16):
        rect(px, col, y, col, y+h-1, GLASS_SEAL)


def paint_hub(px):
    """A wheel seen face on: a rim, a hub, and the bolts between them."""
    x, y, w, h = REGIONS["hub"]
    cx, cy = x+w/2-0.5, y+h/2-0.5
    for row in range(y, y+h):
        for col in range(x, x+w):
            r = math.hypot(col-cx, row-cy)
            angle = math.atan2(row-cy, col-cx)
            if r > 14:
                px[col, row] = STEEL_DARK
            elif r > 12:
                px[col, row] = STEEL_LIT if (angle*8)%2 < 1 else STEEL
            elif r > 5:
                px[col, row] = IRON if int(angle*3+r)%2 else STEEL_DARK
            elif r > 3.5:
                px[col, row] = STEEL_LIT
            else:
                px[col, row] = STEEL
    for i in range(6):
        angle = 2*math.pi*i/6
        px[int(cx+8.5*math.cos(angle)), int(cy+8.5*math.sin(angle))] = OUTLINE


def paint_rim(px):
    """The rolling surface: a band of teeth, so the sprocket has something to drive on."""
    x, y, w, h = REGIONS["rim"]
    rect(px, x, y, x+w-1, y+h-1, IRON)
    rect(px, x, y, x+w-1, y, STEEL_LIT)
    rect(px, x, y+h-1, x+w-1, y+h-1, STEEL_DARK)
    for col in range(x, x+w, 3):
        rect(px, col, y+1, col, y+h-2, STEEL_DARK)
        rect(px, col+1, y+2, col+1, y+h-3, STEEL_LIT)


def paint_belt(px):
    """
    The tread strip: cleats across the belt, repeating every BELT_PERIOD rows.

    **The repeat is load bearing.**  The renderer scrolls this group's UVs by a distance
    wrapped into one period, and that only looks continuous if row n and row
    n+BELT_PERIOD are the same row -- otherwise the tread would visibly jump once per
    period, twenty times a second at speed.
    """
    x, y, w, h = REGIONS["belt"]
    tread = int(TRACK_X1-TRACK_X0)
    for row in range(y, y+h):
        phase = (row-y)%BELT_PERIOD
        for col in range(x, x+w):
            if col-x < tread:
                if phase==0:
                    colour = OUTLINE
                elif phase in (1, 2):
                    colour = RUBBER_LIT
                elif phase==3:
                    colour = RUBBER
                else:
                    colour = RUBBER if (col-x)%4 else RUBBER_LIT
                # The guide horns down the middle of a real track's cleats.
                if phase in (1, 2, 3) and tread//2-1 <= col-x <= tread//2+1:
                    colour = STEEL_DARK if phase==1 else IRON
            else:
                colour = OUTLINE if phase==0 else RUBBER
            px[col, row] = colour


def paint_nameplate(px):
    """"LD", raised on a steel plate.  The machine is nobody's brand but this fork's."""
    x, y, w, h = REGIONS["nameplate"]
    rect(px, x, y, x+w-1, y+h-1, STEEL_DARK)
    rect(px, x+1, y+1, x+w-2, y+h-2, STEEL)
    rect(px, x, y, x+w-1, y, STEEL_LIT)
    glyphs = {
        "L": ["10000", "10000", "10000", "10000", "10000", "10000", "11111"],
        "D": ["11110", "10001", "10001", "10001", "10001", "10001", "11110"],
    }
    for index, letter in enumerate("LD"):
        ox = x+4+index*7
        oy = y+4
        for row, bits in enumerate(glyphs[letter]):
            for col, bit in enumerate(bits):
                if bit=="1":
                    px[ox+col, oy+row] = YELLOW_LIT
    # A row of rivets down the far end, so the plate is not floating.
    for i in range(3):
        px[x+w-4, y+4+i*3] = STEEL_LIT


def paint_edge(px):
    """Wear steel: bright where it has been dragged through the ground."""
    x, y, w, h = REGIONS["edge"]
    rect(px, x, y, x+w-1, y+h-1, IRON)
    for row in range(y, y+h):
        t = (row-y)/max(1, h-1)
        rect(px, x, row, x+w-1, row,
             STEEL_LIT if t > 0.75 else STEEL if t > 0.4 else STEEL_DARK)
    for col in range(x, x+w, 5):
        rect(px, col, y, col, y+h-1, STEEL_DARK)
        rect(px, col+1, y+h//2, col+1, y+h-1, STEEL_LIT)


def paint_walkway(px):
    """Chequer plate.  Every surface anybody is meant to stand on wears it."""
    x, y, w, h = REGIONS["walkway"]
    rect(px, x, y, x+w-1, y+h-1, STEEL)
    for row in range(y, y+h, 4):
        for col in range(x, x+w, 4):
            offset = 2 if ((row-y)//4)%2 else 0
            cx = col+offset
            if cx+2 < x+w:
                px[cx, row] = STEEL_LIT
                px[cx+1, row] = STEEL_LIT
                if row+1 < y+h:
                    px[cx, row+1] = STEEL_DARK
                    px[cx+1, row+1] = STEEL_DARK


def paint_lamp(px):
    x, y, w, h = REGIONS["lamp"]
    rect(px, x, y, x+w-1, y+h-1, STEEL_DARK)
    rect(px, x+1, y+1, x+w-2, y+h-2, LAMP)
    for col in range(x+2, x+w-2, 3):
        rect(px, col, y+2, col, y+h-3, (255, 248, 210, 255))
    rect(px, x, y, x+w-1, y, STEEL_LIT)


def paint_trim(px):
    x, y, w, h = REGIONS["trim"]
    rect(px, x, y, x+w-1, y+h-1, OUTLINE)
    for row in range(y+1, y+h-1, 3):
        rect(px, x+1, row, x+w-2, row, RUBBER_LIT)


def paint_cowl(px):
    """The engine cowl's flank: a hatch, its hinges, and two banks of louvres."""
    paint_panel(px, "cowl", YELLOW, 41)
    x, y, w, h = REGIONS["cowl"]
    edge_lit, edge_dark = shaded(YELLOW, BOLT), shaded(YELLOW, SEAM)
    # The hatch, most of the flank, with a handle.  Its outline is the join round a door
    # in a painted panel: a shade lighter along the top and left, a shade darker along the
    # bottom and right, which is what an edge in one flat colour looks like.
    rect(px, x+4, y+8, x+w-5, y+h-9, YELLOW)
    for col in range(x+4, x+w-4):
        px[col, y+8] = edge_lit
        px[col, y+h-9] = edge_dark
    for row in range(y+8, y+h-8):
        px[x+4, row] = edge_lit
        px[x+w-5, row] = edge_dark
    for row in (y+14, y+h-15):
        rect(px, x+6, row, x+11, row+1, STEEL_DARK)
    rect(px, x+w-12, y+h//2-1, x+w-8, y+h//2+1, STEEL)
    px[x+w-12, y+h//2] = STEEL_LIT
    # Louvres, because the machine has a radiator behind this.  A louvre is a slot rather
    # than a line, so it keeps its dark -- but in the livery's own olive, not in black.
    for row in range(y+20, y+h-20, 4):
        rect(px, x+14, row, x+w-16, row, YELLOW_SHADE)
        rect(px, x+14, row+1, x+w-16, row+1, YELLOW_DARK)


def paint_cab_side(px):
    """The cab's outer skin: the door, its window frame and the grab handle."""
    paint_panel(px, "cab_side", YELLOW, 77)
    x, y, w, h = REGIONS["cab_side"]
    edge_lit, edge_dark = shaded(YELLOW, BOLT), shaded(YELLOW, SEAM)
    rect(px, x+3, y+3, x+w-4, y+h-4, YELLOW)
    for col in range(x+3, x+w-3):
        px[col, y+3] = edge_lit
        px[col, y+h-4] = edge_dark
    for row in range(y+3, y+h-3):
        px[x+3, row] = edge_lit
        px[x+w-4, row] = edge_dark
    # The window in the door, and the seal round it.  Opaque: this is the door's steel
    # skin, and a see-through texel here is a hole in the bodywork rather than a window.
    rect(px, x+8, y+8, x+w-9, y+h//2, opaque(GLASS))
    for col in range(x+8, x+w-8):
        px[col, y+8] = opaque(GLASS_LIT)
    for row in range(y+8, y+h//2+1):
        px[x+8, row] = opaque(GLASS_LIT)
        px[x+w-9, row] = opaque(GLASS_DARK)
    rect(px, x+7, y+7, x+w-8, y+7, STEEL_DARK)
    rect(px, x+7, y+h//2+1, x+w-8, y+h//2+1, STEEL_DARK)
    # The grab handle beside it.
    rect(px, x+w-7, y+10, x+w-6, y+h//2-2, STEEL_LIT)
    px[x+w-7, y+10] = STEEL_DARK
    px[x+w-7, y+h//2-2] = STEEL_DARK


def paint_counterweight(px):
    """The counterweight's flank: cast, ribbed, and lifted by two shackles."""
    paint_panel(px, "counterweight", YELLOW, 13)
    x, y, w, h = REGIONS["counterweight"]
    for row in range(y+6, y+h-6, 10):
        rect(px, x+4, row, x+w-5, row, shaded(YELLOW, SEAM))
        rect(px, x+4, row+1, x+w-5, row+1, shaded(YELLOW, BOLT))
    rect(px, x+w//2-6, y+2, x+w//2+5, y+5, STEEL_DARK)
    rect(px, x+w//2-5, y+3, x+w//2+4, y+3, STEEL_LIT)


def paint_bucket(px):
    """The bucket's back: three wear strips and the ribs between them."""
    x, y, w, h = REGIONS["bucket"]
    rect(px, x, y, x+w-1, y+h-1, IRON)
    for row in range(y, y+h):
        if (row-y)%3==0:
            rect(px, x, row, x+w-1, row, STEEL_DARK)
    for col in range(x+6, x+w-6, 14):
        rect(px, col, y, col+2, y+h-1, STEEL)
        rect(px, col, y, col, y+h-1, STEEL_LIT)
        rect(px, col+2, y, col+2, y+h-1, OUTLINE)
    for row in range(y, y+h, 21):
        rect(px, x, row, x+w-1, row, RUST)


def build_sheet():
    img = Image.new("RGBA", (SHEET, SHEET), (0, 0, 0, 0))
    px = img.load()
    paint_panel(px, "yellow", YELLOW, 1)
    paint_panel(px, "yellow_dark", YELLOW_DARK, 2)
    paint_panel(px, "steel", STEEL, 3)
    paint_panel(px, "steel_dark", STEEL_DARK, 4)
    #  Machined plate is not panelled: it is rolled steel that has been cut and drilled,
    #  and the pins, links and rails cut from it are small enough that a seam grid at any
    #  pitch lands as a stripe across them.  A 32 pitch is one line at the far edge of the
    #  largest face that takes it, and nothing at all on most.
    paint_panel(px, "plate", IRON, 5, pitch=32)
    paint_boss(px)
    paint_hazard(px)
    paint_vent(px, "vent", STEEL, STEEL_DARK, 4, 6)
    paint_grille(px)
    paint_glass(px)
    paint_hub(px)
    paint_rim(px)
    paint_belt(px)
    paint_nameplate(px)
    paint_edge(px)
    paint_walkway(px)
    paint_lamp(px)
    paint_trim(px)
    paint_cowl(px)
    paint_cab_side(px)
    paint_counterweight(px)
    paint_bucket(px)
    check_glazing(px)
    return img


def check_glazing(px):
    """
    Exactly one region is see-through, and it is see-through everywhere.

    Both halves matter and neither is reported by anything at runtime.  A fully opaque
    glazing region is the bug this was written for: the renderer blends the glass group
    faithfully and the operator still cannot see out.  A partly transparent texel
    anywhere else is the opposite failure -- the machine drawn with a hole in it, or a
    panel dimmed by whatever happens to be behind it, depending on which pass eats it.
    """
    for name, (x, y, w, h) in sorted(REGIONS.items()):
        glazed = name=="glass"
        for row in range(y, y+h):
            for col in range(x, x+w):
                alpha = px[col, row][3]
                if glazed and not 0 < alpha < 255:
                    raise SystemExit(
                        "the glazing at %d,%d has an alpha of %d; the cab windows have "
                        "to be seen out of and %s" % (col, row, alpha,
                                                      "0 is a hole" if alpha==0
                                                      else "255 is a wall"))
                if not glazed and alpha!=255:
                    raise SystemExit(
                        "region %s at %d,%d has an alpha of %d; only the glazing may be "
                        "see-through, and everything else is drawn in a pass that does "
                        "not blend" % (name, col, row, alpha))


def build_item():
    """
    The carried item: a small side-on silhouette of the machine.

    Drawn rather than rendered from the model.  An item icon baked off a 3D model at
    sixteen pixels turns to mush, and the thing a player needs from this icon is only
    "that is the digger".
    """
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    for x in range(1, 13):
        px[x, 12] = STEEL_LIT
        px[x, 13] = STEEL
        px[x, 14] = OUTLINE
    for x in (2, 5, 8, 11):
        px[x, 13] = STEEL_DARK
    px[1, 13] = IRON
    px[12, 13] = IRON
    rect(px, 4, 9, 11, 11, YELLOW)
    rect(px, 4, 9, 11, 9, YELLOW_LIT)
    rect(px, 11, 9, 12, 11, HAZARD)
    rect(px, 8, 6, 11, 8, STEEL)
    #  Opaque: an item icon is composited by the GUI, not by the crawler's renderer.
    rect(px, 9, 7, 10, 7, opaque(GLASS_LIT))
    px[8, 6] = STEEL_LIT
    for i in range(4):
        px[7-i, 8-i] = YELLOW
        px[8-i, 8-i] = YELLOW_DARK
    for i in range(3):
        px[3, 5+i] = YELLOW
    rect(px, 1, 7, 3, 8, IRON)
    px[1, 8] = OUTLINE
    px[0, 8] = STEEL_LIT
    return img


# ---------------------------------------------------------------------------
# Output
# ---------------------------------------------------------------------------
def write_obj(mesh, path):
    lines = ["# The Hydraulic Crawler.",
             "# Generated by docs/tools/make_crawler_obj.py -- do not hand-edit.",
             "#",
             "# Model space, not world space: sixteen units to the block, +Y downwards,",
             "# -Z along the arm.  Every group is authored about its own pivot, and the",
             "# pivots live in ModelHydraulicCrawler.",
             "mtllib hydraulic_crawler.mtl", ""]
    for p in mesh.positions:
        lines.append("v %.4f %.4f %.4f"%p)
    lines.append("")
    for uv in mesh.uvs:
        lines.append("vt %.6f %.6f"%uv)
    for name, faces in mesh.groups:
        lines.append("")
        lines.append("o "+name)
        lines.append("usemtl "+MTL)
        for face in faces:
            lines.append("f "+" ".join("%d/%d"%vt for vt in face))
    with open(path, "w", newline="\n") as handle:
        handle.write("\n".join(lines)+"\n")


def write_mtl(path):
    body = ["# Generated by docs/tools/make_crawler_obj.py -- do not hand-edit",
            "# One material: an entity is drawn with one bound texture and the renderer",
            "# binds it. This is here so the model opens in a viewer looking like itself.",
            "newmtl "+MTL,
            "map_Kd ../../textures/entity/hydraulic_crawler.png"]
    with open(path, "w", newline="\n") as handle:
        handle.write("\n".join(body)+"\n")


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    repo = os.path.dirname(os.path.dirname(here))
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets", default=os.path.join(
        repo, "src", "main", "resources", "assets", "immersiveengineering"))
    args = parser.parse_args()

    used = check_layout()
    mesh, volume = build_model()
    checked = check_coplanar(mesh)
    anchored = check_anchoring(mesh)

    model_dir = os.path.join(args.assets, "models", "entity")
    os.makedirs(model_dir, exist_ok=True)
    obj_path = os.path.join(model_dir, "hydraulic_crawler.obj")
    write_obj(mesh, obj_path)
    write_mtl(os.path.join(model_dir, "hydraulic_crawler.mtl"))
    print("wrote %s"%os.path.relpath(obj_path, repo))
    print("  %d groups, %d quads, %d vertices, %.0f cubic units of steel"
          % (len(mesh.groups), mesh.quads, len(mesh.positions), volume))
    for name, faces in mesh.groups:
        print("    %-16s %4d quads"%(name, len(faces)))
    print("  %d faces checked, no two of them fighting over a plane"%checked)
    print("  %d attached parts checked, every one of them seated on the part it names"
          % anchored)

    entity_dir = os.path.join(args.assets, "textures", "entity")
    os.makedirs(entity_dir, exist_ok=True)
    sheet_path = os.path.join(entity_dir, "hydraulic_crawler.png")
    build_sheet().save(sheet_path, "PNG", optimize=True)
    print("wrote %s"%os.path.relpath(sheet_path, repo))

    item_path = os.path.join(args.assets, "textures", "items", "hydraulic_crawler.png")
    build_item().save(item_path, "PNG", optimize=True)
    print("wrote %s"%os.path.relpath(item_path, repo))

    print("%d regions, %d of %d sheet pixels used, no overlaps"
          % (len(REGIONS), used, SHEET*SHEET))


if __name__=="__main__":
    main()
