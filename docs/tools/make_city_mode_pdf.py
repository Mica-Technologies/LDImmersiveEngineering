"""Generate CITY_MODE.pdf: charts via matplotlib, document via reportlab."""
import os
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.ticker import FuncFormatter

from reportlab.lib import colors
from reportlab.lib.enums import TA_LEFT
from reportlab.lib.pagesizes import LETTER
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import inch
from reportlab.platypus import (BaseDocTemplate, Frame, Image, PageTemplate,
                                Paragraph, Spacer, Table, TableStyle, KeepTogether)

OUT = os.path.dirname(os.path.abspath(__file__))
DOCS = os.path.normpath(os.path.join(OUT, ".."))

# ---- palette (dataviz reference instance, light mode; validated) -------------
SURFACE      = "#fcfcfb"
INK_PRIMARY  = "#0b0b0b"
INK_SECOND   = "#52514e"
INK_MUTED    = "#898781"
GRIDLINE     = "#e1e0d9"
BASELINE     = "#c3c2b7"
SERIES_1     = "#2a78d6"   # blue   - normal mode / wire traversal
SERIES_2     = "#008300"   # green  - city mode / rest of IE
SANS = "Helvetica"

plt.rcParams.update({
    "font.family": "sans-serif",
    "font.sans-serif": ["Segoe UI", "DejaVu Sans", "Arial"],
    "figure.facecolor": SURFACE,
    "axes.facecolor": SURFACE,
    "savefig.facecolor": SURFACE,
    "text.color": INK_PRIMARY,
    "axes.labelcolor": INK_SECOND,
    "xtick.color": INK_MUTED,
    "ytick.color": INK_MUTED,
    "axes.edgecolor": BASELINE,
})


def _strip(ax, keep_left=True):
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    ax.spines["left"].set_visible(keep_left)
    ax.spines["bottom"].set_color(BASELINE)
    if keep_left:
        ax.spines["left"].set_color(BASELINE)
    ax.tick_params(length=0)


# ---- Chart A: modelled active-server-CPU share ------------------------------
def chart_cpu_share(path):
    fig, ax = plt.subplots(figsize=(7.4, 2.55), dpi=220)

    rows = ["Normal", "City mode"]
    y = [1, 0]
    traversal = [11.5, 1.5]
    rest_of_ie = [5.15, 5.15]

    H = 0.42
    # 2px-equivalent surface gap between stacked segments
    gap = 0.09
    for i, yy in enumerate(y):
        ax.barh(yy, traversal[i], height=H, color=SERIES_1, zorder=3)
        ax.barh(yy, rest_of_ie[i], height=H, left=traversal[i] + gap,
                color=SERIES_2, zorder=3)

        total = traversal[i] + rest_of_ie[i]
        ax.text(total + 0.75, yy, f"{total:.2f}%".rstrip("0").rstrip("."),
                va="center", ha="left", fontsize=11.5, color=INK_PRIMARY,
                fontweight="bold")
        # direct labels inside segments where they fit
        ax.text(traversal[i] / 2, yy, f"{traversal[i]}", va="center", ha="center",
                fontsize=9.5, color="white", fontweight="bold", zorder=4)
        ax.text(traversal[i] + gap + rest_of_ie[i] / 2, yy, f"{rest_of_ie[i]}",
                va="center", ha="center", fontsize=9.5, color="white",
                fontweight="bold", zorder=4)

    ax.set_yticks(y)
    ax.set_yticklabels(rows, fontsize=11, color=INK_PRIMARY)
    ax.set_xlim(0, 20)
    ax.set_xticks([0, 5, 10, 15, 20])
    ax.xaxis.set_major_formatter(FuncFormatter(lambda v, _: f"{v:g}%"))
    ax.tick_params(axis="x", labelsize=9)
    ax.xaxis.grid(True, color=GRIDLINE, linewidth=0.8, zorder=0)
    ax.set_axisbelow(True)
    _strip(ax, keep_left=False)

    handles = [plt.Rectangle((0, 0), 1, 1, color=SERIES_1),
               plt.Rectangle((0, 0), 1, 1, color=SERIES_2)]
    ax.legend(handles, ["Wire energy traversal", "Rest of Immersive Engineering"],
              loc="lower right", bbox_to_anchor=(1.0, -0.42), ncol=2,
              frameon=False, fontsize=9.5, handlelength=1.1, handleheight=1.1,
              borderpad=0, columnspacing=1.4,
              labelcolor=INK_SECOND)

    ax.set_title("Immersive Engineering's share of active server CPU",
                 fontsize=12.5, color=INK_PRIMARY, pad=12, loc="left",
                 fontweight="bold")
    fig.tight_layout()
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)


# ---- Chart B: work per powered connector per tick ---------------------------
def chart_work(path):
    fig, ax = plt.subplots(figsize=(7.4, 3.05), dpi=220)

    labels = ["Route-set lookups\n(per tick)",
              "outputEnergy calls\n(per reachable output)",
              "Per-segment loss maths\n(per output)",
              "Burnout ledger writes\n(per output)"]
    normal = [3, 6, 8, 4]
    city = [1, 1, 0, 0]

    ypos = list(range(len(labels)))[::-1]
    H = 0.34
    off = 0.19

    for i, yy in enumerate(ypos):
        ax.barh(yy + off, normal[i], height=H, color=SERIES_1, zorder=3)
        ax.barh(yy - off, city[i], height=H, color=SERIES_2, zorder=3)
        ax.text(normal[i] + 0.18, yy + off, str(normal[i]), va="center",
                ha="left", fontsize=10, color=INK_PRIMARY, fontweight="bold")
        ax.text(city[i] + 0.18, yy - off, str(city[i]), va="center",
                ha="left", fontsize=10, color=INK_PRIMARY, fontweight="bold")

    ax.set_yticks(ypos)
    ax.set_yticklabels(labels, fontsize=9.5, color=INK_PRIMARY)
    ax.set_xlim(0, 9)
    ax.set_xticks(range(0, 10, 2))
    ax.tick_params(axis="x", labelsize=9)
    ax.xaxis.grid(True, color=GRIDLINE, linewidth=0.8, zorder=0)
    ax.set_axisbelow(True)
    _strip(ax, keep_left=False)

    handles = [plt.Rectangle((0, 0), 1, 1, color=SERIES_1),
               plt.Rectangle((0, 0), 1, 1, color=SERIES_2)]
    ax.legend(handles, ["Normal", "City mode"], loc="lower right",
              bbox_to_anchor=(1.0, -0.30), ncol=2, frameon=False, fontsize=9.5,
              handlelength=1.1, handleheight=1.1, borderpad=0, columnspacing=1.4,
              labelcolor=INK_SECOND)

    ax.set_title("Work per powered connector, per tick",
                 fontsize=12.5, color=INK_PRIMARY, pad=12, loc="left",
                 fontweight="bold")
    fig.text(0.012, -0.055,
             "Segment counts assume an illustrative 4 wire segments per route.",
             fontsize=8.5, color=INK_MUTED)
    fig.tight_layout()
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)


# ---- document ---------------------------------------------------------------
def build_pdf(path, chart_a, chart_b):
    ss = getSampleStyleSheet()
    body = ParagraphStyle("body", parent=ss["Normal"], fontName=SANS,
                          fontSize=9.6, leading=14.2,
                          textColor=colors.HexColor(INK_PRIMARY),
                          spaceAfter=8, alignment=TA_LEFT)
    h1 = ParagraphStyle("h1", parent=body, fontName=SANS + "-Bold",
                        fontSize=19, leading=23, spaceAfter=3, spaceBefore=0)
    sub = ParagraphStyle("sub", parent=body, fontSize=10.5, leading=14,
                         textColor=colors.HexColor(INK_SECOND), spaceAfter=16)
    h2 = ParagraphStyle("h2", parent=body, fontName=SANS + "-Bold",
                        fontSize=12.6, leading=16, spaceBefore=15, spaceAfter=6,
                        textColor=colors.HexColor(INK_PRIMARY))
    h3 = ParagraphStyle("h3", parent=body, fontName=SANS + "-Bold",
                        fontSize=10.3, leading=14, spaceBefore=10, spaceAfter=4)
    note = ParagraphStyle("note", parent=body, fontSize=8.7, leading=12.4,
                          textColor=colors.HexColor(INK_SECOND))
    cell = ParagraphStyle("cell", parent=body, fontSize=8.5, leading=11.4,
                          spaceAfter=0)
    cellb = ParagraphStyle("cellb", parent=cell, fontName=SANS + "-Bold")

    doc = BaseDocTemplate(path, pagesize=LETTER,
                          leftMargin=0.85 * inch, rightMargin=0.85 * inch,
                          topMargin=0.8 * inch, bottomMargin=0.8 * inch,
                          title="City Mode", author="LDImmersiveEngineering")
    frame = Frame(doc.leftMargin, doc.bottomMargin, doc.width, doc.height, id="f")

    def decorate(canvas, d):
        canvas.saveState()
        canvas.setFillColor(colors.HexColor(INK_MUTED))
        canvas.setFont(SANS, 8)
        canvas.drawString(doc.leftMargin, 0.52 * inch,
                          "LDImmersiveEngineering — City Mode")
        canvas.drawRightString(LETTER[0] - doc.rightMargin, 0.52 * inch,
                               "%d" % d.page)
        canvas.setStrokeColor(colors.HexColor(GRIDLINE))
        canvas.setLineWidth(0.5)
        canvas.line(doc.leftMargin, 0.68 * inch,
                    LETTER[0] - doc.rightMargin, 0.68 * inch)
        canvas.restoreState()

    doc.addPageTemplates([PageTemplate(id="main", frames=[frame], onPage=decorate)])

    def tbl(data, widths, header=True):
        t = Table(data, colWidths=widths, hAlign="LEFT", repeatRows=1 if header else 0)
        style = [
            ("VALIGN", (0, 0), (-1, -1), "TOP"),
            ("LINEBELOW", (0, 0), (-1, 0), 0.7, colors.HexColor(BASELINE)),
            ("ROWBACKGROUNDS", (0, 1), (-1, -1),
             [colors.white, colors.HexColor("#f6f6f3")]),
            ("TOPPADDING", (0, 0), (-1, -1), 5),
            ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
            ("LEFTPADDING", (0, 0), (-1, -1), 6),
            ("RIGHTPADDING", (0, 0), (-1, -1), 6),
        ]
        t.setStyle(TableStyle(style))
        return t

    S = []
    S.append(Paragraph("City Mode", h1))
    S.append(Paragraph(
        "A config-gated power simulation for Immersive Engineering 1.12.2 that keeps the "
        "wiring and removes the grid maths.", sub))

    S.append(Paragraph(
        "City mode keeps every visible part of the electrical system — connectors, relays, "
        "transformers, breaker switches, energy meters and the catenary wires between them — "
        "while replacing the simulation behind them with a single lossless push. It exists because "
        "on a large build the realistic grid is the most expensive thing this mod does per tick.", body))
    S.append(Paragraph(
        "It is <b>off by default</b>. With <font face='Courier'>cityMode = false</font> none of the "
        "paths described here are entered and behaviour is byte-identical to stock.", body))

    S.append(Paragraph("Modelled performance", h2))
    S.append(Image(chart_a, width=6.3 * inch, height=2.17 * inch))
    S.append(Spacer(1, 10))
    S.append(Paragraph(
        "<b>These are modelled figures, not measurements.</b> They apply the operation counts below "
        "to the one measured baseline this fork has — a live spark profile of the production "
        "server, where Immersive Engineering was the single most expensive individual mod at 16.65% "
        "of active CPU, roughly 11.5 points of which was the wire traversal alone. City mode itself "
        "has not yet been profiled under live load. Treat the shape as sound and the exact "
        "percentages as an estimate.", note))

    S.append(KeepTogether([
        Paragraph("Where the saving comes from", h2),
        Image(chart_b, width=6.3 * inch, height=2.44 * inch),
    ]))
    S.append(Spacer(1, 10))
    S.append(Paragraph(
        "The reduction is structural rather than incremental. Normal mode fetches the route set three "
        "times per tick and runs the distribution twice — a simulate pass to discover demand, then "
        "a real pass that sorts every output into a TreeMap by loss rate, gives each a proportional "
        "share, applies per-segment attenuation and records throughput for wire burnout — followed "
        "by a third full walk of the network to advertise available energy. City mode does one lookup, "
        "one real push per output, and stops.", body))

    S.append(Paragraph("What changes, in gameplay terms", h2))
    rows = [
        ["", "Normal", "City mode"],
        ["Wire loss", "2.5–5% per 16 blocks, worse when lightly loaded", "none"],
        ["Voltage tiers", "throttle to the weakest wire on the path", "cosmetic only"],
        ["Supply &lt; demand", "proportional to demand, nearest first", "greedy, arbitrary order"],
        ["Wire burnout", "wire destroyed above its rate", "cannot happen"],
        ["Wire shock damage", "sourced from the whole network", "sourced from the local connector"],
        ["Breaker switches", "cut the network", "unchanged"],
        ["Energy meters", "read loss-attenuated throughput", "read full throughput"],
        ["Generator fuel", "consumed only under load", "unchanged"],
        ["Machine power needs", "—", "unchanged"],
    ]
    data = [[Paragraph(c, cellb if i == 0 else (cellb if j == 0 else cell))
             for j, c in enumerate(r)] for i, r in enumerate(rows)]
    S.append(tbl(data, [1.5 * inch, 2.75 * inch, 2.3 * inch]))

    S.append(Paragraph("Two consequences worth knowing", h2))
    S.append(Paragraph("Wire burnout is disabled", h3))
    S.append(Paragraph(
        "Overload destruction works by the normal transfer path recording per-connection throughput "
        "into a ledger that a world-tick handler then acts on. That path is the ledger's only writer, "
        "so in city mode it stays empty and no wire can ever burn out. Combined with the fact that "
        "city mode never clamps to the cable's rate, a copper wire will carry a full HV connector's "
        "4096 FE/t forever with no consequence. That is intended for a city pack, but it is a rules "
        "change rather than an optimisation.", body))
    S.append(Paragraph("Wire shock damage becomes local", h3))
    S.append(Paragraph(
        "Damage is computed from a per-tick list of energy sources on each connectable, which the "
        "skipped network broadcast used to populate. In city mode that list holds only the "
        "connector's own energy, so damage is generally lower and a wire span running between two "
        "relays will not shock at all, because relays never hold energy. Set "
        "<font face='Courier'>enableWireDamage = false</font> to turn it off properly.", body))

    S.append(Paragraph("Does a diesel generator still need fuel?", h2))
    S.append(Paragraph(
        "<b>Yes.</b> No generator reads the city-mode flag — the entire footprint is four reads "
        "of one boolean inside the connector class. A diesel generator gates fuel consumption on two "
        "conditions city mode never touches: something must actually accept power, and there must be "
        "registered fuel in the tank. With no consumers, connector buffers saturate, the generator's "
        "simulated insert returns zero, the fan spins down and no fuel burns — in both modes.", body))
    S.append(Paragraph(
        "What city mode changes is the yield per unit of fuel: the 4096 FE/t leaving the generator "
        "arrives undiminished instead of being attenuated by every wire segment on the way. More "
        "useful power per bucket of biodiesel, but never power from nothing — only what receivers "
        "actually accepted is deducted from the connector.", body))

    S.append(Paragraph("Block-by-block summary", h2))
    rows = [
        ["Block", "Role", "Affected by city mode"],
        ["Diesel Generator", "4096 FE/t, burns fuel only under load", "No — but its output now arrives lossless"],
        ["Thermoelectric / Dynamo", "push to adjacent blocks only", "No"],
        ["Windmill / Water Wheel", "drive the dynamo, produce no FE", "No"],
        ["Capacitor LV/MV/HV", "buffer; 256 / 1024 / 4096 FE/t", "No"],
        ["Creative Capacitor", "infinite source, all six sides", "No — works as an infinite source in both modes"],
        ["Lightning Rod", "16,000,000 FE on a strike", "No"],
        ["Connector LV/MV/HV", "the only blocks moving energy over wires", "<b>Yes</b> — push path replaced; its own rate caps still apply"],
        ["Relay LV/MV/HV", "routing waypoint, never holds energy", "No — tick body never runs either way"],
        ["Transformer", "tier adapter; holds no energy", "<b>Yes</b> — tier throttling disappears"],
        ["Breaker Switch", "interrupts the network", "No — still cuts"],
        ["Energy Meter", "passive throughput accumulator", "Works, and reads the full unattenuated amount"],
        ["Machines", "receive by push into their own buffer", "No — their own intake caps still apply"],
        ["Direct generator → machine", "no wires involved", "No — entirely unaffected"],
    ]
    data = [[Paragraph(c, cellb if i == 0 else (cellb if j == 0 else cell))
             for j, c in enumerate(r)] for i, r in enumerate(rows)]
    S.append(tbl(data, [1.55 * inch, 2.15 * inch, 2.85 * inch]))

    S.append(Spacer(1, 10))
    S.append(Paragraph(
        "Full technical detail, including the annotated transfer routine, wire-type tables and the "
        "testing checklist, is in <font face='Courier'>docs/CITY_MODE.md</font>.", note))

    doc.build(S)


if __name__ == "__main__":
    import tempfile
    # The charts are build intermediates, not deliverables - keep them out of the repo.
    with tempfile.TemporaryDirectory() as tmp:
        a = os.path.join(tmp, "chart_cpu.png")
        b = os.path.join(tmp, "chart_work.png")
        chart_cpu_share(a)
        chart_work(b)
        out = os.path.join(DOCS, "CITY_MODE.pdf")
        build_pdf(out, a, b)
    print("wrote", out, os.path.getsize(out), "bytes")
