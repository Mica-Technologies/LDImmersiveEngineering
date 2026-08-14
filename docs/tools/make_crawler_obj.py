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
    drawn at.

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
GLASS = (74, 104, 120, 255)
GLASS_LIT = (108, 146, 164, 255)
GLASS_DARK = (48, 70, 84, 255)
HAZARD = (232, 226, 214, 255)
LAMP = (248, 226, 140, 255)
RUST = (128, 84, 46, 255)

SHEET = 256

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
}

# The belt strip repeats every BELT_PERIOD pixels down its length, and every quad of
# the belt is drawn from the *top* BELT_SEG_MAX pixels of it with a scroll offset of up
# to one period added.  So the strip has to be at least one period plus one segment
# tall, and check_belt() says so.
BELT_PERIOD = 8

# ---------------------------------------------------------------------------
# Geometry, in model units.  See the module docstring for the axes.
#
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
        self.quads = 0

    def group(self, name):
        if name in self.by_name:
            raise SystemExit("group %s declared twice" % name)
        faces = []
        self.groups.append((name, faces))
        self.by_name[name] = faces
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

    def add(self, faces, points, uvs):
        faces.append([(self._pos(p), self._uv(uv)) for p, uv in zip(points, uvs)])
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
        self.mesh.add(self.faces, points, [obj_uv(uv) for uv in uvs])

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


def add_cylinder(mesh, faces, what, x0, x1, cz, cy, radius, count, side, cap,
                 twist=0.0):
    """
    A wheel, a pin boss, an exhaust stack: a prism about the X axis.

    The caps are fanned into quads rather than left as one many-sided face because the
    renderer draws quads and nothing else -- one format, one loop, no triangle path to
    get wrong.  An even facet count is what makes that fan come out exact.
    """
    if count%2:
        raise SystemExit("%s: %d facets; the cap fan needs an even count" % (what, count))
    solid = Solid(mesh, faces, what)
    ring = []
    for i in range(count):
        angle = twist+2*math.pi*i/count
        ring.append((cz+radius*math.sin(angle), cy+radius*math.cos(angle)))
    arc = 2*math.pi*radius/count
    for i in range(count):
        (z0, y0), (z1, y1) = ring[i], ring[(i+1)%count]
        outward = ((z0+z1)/2-cz, (y0+y1)/2-cy)
        solid.quad([(x0, y0, z0), (x0, y1, z1), (x1, y1, z1), (x1, y0, z0)],
                   uv_rect(side, x1-x0, arc),
                   (0, outward[1], outward[0]), side)
    # The caps, radially mapped so the hub's art lands square on the wheel.
    rx, ry, rw, rh = REGIONS[cap]
    hub = (rx+rw/2, ry+rh/2)

    def cap_uv(z, y):
        return hub[0]+(z-cz), hub[1]+(y-cy)

    for i in range(0, count-2, 2):
        a, b, c, d = ring[0], ring[i+1], ring[i+2], ring[(i+3)%count]
        solid.quad([(x1, a[1], a[0]), (x1, b[1], b[0]), (x1, c[1], c[0]),
                    (x1, d[1], d[0])],
                   [cap_uv(*p) for p in (a, b, c, d)], (1, 0, 0), cap)
        solid.quad([(x0, d[1], d[0]), (x0, c[1], c[0]), (x0, b[1], b[0]),
                    (x0, a[1], a[0])],
                   [cap_uv(*p) for p in (d, c, b, a)], (-1, 0, 0), cap)
    return solid.finish()


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
    volume += add_cylinder(mesh, faces, "slew ring", -13, 13, 0, -11.5, 13, 12,
                           "plate", "plate", twist=math.pi/12)
    volume += add_cylinder(mesh, faces, "slew collar", -10, 10, 0, -14.0, 10, 12,
                           "plate", "plate", twist=math.pi/12)

    # A track frame per side: the beam the wheels hang off, inside the belt loop.
    for sign in (-1, 1):
        x0, x1 = sorted((sign*(TRACK_X0+1), sign*(TRACK_X1-1)))
        volume += add_frustum(mesh, faces, "track frame", -WHEEL_Z, WHEEL_Z,
                              (x0, -9.5, x1, -2.5), (x0, -9.5, x1, -2.5),
                              sides("steel", top="steel", bottom="steel_dark"))
        # The guard over the top run, which is where the mud goes.
        volume += add_box(mesh, faces, "track guard",
                          (min(x0, x1)-1, -13.5, -12, max(x0, x1)+1, -11.5, 12),
                          sides("yellow", top="yellow", bottom="yellow_dark"))
        # A tensioner block at the idler, so the front of the frame is not a bare end.
        volume += add_box(mesh, faces, "tensioner",
                          (min(x0, x1), -8, -WHEEL_Z-3, max(x0, x1), -4, -WHEEL_Z+1),
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
        volume += add_belt(mesh, faces, "belt "+side, x0, x1)
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
    volume += add_box(mesh, faces, "radiator grating", (-12, -15, 0, -2, -14, 10),
                      sides("vent", top="grille", bottom="steel_dark"))
    # The front deck, lower, so the boom and its ram are not buried in bodywork.
    volume += add_box(mesh, faces, "front deck", (-14, -9, -18, 0, -3, -2),
                      sides("yellow", top="walkway", bottom="yellow_dark"))
    # A toolbox filling the corner behind the cab.
    volume += add_box(mesh, faces, "toolbox", (1, -9, 5, 14, -3, 12),
                      sides("yellow", top="walkway", bottom="yellow_dark"))

    # The ears the boom is pinned between, straddling the pivot.
    for x0, x1 in ((-9.5, -6.5), (-3.5, -0.5)):
        volume += add_frustum(mesh, faces, "boom ear", -19, -8,
                              (x0, -13, x1, -3), (x0, -13, x1, -3),
                              sides("plate", bottom="steel_dark"))

    # The exhaust stack and its rain cap.
    volume += add_cylinder(mesh, faces, "exhaust", -10, -6, 4, -20, 2, 8,
                           "steel", "steel_dark")
    volume += add_cylinder(mesh, faces, "exhaust cap", -10.5, -5.5, 4, -21, 2.6, 8,
                           "steel_dark", "steel_dark")

    volume += build_cab(mesh, faces)
    volume += build_handrails(mesh, faces)
    return volume


def build_cab(mesh, faces):
    """
    The operator's cab: a steel frame with glass in the holes, not a painted box.

    **The openings are real geometry.**  The old cab was one box wearing a texture with
    glazing painted on its middle band, which at this scale reads as a sticker; four
    posts, a roof, a floor and separate panes read as a cab because the light gets
    through the gaps between them.  The seat is inside it -- CrawlerGeometry.CAB_* is
    read off these numbers, and a test asserts the operator ends up between these walls.
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
    volume += add_box(mesh, faces, "cab back",
                      (CAB_X0, CAB_TOP+2, CAB_Z1-2, CAB_X1, CAB_FLOOR, CAB_Z1),
                      sides("yellow", ends="cab_side"))
    # The inner wall, half height: a rail to lean on, and clear glass above it so the
    # operator can see the arm they are working with.
    volume += add_box(mesh, faces, "cab inner wall",
                      (CAB_X0, -24, CAB_Z0+2, CAB_X0+1.5, CAB_FLOOR, CAB_Z1-2), "yellow")
    volume += add_box(mesh, faces, "cab inner glass",
                      (CAB_X0+0.4, CAB_TOP+2, CAB_Z0+2, CAB_X0+1.1, -24, CAB_Z1-2),
                      "glass")
    # The windscreen, which on an excavator runs the full height of the front.
    volume += add_box(mesh, faces, "windscreen",
                      (CAB_X0+2, CAB_TOP+2, CAB_Z0+0.4, CAB_X1-2, CAB_FLOOR, CAB_Z0+1.1),
                      "glass")
    # The door: glazed above the handle, panelled below it.
    volume += add_box(mesh, faces, "door glass",
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
    # A beacon, because everything on a site has one.
    volume += add_cylinder(mesh, faces, "beacon", 7, 9, CAB_Z1-2, CAB_TOP-1, 1.2, 8,
                           "lamp", "steel_dark")
    return volume


def build_handrails(mesh, faces):
    """The rails round the deck.  Small, and they do more for the silhouette than the
    counterweight does: they are the only thin thing on an otherwise slab-sided machine."""
    volume = 0.0
    rail_y = -12.0
    for z in (-16, -6):
        volume += add_box(mesh, faces, "rail post", (-13.5, rail_y, z, -12.5, -3, z+1),
                          "plate")
    volume += add_box(mesh, faces, "rail", (-13.5, rail_y, -16, -12.5, rail_y+1, -5),
                      "plate")
    for z in (2, 10):
        volume += add_box(mesh, faces, "rail post", (-13.5, rail_y, z, -12.5, -3, z+1),
                          "plate")
    volume += add_box(mesh, faces, "rail", (-13.5, rail_y, 2, -12.5, rail_y+1, 11),
                      "plate")
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
    """
    faces = mesh.group("boom")
    volume = 0.0
    volume += add_frustum(mesh, faces, "boom beam", -BOOM_LENGTH, 2,
                          (-3.5, -4.0, 3.5, 4.0), (-4.5, -5.0, 4.5, 5.0),
                          sides("yellow", top="yellow", bottom="yellow_dark"))
    # The pin at each end, wider than the beam so the joint reads as pinned rather
    # than as two boxes that happen to touch.
    volume += add_cylinder(mesh, faces, "boom foot pin", -5.5, 5.5, 0, 0, 4.5, 12,
                           "plate", "plate")
    volume += add_cylinder(mesh, faces, "boom head pin", -4.5, 4.5, -BOOM_LENGTH, 0,
                           3.5, 12, "plate", "plate")
    # The ram that folds the stick, lying along the top of the boom.
    volume += add_cylinder(mesh, faces, "stick ram", -2.5, 2.5, -8, -6.5, 2.5, 8,
                           "steel", "steel_dark")
    volume += add_cylinder(mesh, faces, "stick ram rod", -1.6, 1.6, -19, -6.5, 1.4, 8,
                           "plate", "plate")
    volume += add_box(mesh, faces, "ram anchor", (-3, -8, -4, 3, -3, 0), "plate")
    # And the boom's own ram, under it, anchored on the foot.
    volume += add_cylinder(mesh, faces, "boom ram", -2.2, 2.2, -10, 6.0, 2.2, 8,
                           "steel", "steel_dark")
    volume += add_box(mesh, faces, "hose run", (-4.6, -5.5, -22, -3.8, -1.5, -2),
                      "steel_dark")
    return volume


def build_stick(mesh):
    """The stick, and the linkage the tool hangs off."""
    faces = mesh.group("stick")
    volume = 0.0
    volume += add_frustum(mesh, faces, "stick beam", -STICK_LENGTH, 2,
                          (-2.75, -3.0, 2.75, 3.0), (-3.5, -4.0, 3.5, 4.0),
                          sides("yellow", top="yellow", bottom="yellow_dark"))
    volume += add_cylinder(mesh, faces, "stick foot pin", -4.0, 4.0, 0, 0, 3.5, 12,
                           "plate", "plate")
    volume += add_cylinder(mesh, faces, "stick head pin", -3.0, 3.0, -STICK_LENGTH, 0,
                           2.6, 12, "plate", "plate")
    # The tool's ram, on top, and the two link plates down to the pin.
    volume += add_cylinder(mesh, faces, "tool ram", -2.0, 2.0, -5, -5.5, 2.0, 8,
                           "steel", "steel_dark")
    volume += add_cylinder(mesh, faces, "tool ram rod", -1.3, 1.3, -14, -5.5, 1.1, 8,
                           "plate", "plate")
    for x0, x1 in ((-3.2, -2.0), (2.0, 3.2)):
        volume += add_frustum(mesh, faces, "tool link", -STICK_LENGTH+1, -14,
                              (x0, -3.0, x1, 0.5), (x0, -6.0, x1, -3.0), "plate")
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
    # The mount: the ears the pin goes through, and the boss on it.
    volume += add_box(mesh, faces, "bucket mount", (-4, -4, -3, 4, 2, 1), "plate")
    volume += add_cylinder(mesh, faces, "bucket pin", -4.5, 4.5, 0, 0, 2.4, 8,
                           "plate", "plate")
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
    # The sides, which close the shell the slabs left open.
    for x0, x1 in ((-7.0, -5.5), (5.5, 7.0)):
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
    """The hydraulic breaker: a housing, a barrel and a chisel."""
    faces = mesh.group("tool_breaker")
    volume = 0.0
    volume += add_box(mesh, faces, "breaker mount", (-4, -4, -3, 4, 2, 1), "plate")
    volume += add_cylinder(mesh, faces, "breaker pin", -4.5, 4.5, 0, 0, 2.4, 8,
                           "plate", "plate")
    volume += add_frustum(mesh, faces, "breaker housing", -9, -1,
                          (-4.5, -5.0, 4.5, 5.0), (-5.0, -5.5, 5.0, 5.5),
                          sides("yellow", top="yellow", bottom="yellow_dark"))
    volume += add_cylinder(mesh, faces, "breaker barrel", -3.5, 3.5, 0, -12.5, 3.5, 12,
                           "steel", "steel_dark")
    volume += add_frustum(mesh, faces, "chisel", -17, -12,
                          (-1.0, -1.0, 1.0, 1.0), (-2.2, -2.2, 2.2, 2.2),
                          sides("edge", top="edge", bottom="edge"))
    return volume


def build_grapple(mesh):
    """The grapple: a rotator and two jaws, held a little open."""
    faces = mesh.group("tool_grapple")
    volume = 0.0
    volume += add_box(mesh, faces, "grapple mount", (-4, -4, -3, 4, 2, 1), "plate")
    volume += add_cylinder(mesh, faces, "grapple pin", -4.5, 4.5, 0, 0, 2.4, 8,
                           "plate", "plate")
    volume += add_cylinder(mesh, faces, "rotator", -3.0, 3.0, -4.5, 0, 3.0, 12,
                           "steel", "steel_dark")
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


def paint_panel(px, region, base, lit, dark, shade, seed):
    """
    Panelled steel, painted as a *repeating* pattern rather than as a framed panel.

    Every face takes a sub-rectangle from wherever it happens to fit, so a border drawn
    round the region would come out as a line across the middle of some face somewhere.
    A seam grid and a scatter of wear does the same job -- it says "panel" at any crop --
    and the scatter is seeded so the checked-in sheet is the same file every run.
    """
    x, y, w, h = REGIONS[region]
    rng = random.Random(seed)
    rect(px, x, y, x+w-1, y+h-1, base)
    for row in range(y, y+h):
        if (row-y)%4==3:
            rect(px, x, row, x+w-1, row, dark)
        elif (row-y)%8==1:
            rect(px, x, row, x+w-1, row, lit)
    # Seams every sixteen units, with bolts down them: the pitch a real panel is
    # built at, and the thing that gives a large flat face a sense of scale.
    for row in range(y, y+h, 16):
        rect(px, x, row, x+w-1, row, shade)
        for col in range(x+3, x+w, 8):
            px[col, row] = lit
            if row+1 < y+h:
                px[col, row+1] = shade
    for col in range(x, x+w, 16):
        rect(px, col, y, col, y+h-1, shade)
    for _ in range(w*h//90):
        sx, sy = rng.randrange(x, x+w), rng.randrange(y, y+h)
        px[sx, sy] = rng.choice((lit, dark, shade))
    for _ in range(w*h//600):
        sx, sy = rng.randrange(x+1, x+w-3), rng.randrange(y+1, y+h-1)
        rect(px, sx, sy, sx+2, sy, RUST)


def paint_hazard(px):
    x, y, w, h = REGIONS["hazard"]
    for row in range(y, y+h):
        for col in range(x, x+w):
            px[col, row] = HAZARD if ((col-x)+(row-y))%12 < 6 else YELLOW_DARK
    for row in range(y, y+h, 16):
        rect(px, x, row, x+w-1, row, OUTLINE)


def paint_vent(px, region, base, dark, pitch, seed):
    paint_panel(px, region, base, STEEL_LIT, dark, OUTLINE, seed)
    x, y, w, h = REGIONS[region]
    for row in range(y+1, y+h-1, pitch):
        rect(px, x, row, x+w-1, row, OUTLINE)
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
    for row in range(y, y+h, 16):
        rect(px, x, row, x+w-1, row, GLASS_DARK)


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
    paint_panel(px, "cowl", YELLOW, YELLOW_LIT, YELLOW_DARK, YELLOW_SHADE, 41)
    x, y, w, h = REGIONS["cowl"]
    # The hatch, most of the flank, with a handle.
    rect(px, x+4, y+8, x+w-5, y+h-9, YELLOW)
    for col in range(x+4, x+w-4):
        px[col, y+8] = YELLOW_LIT
        px[col, y+h-9] = YELLOW_SHADE
    for row in range(y+8, y+h-8):
        px[x+4, row] = YELLOW_LIT
        px[x+w-5, row] = YELLOW_SHADE
    for row in (y+14, y+h-15):
        rect(px, x+6, row, x+11, row+1, STEEL_DARK)
    rect(px, x+w-12, y+h//2-1, x+w-8, y+h//2+1, STEEL)
    px[x+w-12, y+h//2] = STEEL_LIT
    # Louvres, because the machine has a radiator behind this.
    for row in range(y+20, y+h-20, 4):
        rect(px, x+14, row, x+w-16, row, OUTLINE)
        rect(px, x+14, row+1, x+w-16, row+1, YELLOW_DARK)


def paint_cab_side(px):
    """The cab's outer skin: the door, its window frame and the grab handle."""
    paint_panel(px, "cab_side", YELLOW, YELLOW_LIT, YELLOW_DARK, YELLOW_SHADE, 77)
    x, y, w, h = REGIONS["cab_side"]
    rect(px, x+3, y+3, x+w-4, y+h-4, YELLOW)
    for col in range(x+3, x+w-3):
        px[col, y+3] = YELLOW_LIT
        px[col, y+h-4] = YELLOW_SHADE
    for row in range(y+3, y+h-3):
        px[x+3, row] = YELLOW_LIT
        px[x+w-4, row] = YELLOW_SHADE
    # The window in the door, and the seal round it.
    rect(px, x+8, y+8, x+w-9, y+h//2, GLASS)
    for col in range(x+8, x+w-8):
        px[col, y+8] = GLASS_LIT
    for row in range(y+8, y+h//2+1):
        px[x+8, row] = GLASS_LIT
        px[x+w-9, row] = GLASS_DARK
    rect(px, x+7, y+7, x+w-8, y+7, STEEL_DARK)
    rect(px, x+7, y+h//2+1, x+w-8, y+h//2+1, STEEL_DARK)
    # The grab handle beside it.
    rect(px, x+w-7, y+10, x+w-6, y+h//2-2, STEEL_LIT)
    px[x+w-7, y+10] = OUTLINE
    px[x+w-7, y+h//2-2] = OUTLINE


def paint_counterweight(px):
    """The counterweight's flank: cast, ribbed, and lifted by two shackles."""
    paint_panel(px, "counterweight", YELLOW, YELLOW_LIT, YELLOW_DARK, YELLOW_SHADE, 13)
    x, y, w, h = REGIONS["counterweight"]
    for row in range(y+6, y+h-6, 10):
        rect(px, x+4, row, x+w-5, row, YELLOW_SHADE)
        rect(px, x+4, row+1, x+w-5, row+1, YELLOW_LIT)
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
    paint_panel(px, "yellow", YELLOW, YELLOW_LIT, YELLOW_DARK, YELLOW_SHADE, 1)
    paint_panel(px, "yellow_dark", YELLOW_DARK, YELLOW, YELLOW_SHADE, OUTLINE, 2)
    paint_panel(px, "steel", STEEL, STEEL_LIT, STEEL_DARK, OUTLINE, 3)
    paint_panel(px, "steel_dark", STEEL_DARK, STEEL, IRON, OUTLINE, 4)
    paint_panel(px, "plate", IRON, STEEL_LIT, STEEL_DARK, OUTLINE, 5)
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
    return img


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
    rect(px, 9, 7, 10, 7, GLASS_LIT)
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
