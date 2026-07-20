"""Generate CITY_MODE_AND_PERF.pdf: charts via matplotlib, document via reportlab."""
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


# ---- Chart A: MEASURED cost per configuration ------------------------------
def chart_cpu_share(path):
    """Measured IE server-thread cost across the profiled configurations."""
    fig, ax = plt.subplots(figsize=(7.4, 2.5), dpi=220)

    rows = ["Stock\n(wire damage on)", "City mode on", "Wire damage off\n(physics intact)"]
    # measured IE server-thread CPU, seconds per 120s capture; load-normalised reduction
    cost = [6.53, 2.72, 2.49]
    delta = [None, -49, -60]

    ypos = [2, 1, 0]
    for i, yy in enumerate(ypos):
        colour = SERIES_2 if i == 2 else SERIES_1
        ax.barh(yy, cost[i], height=0.48, color=colour, zorder=3)
        lbl = f"{cost[i]:.2f}s"
        if delta[i] is not None:
            lbl += f"    {delta[i]}%"
        ax.text(cost[i] + 0.16, yy, lbl, va="center", ha="left", fontsize=10.5,
                color=INK_PRIMARY, fontweight="bold")

    ax.set_yticks(ypos)
    ax.set_yticklabels(rows, fontsize=9.5, color=INK_PRIMARY)
    ax.set_xlim(0, 8.4)
    ax.set_xticks([0, 2, 4, 6, 8])
    ax.xaxis.set_major_formatter(FuncFormatter(lambda v, _: f"{v:g}s"))
    ax.tick_params(axis="x", labelsize=9)
    ax.xaxis.grid(True, color=GRIDLINE, linewidth=0.8, zorder=0)
    ax.set_axisbelow(True)
    _strip(ax, keep_left=False)

    ax.set_title("Measured: Immersive Engineering server CPU per 120s capture",
                 fontsize=12.5, color=INK_PRIMARY, pad=12, loc="left",
                 fontweight="bold")
    fig.text(0.012, -0.04,
             "Percentages are the load-normalised reduction against the stock baseline. "
             "Turning wire damage off beats city mode and keeps every power mechanic.",
             fontsize=8.5, color=INK_MUTED)
    fig.tight_layout()
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)


# ---- Chart B: MEASURED source of the saving ---------------------------------
def chart_work(path):
    """Where city mode's measured saving actually came from."""
    fig, ax = plt.subplots(figsize=(7.4, 2.75), dpi=220)

    labels = ["Removing the whole-network\nbroadcast (notifyAvailableEnergy)",
              "toIIC — overwhelmingly called\nby that broadcast",
              "Simpler distribution maths\n(loss, sort, proportional split)"]
    saved = [2444, 1126, 240]
    total = sum(saved)

    ypos = list(range(len(labels)))[::-1]
    for i, yy in enumerate(ypos):
        ax.barh(yy, saved[i], height=0.5, color=SERIES_1, zorder=3)
        ax.text(saved[i] + 45, yy, f"{saved[i]} ms   ({100*saved[i]/total:.0f}%)",
                va="center", ha="left", fontsize=10, color=INK_PRIMARY,
                fontweight="bold")

    ax.set_yticks(ypos)
    ax.set_yticklabels(labels, fontsize=9.5, color=INK_PRIMARY)
    ax.set_xlim(0, 3350)
    ax.set_xticks([0, 1000, 2000, 3000])
    ax.xaxis.set_major_formatter(FuncFormatter(lambda v, _: f"{v:g}ms"))
    ax.tick_params(axis="x", labelsize=9)
    ax.xaxis.grid(True, color=GRIDLINE, linewidth=0.8, zorder=0)
    ax.set_axisbelow(True)
    _strip(ax, keep_left=False)

    ax.set_title("Measured: where city mode's saving comes from",
                 fontsize=12.5, color=INK_PRIMARY, pad=12, loc="left",
                 fontweight="bold")
    fig.text(0.012, -0.02,
             "Server-thread CPU saved per 120s capture. The distribution rewrite — the "
             "conceptual heart of city mode — accounts for 6%.",
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

    # invariant=1 pins the embedded creation timestamp and document id, so regenerating an
    # unchanged document produces a byte-identical file instead of a phantom diff on every run.
    doc = BaseDocTemplate(path, pagesize=LETTER,
                          leftMargin=0.85 * inch, rightMargin=0.85 * inch,
                          topMargin=0.8 * inch, bottomMargin=0.8 * inch,
                          title="Performance Tuning and City Mode", author="LDImmersiveEngineering",
                          invariant=1)
    frame = Frame(doc.leftMargin, doc.bottomMargin, doc.width, doc.height, id="f")

    def decorate(canvas, d):
        canvas.saveState()
        canvas.setFillColor(colors.HexColor(INK_MUTED))
        canvas.setFont(SANS, 8)
        canvas.drawString(doc.leftMargin, 0.52 * inch,
                          "LDImmersiveEngineering — Performance Tuning and City Mode")
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
    S.append(Paragraph("Performance Tuning and City Mode", h1))
    S.append(Paragraph(
        "How to make Immersive Engineering 1.12.2 cheap on the server tick, and what each option "
        "costs you.", sub))

    S.append(Paragraph("The headline", h2))
    S.append(Paragraph(
        "<b>Set <font face='Courier'>enableWireDamage = false</font>.</b> On a profiled world it cut "
        "Immersive Engineering's server CPU by <b>60%</b> while changing nothing else about how the "
        "mod plays — wire loss, voltage tiers, proportional power distribution and wire burnout all "
        "stay exactly as they are. It is a bigger win than city mode, and it costs you one feature: "
        "entities no longer take shock damage from touching a live wire.", body))
    S.append(Paragraph(
        "That is not what anyone expected, including the author of city mode. The wire network's cost "
        "turned out not to be its physics but a per-tick, whole-network broadcast that exists only to "
        "feed that damage feature.", body))

    S.append(Paragraph("Recommended configurations", h2))
    rows = [
        ["Goal", "Set", "Result"],
        ["<b>Recommended</b> — fastest, keeps the gameplay",
         "enableWireDamage = false<br/>cityMode = false",
         "~60% less IE server CPU. Loss, voltage tiers, proportional distribution and wire burnout all intact. Only wire shock damage is lost."],
        ["City / roleplay pack",
         "cityMode = true<br/>enableWireDamage = false",
         "~49% from city mode alone. Choose it for the gameplay — lossless voltage-agnostic wires, no burnout — not for speed; the row above is faster."],
        ["Leave alone",
         "validateConnections = false<br/>pump_placeCobble = true",
         "Both already default. The first slows world load; the second stops flowing-fluid updates propagating."],
        ["Client FPS only",
         "increasedRenderboxes = false<br/>disableFancyTESR = true",
         "No effect on TPS. For low-end GPUs."],
    ]
    data = [[Paragraph(c, cellb if i == 0 else (cellb if j == 0 else cell))
             for j, c in enumerate(r)] for i, r in enumerate(rows)]
    S.append(tbl(data, [1.7 * inch, 1.85 * inch, 3.0 * inch]))
    S.append(Spacer(1, 12))

    S.append(Paragraph("What city mode is", h2))
    S.append(Paragraph(
        "City mode trades simulation detail for server tick time. It is aimed at city and roleplay "
        "packs where the mod's machinery is set dressing rather than an engineering puzzle: you keep "
        "the entire look of the build and give up the physics behind it. It covers four subsystems.", body))

    rows = [
        ["Subsystem", "What is simplified"],
        ["Wires", "One lossless push per connector instead of loss, distance weighting, proportional split, a double simulate/real pass and a network-wide broadcast."],
        ["Floodlights", "Beams re-traced only when the light switches or a neighbour changes, never on a timer; light-block count capped per lamp."],
        ["Generators", "Fuel becomes cosmetic — a presence check and a token sip instead of a per-tick burn rate and tank drain."],
        ["Machines", "Idle multiblocks stop re-scanning the recipe list every tick; the scan interval widens."],
    ]
    data = [[Paragraph(c, cellb if i == 0 else (cellb if j == 0 else cell))
             for j, c in enumerate(r)] for i, r in enumerate(rows)]
    S.append(tbl(data, [1.05 * inch, 5.5 * inch]))
    S.append(Spacer(1, 10))

    S.append(Paragraph(
        "It is <b>off by default</b>, and off means byte-identical to stock. "
        "<font face='Courier'>cityMode</font> is the master switch; "
        "<font face='Courier'>cityModeWires</font>, "
        "<font face='Courier'>cityModeFloodlights</font>, "
        "<font face='Courier'>cityModeGenerators</font> and "
        "<font face='Courier'>cityModeMachines</font> each default to on, so the master alone "
        "enables everything while any one subsystem can be declined. Switching the master off is "
        "always sufficient to restore stock behaviour, and nothing here touches saved data.", body))

    S.append(Paragraph("Measured results", h2))
    S.append(Image(chart_a, width=6.3 * inch, height=2.13 * inch))
    S.append(Spacer(1, 10))
    S.append(Paragraph(
        "<b>Measured, not modelled.</b> Four 120-second spark captures of the Server thread on a "
        "local world, flying along power lines — the worst case for the route cache, since chunk "
        "streaming keeps invalidating it. Library time is attributed to the calling mod and idle "
        "(the server thread sleeps 84–90% of the time on an unsaturated world) is excluded. The runs "
        "were not perfectly load-matched — non-IE time varied between them, which no IE setting can "
        "cause — so the quoted percentages normalise IE cost against non-IE server work, which "
        "cancels overall load out. Raw absolutes tell the same story.", note))
    S.append(Spacer(1, 4))
    S.append(Paragraph(
        "<b>The physics is nearly free.</b> The wire-damage-off run keeps the entire realistic "
        "distribution — per-wire loss, the TreeMap sort by loss rate, proportional splitting, the "
        "double simulate/real pass, the burnout ledger — and transferEnergy costs 1212 ms against "
        "city mode's stripped-down cityModeTransfer at 996 ms. That 216 ms is the price of "
        "everything city mode removes from power behaviour: about 1.6% of active CPU.", note))
    S.append(Spacer(1, 4))
    S.append(Paragraph(
        "The floodlight, machine and generator subsystems did not register in these captures — "
        "TileEntityFloodlight.update came in at 0.04%, effectively absent. That neither vindicates "
        "nor refutes them: the profiled area simply had no lit floodlights and no idle machines "
        "holding unusable input. Their value depends entirely on what is built and loaded, so measure "
        "them where they exist, toggling the sub-flags individually. The floodlight work was predicted "
        "to rival the wire saving in a lit city; that prediction remains unmeasured.", note))

    S.append(KeepTogether([
        Paragraph("Where the saving actually comes from", h2),
        Image(chart_b, width=6.3 * inch, height=2.44 * inch),
    ]))
    S.append(Spacer(1, 10))
    S.append(Paragraph(
        "This was a surprise, and it overturned the estimate this document previously carried. City "
        "mode's conceptual heart — replacing the loss maths, the TreeMap sort by loss rate, the "
        "proportional split and the double simulate/real pass with a single lossless push — accounts "
        "for about 6% of the saving. Essentially all of it is the removal of one whole-network "
        "broadcast per connector per tick.", body))
    S.append(Paragraph(
        "That has a consequence worth acting on. The broadcast exists solely to feed wire-shock "
        "damage, so most of this performance is available <i>without</i> giving up loss, voltage tiers "
        "or wire burnout. The broadcast now respects enableWireDamage — it previously ran every tick "
        "even with the feature switched off — and computing damage lazily on entity-wire collision "
        "would give the realistic grid most of city mode's speedup.", body))
    S.append(Paragraph(
        "One thing did <i>not</i> improve: getIndirectEnergyConnections was unchanged despite being "
        "called once per tick instead of three times, so its cost is cache misses being re-flooded "
        "rather than call count. It is now the largest remaining Immersive Engineering cost.", body))

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
        ["Floodlight beams", "re-traced every 512 ticks", "only on switch / neighbour change"],
        ["Floodlight light count", "uncapped", "capped at 64 per lamp"],
        ["Generator fuel burn", "per-tick rate from the fluid", "1 mB every 20 ticks, cosmetic"],
        ["Generator load gate", "only runs under load", "<b>unchanged — deliberately kept</b>"],
        ["Idle machine recipe scan", "every tick", "every 32 ticks"],
        ["Machine speed / power needs", "—", "unchanged"],
    ]
    data = [[Paragraph(c, cellb if i == 0 else (cellb if j == 0 else cell))
             for j, c in enumerate(r)] for i, r in enumerate(rows)]
    S.append(tbl(data, [1.5 * inch, 2.75 * inch, 2.3 * inch]))

    S.append(Paragraph("Three consequences worth knowing", h2))
    S.append(Paragraph("Floodlights stop noticing distant obstructions", h3))
    S.append(Paragraph(
        "A floodlight is usually the most expensive block in a city build. Every 512 ticks each one "
        "re-traces thirteen beams, queues a light block roughly every third block along each, and "
        "recalculates block lighting — and every light it places is an individually ticking tile "
        "entity, uncapped, so one unobstructed lamp can own well over a hundred. City mode rebuilds "
        "only when the light switches or a neighbouring block changes. The cost is that a wall built "
        "across a beam further out is not noticed until something else triggers a rebuild, leaving "
        "the lights beyond it floating until the lamp is toggled.", body))
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
        "<b>Yes.</b> A diesel generator gates on two conditions: something must actually accept "
        "power, and there must be fuel in the tank. With no consumers, connector buffers saturate, "
        "the generator's simulated insert returns zero, the fan spins down and no fuel burns — in "
        "both modes. What city mode makes cosmetic is the <i>rate</i>: instead of deriving a per-tick "
        "burn rate from the fluid and draining every tick, it sips 1 mB every 20 ticks, so a full "
        "tank lasts about six and a half hours of runtime.", body))
    S.append(Paragraph(
        "The load gate is kept deliberately. Only running when something wants power is a "
        "<i>performance</i> feature, not a realism one — it is what makes an idle generator free. "
        "Removing it would make city mode slower, not faster, because generators would push energy "
        "at saturated connectors every tick only for it to be discarded.", body))
    S.append(Paragraph(
        "City mode also raises the yield per unit of fuel, because the 4096 FE/t leaving the "
        "generator arrives undiminished instead of being attenuated by every wire segment on the "
        "way. More useful power per bucket of biodiesel, but never power from nothing — only what "
        "receivers actually accepted is deducted from the connector.", body))

    S.append(Paragraph("Block-by-block summary", h2))
    rows = [
        ["Block", "Role", "Affected by city mode"],
        ["Diesel Generator", "4096 FE/t, burns fuel only under load", "<b>Yes</b> — fuel burn becomes a token sip; load gate kept"],
        ["Thermoelectric / Dynamo", "push to adjacent blocks only", "No — already cached or event-driven"],
        ["Windmill / Water Wheel", "drive the dynamo, produce no FE", "No — already cached and throttled"],
        ["Floodlight", "13 beams of ticking light blocks", "<b>Yes</b> — no timed re-scan, 64-light cap"],
        ["Multiblock machines", "Arc Furnace, Squeezer, Fermenter, Mixer, Refinery", "<b>Yes</b> — idle recipe scan throttled to 32 ticks"],
        ["Capacitor LV/MV/HV", "buffer; 256 / 1024 / 4096 FE/t", "No"],
        ["Creative Capacitor", "infinite source, all six sides", "No — works as an infinite source in both modes"],
        ["Lightning Rod", "16,000,000 FE on a strike", "No"],
        ["Connector LV/MV/HV", "the only blocks moving energy over wires", "<b>Yes</b> — push path replaced; its own rate caps still apply"],
        ["Relay LV/MV/HV", "routing waypoint, never holds energy", "No — tick body never runs either way"],
        ["Transformer", "tier adapter; holds no energy", "<b>Yes</b> — tier throttling disappears"],
        ["Breaker Switch", "interrupts the network", "No — still cuts"],
        ["Energy Meter", "passive throughput accumulator", "Works, and reads the full unattenuated amount"],
        ["Machine power intake", "receive by push into their own buffer", "No — their own intake caps still apply"],
        ["Direct generator → machine", "no wires involved", "No — entirely unaffected"],
    ]
    data = [[Paragraph(c, cellb if i == 0 else (cellb if j == 0 else cell))
             for j, c in enumerate(r)] for i, r in enumerate(rows)]
    S.append(tbl(data, [1.55 * inch, 2.15 * inch, 2.85 * inch]))

    S.append(Spacer(1, 10))
    S.append(Paragraph(
        "Full technical detail, including the annotated transfer routine, wire-type tables and the "
        "testing checklist, is in <font face='Courier'>docs/CITY_MODE_AND_PERF.md</font>.", note))

    doc.build(S)


if __name__ == "__main__":
    import tempfile
    # The charts are build intermediates, not deliverables - keep them out of the repo.
    with tempfile.TemporaryDirectory() as tmp:
        a = os.path.join(tmp, "chart_cpu.png")
        b = os.path.join(tmp, "chart_work.png")
        chart_cpu_share(a)
        chart_work(b)
        out = os.path.join(DOCS, "CITY_MODE_AND_PERF.pdf")
        build_pdf(out, a, b)
    print("wrote", out, os.path.getsize(out), "bytes")
