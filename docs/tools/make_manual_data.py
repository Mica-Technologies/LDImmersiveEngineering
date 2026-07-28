#!/usr/bin/env python3
"""
Generates the data-driven half of the LDImmersiveEngineering manual.

The manual has two kinds of content.  The prose -- what a system is for, how it behaves, why
it was built the way it was -- is written by hand in docs/manual/chapters/.  The *tables* --
every block, every item, every fluid, every config option, every recipe -- are generated from
the source tree by this script, into docs/manual/generated/.

That split is the whole point.  A hand-written list of blocks is out of date the first time
somebody adds one, and nobody notices until a player goes looking for something the manual
promised.  A generated one is wrong only if the code is.

Run it before building the PDF; `docs/manual/build.sh` does that for you.

Usage:  python docs/tools/make_manual_data.py [--repo <path>]

No dependencies beyond the standard library.
"""

import argparse
import io
import json
import os
import re

# ---------------------------------------------------------------------------
# LaTeX escaping.  Mod text is full of characters TeX treats as syntax.
# ---------------------------------------------------------------------------
_TEX_ESCAPES = {
    "\\": r"\textbackslash{}",
    "&": r"\&", "%": r"\%", "$": r"\$", "#": r"\#",
    "_": r"\_", "{": r"\{", "}": r"\}",
    "~": r"\textasciitilde{}", "^": r"\textasciicircum{}",
}


def tex(text):
    """Escape a string for LaTeX, and strip Minecraft's formatting codes.

    The section sign introduces a colour code in Minecraft and is a maths shift in TeX, so
    leaving them in is both wrong and a compile error.  `<br>` is Minecraft's manual line
    break; it becomes a paragraph break here.
    """
    if text is None:
        return ""
    text = re.sub(r"§[0-9a-fk-or]", "", text)
    text = text.replace("<br>", "\n\n")
    return "".join(_TEX_ESCAPES.get(ch, ch) for ch in text)


def read(path):
    with io.open(path, encoding="utf-8", errors="replace") as handle:
        return handle.read()


def write(path, body):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(body)
    return path


# ---------------------------------------------------------------------------
# The language file: the mod's own names for everything.
# ---------------------------------------------------------------------------
def load_lang(repo):
    """Every key=value in en_us.lang, which is where the player-facing names live."""
    path = os.path.join(repo, "src/main/resources/assets/immersiveengineering/lang/en_us.lang")
    entries = {}
    for line in read(path).split("\n"):
        line = line.strip()
        if not line or line.startswith("#") or line.startswith("//") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        entries[key.strip()] = value.strip()
    return entries


# ---------------------------------------------------------------------------
# Blocks, from the meta enums.
# ---------------------------------------------------------------------------
def find_block_enums(repo):
    """Every BlockTypes_* enum in the tree, with its constants in declaration order.

    Declaration order *is* metadata order -- every one of these enums returns `ordinal()` from
    `getMeta()` -- so the order here is the order a player sees in the creative tab and the
    order the save file uses.
    """
    root = os.path.join(repo, "src/main/java/blusunrize/immersiveengineering/common/blocks")
    found = {}
    for base, _dirs, files in os.walk(root):
        for name in files:
            if not name.startswith("BlockTypes_") or not name.endswith(".java"):
                continue
            source = read(os.path.join(base, name))
            # The enum body runs from the brace after `public enum ...` to the semicolon that
            # closes the constant list.
            #
            # Located by index rather than by splitting on braces: the javadoc on these enums is
            # full of {@code} and {@link}, and splitting on the second brace lands in the middle
            # of a comment. That silently produced a manual missing more than half the blocks in
            # the mod -- the sort of wrong that looks fine until somebody goes looking for a
            # block it never mentioned.
            head = re.search(r"public enum \w+[^{]*\{", source)
            if not head:
                continue
            end = source.find(";", head.end())
            body = source[head.end():end if end > 0 else len(source)]
            # Constants may carry constructor arguments, so allow a parenthesised tail.
            constants = re.findall(r"^\s*([A-Z][A-Z0-9_]*)\s*(?:\([^)]*\))?\s*(?:,|$)",
                                   body, re.M)
            if constants:
                found[name[len("BlockTypes_"):-len(".java")]] = constants
    return found


def block_registry_name(enum_name):
    """`MetalDecoration1` -> `metal_decoration1`, which is how lang keys are spelled."""
    out = re.sub(r"(?<!^)(?=[A-Z])", "_", enum_name).lower()
    return re.sub(r"_(\d)", r"\1", out)


def emit_blocks(repo, lang, out_dir):
    enums = find_block_enums(repo)
    lines = ["% Generated by docs/tools/make_manual_data.py -- do not edit by hand.", ""]
    total = 0
    for enum_name in sorted(enums):
        registry = block_registry_name(enum_name)
        constants = enums[enum_name]
        lines.append(r"\subsection{%s}" % tex(enum_name))
        lines.append(r"\label{blk:%s}" % registry)
        lines.append(r"Registry name \texttt{%s}. %d metadata value%s."
                     % (tex(registry), len(constants), "" if len(constants) == 1 else "s"))
        lines.append(r"\begin{blocktable}")
        for meta, constant in enumerate(constants):
            key = "tile.immersiveengineering.%s.%s.name" % (registry, constant.lower())
            name = lang.get(key, "")
            info = lang.get("tile.immersiveengineering.%s.%s.info" % (registry, constant.lower()), "")
            lines.append(r"%d & \texttt{%s} & %s & %s \\" % (
                meta, tex(constant.lower()), tex(name) or r"\itshape unnamed",
                tex(info[:180] + ("…" if len(info) > 180 else "")) or r"\itshape ---"))
            total += 1
        lines.append(r"\end{blocktable}")
        lines.append("")
    lines.insert(2, r"There are %d block states across %d block registrations."
                 % (total, len(enums)))
    lines.insert(3, "")
    return write(os.path.join(out_dir, "blocks.tex"), "\n".join(lines) + "\n"), total


# ---------------------------------------------------------------------------
# Config, from the @Comment annotations.
# ---------------------------------------------------------------------------
def emit_config(repo, out_dir):
    """Every config field, with the comment that documents it.

    Read out of Config.java rather than out of a generated .cfg, so the manual documents what
    the mod *means* by an option rather than only what its default is.
    """
    source = read(os.path.join(repo, "src/main/java/blusunrize/immersiveengineering/common/Config.java"))
    pattern = re.compile(
        r"@Comment\((\{.*?\}|\".*?\")\)\s*(?:@[A-Za-z.]+(?:\([^)]*\))?\s*)*"
        r"public static (?:final )?(\w+(?:\[\])?) (\w+)\s*(?:=\s*([^;]+))?;",
        re.S)
    rows = []
    for match in pattern.finditer(source):
        raw_comment, field_type, name, default = match.groups()
        parts = re.findall(r'"((?:[^"\\]|\\.)*)"', raw_comment)
        comment = " ".join(p.replace('\\"', '"') for p in parts)
        default = (default or "").strip().replace("\n", " ")
        default = re.sub(r"\s+", " ", default)
        rows.append((name, field_type, default, comment))

    lines = ["% Generated by docs/tools/make_manual_data.py -- do not edit by hand.", "",
             r"%d documented options." % len(rows), ""]
    for name, field_type, default, comment in rows:
        lines.append(r"\configentry{%s}{%s}{%s}{%s}" % (
            tex(name), tex(field_type), tex(default[:60]) or "---", tex(comment)))
        lines.append("")
    return write(os.path.join(out_dir, "config.tex"), "\n".join(lines) + "\n"), len(rows)


# ---------------------------------------------------------------------------
# Recipes, from the JSON.
# ---------------------------------------------------------------------------
def describe_ingredient(node):
    if node is None:
        return "?"
    if isinstance(node, list):
        return " or ".join(describe_ingredient(n) for n in node)
    if "item" in node:
        name = node["item"]
        if name.startswith("#"):
            return name[1:]
        name = name.split(":")[-1]
        if "data" in node:
            name += "/%s" % node["data"]
        return name
    if "type" in node and node["type"] == "forge:ore_dict":
        return node.get("ore", "?")
    return node.get("ore", "?")


def emit_recipes(repo, lang, out_dir):
    """Crafting-table recipes, grouped by the folder they live in."""
    root = os.path.join(repo, "src/main/resources/assets/immersiveengineering/recipes")
    groups = {}
    for base, _dirs, files in os.walk(root):
        group = os.path.relpath(base, root).replace("\\", "/")
        if group == ".":
            group = "core"
        for name in sorted(files):
            if not name.endswith(".json") or name.startswith("_"):
                continue
            try:
                body = json.loads(read(os.path.join(base, name)))
            except ValueError:
                continue
            result = body.get("result")
            if not isinstance(result, dict):
                continue
            item = result.get("item", "?").split(":")[-1]
            if "data" in result:
                item += "/%s" % result["data"]
            count = result.get("count", 1)
            pattern = body.get("pattern")
            key = body.get("key", {})
            if pattern:
                shape = r" \newline ".join(tex(row) for row in pattern)
                inputs = ", ".join("%s = %s" % (tex(k), tex(describe_ingredient(v)))
                                   for k, v in sorted(key.items()))
                recipe = "%s \\newline \\emph{%s}" % (shape, inputs)
            else:
                items = body.get("ingredients", [])
                recipe = tex(", ".join(describe_ingredient(i) for i in items)) or "---"
            groups.setdefault(group, []).append((item, count, recipe))

    lines = ["% Generated by docs/tools/make_manual_data.py -- do not edit by hand.", ""]
    total = sum(len(v) for v in groups.values())
    lines.append(r"%d crafting recipes across %d groups." % (total, len(groups)))
    lines.append("")
    for group in sorted(groups):
        lines.append(r"\subsection{%s}" % tex(group))
        lines.append(r"\begin{recipetable}")
        for item, count, recipe in sorted(groups[group]):
            lines.append(r"\texttt{%s} & %d & %s \\" % (tex(item), count, recipe))
        lines.append(r"\end{recipetable}")
        lines.append("")
    return write(os.path.join(out_dir, "recipes.tex"), "\n".join(lines) + "\n"), total


# ---------------------------------------------------------------------------
# The Engineer's Manual chapters, which are already written prose.
# ---------------------------------------------------------------------------
def emit_ingame_manual(repo, lang, out_dir):
    """The in-game Engineer's Manual, transcribed.

    Those pages are already the mod's own explanation of itself, written for the player rather
    than for a developer. Carrying them into the PDF means the two cannot disagree, and it gives
    every chapter here a canonical short version to sit beside.
    """
    entries = {}
    for key, value in lang.items():
        match = re.match(r"ie\.manual\.entry\.(\w+?)(\d+)$", key)
        if match:
            entries.setdefault(match.group(1), {})[int(match.group(2))] = value
    lines = ["% Generated by docs/tools/make_manual_data.py -- do not edit by hand.", ""]
    lines.append(r"%d chapters, transcribed from the in-game book." % len(entries))
    lines.append("")
    for name in sorted(entries):
        title = lang.get("ie.manual.entry.%s.name" % name, name)
        subtext = lang.get("ie.manual.entry.%s.subtext" % name, "")
        lines.append(r"\subsection{%s}" % tex(title))
        if subtext:
            lines.append(r"\emph{%s}" % tex(subtext))
            lines.append("")
        for page in sorted(entries[name]):
            lines.append(tex(entries[name][page]))
            lines.append("")
    return write(os.path.join(out_dir, "ingame.tex"), "\n".join(lines) + "\n"), len(entries)


def emit_stats(out_dir, counts):
    lines = ["% Generated by docs/tools/make_manual_data.py -- do not edit by hand.", ""]
    for key, value in counts.items():
        lines.append(r"\newcommand{\stat%s}{%s}" % (key, value))
    return write(os.path.join(out_dir, "stats.tex"), "\n".join(lines) + "\n")


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    default_repo = os.path.dirname(os.path.dirname(here))
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default=default_repo)
    args = parser.parse_args()

    out_dir = os.path.join(args.repo, "docs", "manual", "generated")
    lang = load_lang(args.repo)

    _, blocks = emit_blocks(args.repo, lang, out_dir)
    _, options = emit_config(args.repo, out_dir)
    _, recipes = emit_recipes(args.repo, lang, out_dir)
    _, chapters = emit_ingame_manual(args.repo, lang, out_dir)
    emit_stats(out_dir, {
        "Blocks": blocks, "Options": options, "Recipes": recipes,
        "Chapters": chapters, "LangKeys": len(lang),
    })

    print("blocks:   %4d block states" % blocks)
    print("config:   %4d options" % options)
    print("recipes:  %4d crafting recipes" % recipes)
    print("in-game:  %4d manual chapters" % chapters)
    print("lang:     %4d keys" % len(lang))
    print("wrote docs/manual/generated/")


if __name__ == "__main__":
    main()
