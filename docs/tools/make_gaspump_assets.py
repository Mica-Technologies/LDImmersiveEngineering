#!/usr/bin/env python3
"""
Regenerates the Gas Station Pump's OBJ model and the textures it wears.

The pump used to be a cube.  A forecourt built out of cubes reads as a row of crates,
which is what a playtester reported: "the fuel pump does not look like a fuel pump when
it is fully assembled".  What it wants to be is the roadside object everybody already
has a picture of -- a tall painted cabinet on a plinth, a glass price panel near the
top, a lit globe over it, and a nozzle racked on its flank.

**Why an OBJ rather than a JSON model.**  The pump is two blocks tall and turns to face
the road.  A JSON block model cannot be rotated per-tile without a facing property on
the block, and `petroleum_device` deliberately has none -- see the Torpedo Tank note in
docs/PETROLEUM.md.  An OBJ model goes through IE's own loader, where the tile entity is
handed each group and can both hide it and transform it (`IOBJModelCallback`), so the
facing, the assembled/unassembled state and the "upper block draws nothing" rule are all
answered by the tile without touching the blockstate's property set.

**Why it is generated.**  The alternative to this script is a thousand hand-written
lines of vertex soup that nobody can review and nobody dares edit.  Here the pump is
about thirty numbers, each a named box in pixel coordinates, and a change to the height
of the price panel is a change to one of them.  The same reasoning as
make_conduit_assets.py, which generates thirty models for the same reason.

**The unformed cube is part of the model.**  A blockstate variant names exactly one
model, and the pump has to be able to look like a loose crate before it is hammered
together.  So the crate is a group in the same OBJ (`unformed`), and the tile shows
either that group or the pump, never both.

Coordinates are Minecraft pixels: 16 to a block, y running 0-32 because the assembled
pump spans the master block and the dummy above it.  The pump faces **north** (-Z) as
authored; the tile rotates it to its facing.

Usage:  python docs/tools/make_gaspump_assets.py [--assets <assets dir>]

Requires Pillow.
"""

import argparse
import os

from PIL import Image

# ---------------------------------------------------------------------------
# Palette
#
# A service-station pump is painted sheet metal, not bare industrial steel: the body is
# the one thing in this feature that is deliberately *bright*, because a forecourt is
# meant to be visible from the road.  Cream and oxide red, the colours a mid-century
# pump actually wore, sit close enough to IE's rust-and-treated-wood range that a
# station does not look like it came out of a different mod.
# ---------------------------------------------------------------------------
OUTLINE = (34, 32, 30, 255)
BODY = (222, 214, 196, 255)
BODY_LIT = (238, 232, 216, 255)
BODY_SHADE = (186, 177, 158, 255)
BODY_SEAM = (150, 142, 126, 255)
OXIDE = (150, 62, 44, 255)
OXIDE_LIT = (178, 82, 60, 255)

STEEL = (92, 92, 96, 255)
STEEL_LIT = (122, 122, 128, 255)
STEEL_SHADE = (64, 64, 68, 255)
RUST = (112, 74, 48, 255)

GLASS = (26, 28, 30, 255)
GLASS_LIT = (44, 48, 52, 255)
GLASS_GLOW = (70, 58, 26, 255)
BEZEL = (104, 104, 108, 255)
BEZEL_SHADE = (70, 70, 74, 255)

RUBBER = (36, 36, 38, 255)
RUBBER_LIT = (58, 58, 62, 255)
BRASS = (146, 116, 58, 255)

GLOBE = (232, 226, 208, 255)
GLOBE_GLOW = (250, 246, 232, 255)

# ---------------------------------------------------------------------------
# Geometry, in pixels.  Front is -Z.
#
# Every number here is a box corner, and every box is named for the part it is, so the
# model reads as a parts list rather than as coordinates.  The one rule the shapes obey:
# nothing leaves the 16x16 footprint, because the pump occupies two blocks and a player
# will stand a second pump against it.
# ---------------------------------------------------------------------------
BLOCK = 16.0

# The price panel's front face.  The renderer draws the price just in front of this, so
# the number is shared with TileRenderGasPump -- move one and move the other.
PANEL_FRONT_Z = 3.25
PANEL_BOTTOM_Y = 16.0
PANEL_TOP_Y = 24.0

BOXES = [
    # group, material, x0, y0, z0, x1, y1, z1
    # The loose block: a plain cube wearing the crate texture the pump has always had.
    ("unformed", "pump_crate", 0, 0, 0, 16, 16, 16),

    # The island the pump is bolted to, and the step up onto the cabinet.
    ("base", "pump_base", 1.0, 0.0, 3.0, 15.0, 2.0, 13.0),
    ("base", "pump_base", 2.0, 2.0, 4.0, 14.0, 3.0, 12.0),

    # The cabinet itself: wide across the road, shallow front to back, as a pump is.
    ("body", "pump_body", 2.0, 3.0, 4.0, 14.0, 25.0, 12.0),

    # Two dark bands break the height up so the cabinet does not read as one slab.
    ("trim", "pump_base", 1.75, 13.5, 3.75, 14.25, 15.0, 12.25),
    ("trim", "pump_base", 1.75, 24.5, 3.75, 14.25, 25.5, 12.25),

    # The chamfered crown, and the cap it carries.
    ("crown", "pump_body", 3.0, 27.5, 5.0, 13.0, 28.5, 11.0),

    # The lit globe every pump of this shape has on top of it.
    ("globe", "pump_globe", 4.0, 28.5, 6.5, 12.0, 31.5, 9.5),

    # The price panel, standing a little proud of the cabinet's face.
    # 3.98 rather than 4.0 at the back: a face exactly coplanar with the cabinet's would
    # be a z-fighting seam the day somebody renders this without backface culling.
    ("panel", "pump_panel", 3.0, PANEL_BOTTOM_Y, PANEL_FRONT_Z, 13.0, PANEL_TOP_Y, 3.98),

    # The nozzle boot on the flank, the nozzle racked in it, and the hose over the top.
    ("holster", "pump_gear", 14.0, 12.0, 6.0, 15.5, 20.0, 10.0),
    ("nozzle", "pump_gear", 14.2, 16.5, 7.0, 15.3, 20.5, 9.0),
    ("nozzle", "pump_gear", 14.5, 20.5, 7.6, 15.0, 23.5, 8.4),
    ("hose", "pump_gear", 14.0, 22.8, 9.2, 15.2, 23.6, 10.0),
    ("hose", "pump_gear", 14.4, 20.8, 9.2, 15.2, 22.8, 10.0),
]

# The chamfer between the cabinet and its cap: a box would give the pump a flat lid, and
# every pump this one is modelled on has a sloped shoulder there.
TAPERS = [
    # group, material, (x0,z0,x1,z1) at the bottom, (x0,z0,x1,z1) at the top, y0, y1
    ("crown", "pump_body", (2.0, 4.0, 14.0, 12.0), (3.0, 5.0, 13.0, 11.0), 25.5, 27.5),
]

MATERIALS = [
    ("pump_crate", "immersiveengineering:blocks/petroleum_gas_pump"),
    ("pump_base", "immersiveengineering:blocks/petroleum_gas_pump_base"),
    ("pump_body", "immersiveengineering:blocks/petroleum_gas_pump_body"),
    ("pump_panel", "immersiveengineering:blocks/petroleum_gas_pump_panel"),
    ("pump_gear", "immersiveengineering:blocks/petroleum_gas_pump_gear"),
    ("pump_globe", "immersiveengineering:blocks/petroleum_gas_pump_globe"),
]

# Group order in the file.  It is also the order the tile entity's group names are
# written in, and the order the asset test checks, so keeping one list is the point.
GROUPS = ["unformed", "base", "body", "trim", "crown", "globe", "panel",
          "holster", "nozzle", "hose"]


class Mesh:
    """
    Accumulates an OBJ: shared vertex and texture-coordinate tables, faces per group.

    Vertices are deduplicated because OBJ indices are global and a hundred boxes sharing
    corners is a hundred lines of file nobody reads.  Faces are quads, wound
    counter-clockwise seen from outside: the shipped IE models carry no vertex normals
    and Forge derives them from the winding, so a reversed face is an invisible one.
    """

    def __init__(self):
        self.vertices = []
        self.vertex_index = {}
        self.uvs = []
        self.uv_index = {}
        self.faces = {}

    def vertex(self, x, y, z):
        key = (round(x, 5), round(y, 5), round(z, 5))
        if key not in self.vertex_index:
            self.vertex_index[key] = len(self.vertices) + 1
            self.vertices.append(key)
        return self.vertex_index[key]

    def uv(self, u, v):
        key = (round(u, 5), round(v, 5))
        if key not in self.uv_index:
            self.uv_index[key] = len(self.uvs) + 1
            self.uvs.append(key)
        return self.uv_index[key]

    def face(self, group, material, corners, uvs):
        entry = self.faces.setdefault(group, [])
        indices = []
        for (point, coord) in zip(corners, uvs):
            indices.append((self.vertex(*[c / BLOCK for c in point]),
                            self.uv(*uv_to_obj(*coord))))
        entry.append((material, indices))


def uv_to_obj(u_px, v_px):
    """
    Pixel coordinates -- u from the left, v from the *top* -- to what an OBJ carrying
    `flip-v` wants.

    Every IE model sets `"custom": {"flip-v": true}` because they come out of Blender,
    whose V axis runs the other way from Minecraft's.  The loader stores `1 - v`, so
    writing `1 - v` here means the loader ends up with exactly the pixel row asked for.
    """
    return u_px / 16.0, 1.0 - v_px / 16.0


def add_box(mesh, group, material, x0, y0, z0, x1, y1, z1):
    add_prism(mesh, group, material, (x0, z0, x1, z1), (x0, z0, x1, z1), y0, y1)


def add_prism(mesh, group, material, bottom, top, y0, y1):
    """
    A box whose top footprint may differ from its bottom, which is all a chamfer is.

    Horizontal texture coordinates map straight through, so a box two pixels wide gets
    two pixels of texture and the whole model shares one pixel grid.  The vertical axis
    cannot: the cabinet is twenty-two pixels tall and a block texture is sixteen, and a
    UV outside 0-1 does not tile on a stitched atlas -- it reads whatever sprite happens
    to be next door.  So every box's height is stretched to fill the texture, which is
    why the body texture is drawn as horizontal bands.
    """
    bx0, bz0, bx1, bz1 = bottom
    tx0, tz0, tx1, tz1 = top
    vt = 0.0
    vb = 16.0

    # North (-Z), the pump's front.
    mesh.face(group, material,
              [(bx0, y0, bz0), (tx0, y1, tz0), (tx1, y1, tz0), (bx1, y0, bz0)],
              [(bx0, vb), (tx0, vt), (tx1, vt), (bx1, vb)])
    # South (+Z).
    mesh.face(group, material,
              [(bx0, y0, bz1), (bx1, y0, bz1), (tx1, y1, tz1), (tx0, y1, tz1)],
              [(bx0, vb), (bx1, vb), (tx1, vt), (tx0, vt)])
    # West (-X).
    mesh.face(group, material,
              [(bx0, y0, bz0), (bx0, y0, bz1), (tx0, y1, tz1), (tx0, y1, tz0)],
              [(bz0, vb), (bz1, vb), (tz1, vt), (tz0, vt)])
    # East (+X).
    mesh.face(group, material,
              [(bx1, y0, bz0), (tx1, y1, tz0), (tx1, y1, tz1), (bx1, y0, bz1)],
              [(bz0, vb), (tz0, vt), (tz1, vt), (bz1, vb)])
    # Top (+Y) and bottom (-Y) take both coordinates from the footprint.
    mesh.face(group, material,
              [(tx0, y1, tz0), (tx0, y1, tz1), (tx1, y1, tz1), (tx1, y1, tz0)],
              [(tx0, tz0), (tx0, tz1), (tx1, tz1), (tx1, tz0)])
    mesh.face(group, material,
              [(bx0, y0, bz0), (bx1, y0, bz0), (bx1, y0, bz1), (bx0, y0, bz1)],
              [(bx0, bz0), (bx1, bz0), (bx1, bz1), (bx0, bz1)])


def build_mesh():
    mesh = Mesh()
    for (group, material, x0, y0, z0, x1, y1, z1) in BOXES:
        add_box(mesh, group, material, x0, y0, z0, x1, y1, z1)
    for (group, material, bottom, top, y0, y1) in TAPERS:
        add_prism(mesh, group, material, bottom, top, y0, y1)
    return mesh


def write_model(assets, mesh):
    lines = ["# Gas Station Pump -- generated by docs/tools/make_gaspump_assets.py",
             "# Do not hand-edit: the geometry constants live in that script.",
             "mtllib gas_pump.mtl",
             ""]
    for (x, y, z) in mesh.vertices:
        lines.append("v %.6f %.6f %.6f" % (x, y, z))
    lines.append("")
    for (u, v) in mesh.uvs:
        lines.append("vt %.6f %.6f" % (u, v))
    lines.append("")
    for group in GROUPS:
        faces = mesh.faces.get(group)
        if not faces:
            raise AssertionError("group %s has no faces" % group)
        material = None
        lines.append("o %s" % group)
        for (mat, indices) in faces:
            if mat != material:
                lines.append("usemtl %s" % mat)
                material = mat
            lines.append("f "+" ".join("%d/%d" % pair for pair in indices))
        lines.append("")

    path = os.path.join(assets, "models", "block", "petroleum", "gas_pump.obj.ie")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", newline="\n") as handle:
        handle.write("\n".join(lines).rstrip("\n")+"\n")
    return path


def write_material(assets):
    lines = []
    for (name, texture) in MATERIALS:
        lines.append("newmtl %s" % name)
        lines.append("map_Ka %s" % texture)
    path = os.path.join(assets, "models", "block", "petroleum", "gas_pump.mtl")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", newline="\n") as handle:
        handle.write("\n".join(lines)+"\n")
    return path


# ---------------------------------------------------------------------------
# Textures.  Sixteen by sixteen and fully opaque, both of which PetroleumAssetsTest
# enforces: a transparent pixel on machinery reads as a hole in the model.
# ---------------------------------------------------------------------------

def rect(px, x0, y0, x1, y1, colour):
    """Inclusive rectangle, as the other petroleum texture scripts draw them."""
    for x in range(x0, x1+1):
        for y in range(y0, y1+1):
            px[x, y] = colour


def new_image(fill):
    image = Image.new("RGBA", (16, 16), fill)
    return image, image.load()


def save(assets, name, image):
    path = os.path.join(assets, "textures", "blocks", "petroleum_gas_pump_%s.png" % name)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    image.save(path, "PNG", optimize=True)
    return path


def build_body(assets):
    """
    The painted cabinet.  Drawn in horizontal bands because the cabinet face is taller
    than the texture and the model stretches it vertically -- bands survive that, a
    pattern of bolts would smear.
    """
    image, px = new_image(BODY)
    for y in range(16):
        # A soft top-down shade, so a flat slab still has a light side.
        if y > 11:
            rect(px, 0, y, 15, y, BODY_SHADE)
        elif y < 2:
            rect(px, 0, y, 15, y, BODY_LIT)
    # The seam down each edge, which is where a real cabinet's panel folds are.
    rect(px, 0, 0, 0, 15, BODY_SEAM)
    rect(px, 15, 0, 15, 15, BODY_SEAM)
    # The oxide waistband: the one colour on the pump, and what makes it read as a
    # forecourt object rather than a fridge.
    rect(px, 0, 6, 15, 8, OXIDE)
    rect(px, 0, 6, 15, 6, OXIDE_LIT)
    rect(px, 0, 9, 15, 9, BODY_SEAM)
    # Bolt heads along the waistband, four of them, evenly spaced.
    for x in (2, 6, 9, 13):
        rect(px, x, 7, x, 7, OUTLINE)
    return save(assets, "body", image)


def build_base(assets):
    """The plinth and the dark bands: painted steel that has been kicked for years."""
    image, px = new_image(STEEL)
    rect(px, 0, 0, 15, 0, STEEL_LIT)
    rect(px, 0, 14, 15, 15, STEEL_SHADE)
    # Rivets along the top edge of the plinth.
    for x in range(1, 15, 4):
        rect(px, x, 2, x, 2, STEEL_LIT)
        rect(px, x, 3, x, 3, STEEL_SHADE)
    # A rust wash at the foot, where a plinth actually rusts.
    rect(px, 3, 12, 6, 13, RUST)
    rect(px, 10, 13, 12, 13, RUST)
    return save(assets, "base", image)


def build_panel(assets):
    """
    The price panel: a steel bezel around dark glass.

    The glass is nearly black on purpose.  The price is drawn over it by the renderer in
    the nixie font, and a digit only reads as lit if what is behind it is not.
    """
    image, px = new_image(GLASS)
    rect(px, 0, 0, 15, 1, BEZEL)
    rect(px, 0, 14, 15, 15, BEZEL_SHADE)
    rect(px, 0, 0, 1, 15, BEZEL)
    rect(px, 14, 0, 15, 15, BEZEL_SHADE)
    rect(px, 2, 2, 13, 2, BEZEL_SHADE)
    rect(px, 2, 13, 13, 13, BEZEL_SHADE)
    # A faint glow behind where the digits sit, so an unset price still looks like a
    # display rather than a hole.
    rect(px, 3, 4, 12, 8, GLASS_LIT)
    rect(px, 3, 4, 12, 4, GLASS_GLOW)
    rect(px, 3, 10, 12, 11, GLASS_LIT)
    return save(assets, "panel", image)


def build_gear(assets):
    """Hose, nozzle and boot: rubber and a brass ferrule, all of it nearly black."""
    image, px = new_image(RUBBER)
    # Diagonal ribbing, which is what makes a hose read as a hose at two blocks away.
    for x in range(16):
        for y in range(16):
            if (x+y) % 4 == 0:
                px[x, y] = RUBBER_LIT
    rect(px, 0, 0, 15, 0, RUBBER_LIT)
    rect(px, 0, 15, 15, 15, OUTLINE)
    # The ferrule band.
    rect(px, 6, 7, 9, 8, BRASS)
    return save(assets, "gear", image)


def build_globe(assets):
    """The lit sign on top: a pale field between two oxide bands."""
    image, px = new_image(GLOBE)
    rect(px, 0, 0, 15, 2, OXIDE)
    rect(px, 0, 13, 15, 15, OXIDE)
    rect(px, 0, 3, 15, 3, OXIDE_LIT)
    rect(px, 0, 12, 15, 12, OXIDE_LIT)
    rect(px, 2, 6, 13, 9, GLOBE_GLOW)
    rect(px, 0, 0, 15, 0, OUTLINE)
    rect(px, 0, 15, 15, 15, OUTLINE)
    return save(assets, "globe", image)


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    repo = os.path.dirname(os.path.dirname(here))
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets", default=os.path.join(
        repo, "src", "main", "resources", "assets", "immersiveengineering"))
    args = parser.parse_args()

    mesh = build_mesh()
    model = write_model(args.assets, mesh)
    material = write_material(args.assets)
    textures = [build_body(args.assets), build_base(args.assets),
                build_panel(args.assets), build_gear(args.assets),
                build_globe(args.assets)]

    quads = sum(len(faces) for faces in mesh.faces.values())
    print("wrote %s (%d groups, %d quads, %d vertices)"
          % (os.path.relpath(model, repo), len(mesh.faces), quads, len(mesh.vertices)))
    print("wrote %s (%d materials)" % (os.path.relpath(material, repo), len(MATERIALS)))
    for path in textures:
        print("wrote %s" % os.path.relpath(path, repo))


if __name__ == "__main__":
    main()
