#!/usr/bin/env bash
# Vendor the sardinetracker server into this module's Python source dir.
#
# The embedded app runs the SAME code as a self-hosted server — this script
# copies it from a sibling checkout of sardine-track-public. Rerun after any
# upstream change and commit the result; never hand-edit the vendored copy
# (fixes go upstream first, then re-vendor).
set -euo pipefail

SRC="${1:-$(dirname "$0")/../../sardine-track-public}"
DST="$(dirname "$0")/src/main/python/tracker"

if [ ! -f "$SRC/app.py" ]; then
    echo "error: sardine-track-public checkout not found at $SRC" >&2
    exit 1
fi

mkdir -p "$DST"
rm -rf "$DST"/templates "$DST"/images
cp "$SRC"/{app.py,db.py,setup.py,severity_vocab.py,uv_fetcher.py} "$DST/"
cp -r "$SRC/templates" "$DST/templates"

# The app's /favicon/ route serves from images/favicon/ — reuse the set the
# landing page ships (same spike mark as the launcher icon).
mkdir -p "$DST/images/favicon"
for f in favicon.ico favicon.svg favicon-96x96.png apple-touch-icon.png; do
    if [ -f "$SRC/site/public/$f" ]; then
        cp "$SRC/site/public/$f" "$DST/images/favicon/$f"
    fi
done

UPSTREAM=$(git -C "$SRC" rev-parse --short HEAD 2>/dev/null || echo unknown)
echo "vendored from sardine-track-public @ $UPSTREAM" > "$DST/VENDORED"
echo "done: $DST (upstream $UPSTREAM)"
