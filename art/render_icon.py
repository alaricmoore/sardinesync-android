"""Render the SardineSync 'spike' logo (candidate A) at any size.

Geometry lives in 108x108 design units, drawn supersampled then downscaled.
Variants:
  square      - full-bleed dark plate (fastlane, favicons, manifest)
  rounded     - dark plate with rounded corners (legacy launcher PNGs)
  foreground  - transparent, mark scaled 0.92 into the adaptive safe zone
"""
import sys
from PIL import Image, ImageDraw

BG = (0x1E, 0x1E, 0x28, 255)

# (x, y, w, h, color) in design units; rx is 2.5 for all segments
SEGMENTS = [
    (29, 68,   12, 10, "#66bb6a"),
    (29, 58.5, 12, 8,  "#d4b84a"),
    (48, 66,   12, 12, "#c94040"),
    (48, 54.5, 12, 10, "#9b72cf"),
    (48, 37,   12, 16, "#e85d9e"),
    (48, 27.5, 12, 8,  "#d4b84a"),
    (67, 70,   12, 8,  "#5b9bd5"),
    (67, 58.5, 12, 10, "#66bb6a"),
    (67, 51,   12, 6,  "#e0a050"),
]
SEG_RX = 2.5

DASH_Y, DASH_X0, DASH_X1 = 45, 20, 88
DASH_W, DASH_ON, DASH_OFF = 3.5, 7, 5
DASH_COLOR = "#b6b2c6"

CORNER_RX = 20  # for the rounded variant


def render(size: int, variant: str, ss: int = 8) -> Image.Image:
    canvas = size * ss
    s = canvas / 108.0
    img = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    if variant == "square":
        d.rectangle([0, 0, canvas, canvas], fill=BG)
    elif variant == "rounded":
        d.rounded_rectangle([0, 0, canvas - 1, canvas - 1], radius=CORNER_RX * s, fill=BG)

    # adaptive foreground: shrink the mark into the 66/108 safe zone
    if variant == "foreground":
        def tx(v, c=54):
            return (c + (v - c) * 0.92) * s
        def tw(v):
            return v * 0.92 * s
    else:
        def tx(v, c=54):
            return v * s
        def tw(v):
            return v * s

    for x, y, w, h, color in SEGMENTS:
        d.rounded_rectangle(
            [tx(x), tx(y), tx(x) + tw(w), tx(y) + tw(h)],
            radius=tw(SEG_RX), fill=color,
        )

    # dashed threshold line as rounded pills. SVG round caps extend half the
    # stroke width past each dash end, so each pill grows by DASH_W/2 per side
    # to match how browsers rendered the mockup.
    half = tw(DASH_W) / 2
    cy = tx(DASH_Y)
    x = DASH_X0
    while x < DASH_X1:
        seg_end = min(x + DASH_ON, DASH_X1)
        d.rounded_rectangle(
            [tx(x) - half, cy - half, tx(seg_end) + half, cy + half],
            radius=half, fill=DASH_COLOR,
        )
        x = seg_end + DASH_OFF

    return img.resize((size, size), Image.LANCZOS)


if __name__ == "__main__":
    out, size, variant = sys.argv[1], int(sys.argv[2]), sys.argv[3]
    im = render(size, variant)
    if len(sys.argv) > 4 and sys.argv[4] == "opaque":
        flat = Image.new("RGB", im.size, BG[:3])
        flat.paste(im, mask=im.split()[3])
        flat.save(out)
    else:
        im.save(out)
    print(f"{out} {size}x{size} {variant}")
