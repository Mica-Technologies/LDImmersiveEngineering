#!/usr/bin/env bash
# Builds docs/manual/MANUAL.pdf.
#
# Regenerates the data-driven chapters first, always. Building without doing so would produce a
# manual describing whatever the tree looked like the last time somebody remembered -- which is
# the exact failure the generator exists to prevent.
#
# Needs a LaTeX toolchain on PATH. MiKTeX is the easy one on Windows: it fetches missing packages
# on the first build, so nothing here has to be installed by hand.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"

echo "== regenerating tables from source"
python "$repo/docs/tools/make_manual_data.py" --repo "$repo"

# MiKTeX installs per-user and does not always land on PATH -- especially in a shell that was
# already open when it was installed. Look where it actually puts itself before giving up.
for candidate in \
	"${LOCALAPPDATA:-}/Programs/MiKTeX/miktex/bin/x64" \
	"$HOME/AppData/Local/Programs/MiKTeX/miktex/bin/x64" \
	"/c/Program Files/MiKTeX/miktex/bin/x64"; do
	if [ -x "$candidate/pdflatex.exe" ]; then
		PATH="$candidate:$PATH"
		break
	fi
done

if ! command -v pdflatex >/dev/null 2>&1 && ! command -v latexmk >/dev/null 2>&1; then
	echo "no LaTeX toolchain found. Install MiKTeX, or put pdflatex on PATH." >&2
	exit 1
fi

echo "== building PDF"
cd "$here"
if command -v latexmk >/dev/null 2>&1; then
	latexmk -pdf -interaction=nonstopmode -halt-on-error MANUAL.tex
	latexmk -c >/dev/null 2>&1 || true
else
	# Three passes by hand: the table of contents needs the second, and page references
	# inside it need the third.
	for pass in 1 2 3; do
		pdflatex -interaction=nonstopmode -halt-on-error MANUAL.tex >/dev/null
	done
fi

echo "== wrote $here/MANUAL.pdf"
