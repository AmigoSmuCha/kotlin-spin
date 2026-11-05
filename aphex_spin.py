#!/usr/bin/env python3
"""aphex_spin: an inflated chrome logo, raymarched and drawn in ASCII in your terminal.

    python3 aphex_spin.py                    run it (60 fps, in parallel on every core)
    python3 aphex_spin.py --palette "axel clay" --material gloss --backdrop
    python3 aphex_spin.py --preview          print one frame and exit (no mouse needed)
    python3 aphex_spin.py --png model.png    render the 3D model itself as an image
    python3 aphex_spin.py --logo logo.png    inflate another logo, from an image or ASCII
                                             art (needs Pillow, scipy, scikit-image once)

Mouse   drag    spin it; let go mid-swing and it keeps the momentum
        hover   the sun follows the pointer: glints and sparkles slide with it
        wheel   zoom
Keys    lowercase goes forward, uppercase back
        t T palette   m M finish   g G glyph mode   c C glyph palette
        b sky   s stars   o ground   l hold the sun   + - spin speed   [ ] contrast
        space pause   r reset   f frame rate   h hints   q quit

Palettes
  aphex (the original sky) and the focus / cornholio Design System's own:
  axel clay, miasma, gruvbox, graphite, dracula, nord, catppuccin, integral lab.
  Each is closed: every colour on screen is derived from its six roles (ground,
  ink, the accent lamp, the cold title, panel, caution). The chrome reflects a
  sky in the title's cold hue over a sea of cloud in the lamp's, highlights
  converge on ink (never pure white), the ground is painted behind it all, and
  a change of palette cross-fades everything at once over 0.9 s.

How it is made
  model   A balloon. The outline (traced at sub-pixel precision from the logo
          image, so its rounded corners are the real ones) is inflated like a
          membrane under pressure: phi solves -lap(phi) = 1 inside with phi = 0
          on the outline (conjugate gradients, once, then cached), and the
          surface is z^2 = Q(phi). A strip of width w becomes a tube of
          diameter w and a disc a sphere, so thin parts get small, tight
          rounding and the wide forehead swells into a pillow.
  render  Orthographic sphere tracing, six rays per character cell. Each ray
          starts just in front of where its neighbourhood hit last frame (the
          surface can only have moved by the rotation angle times the radius),
          steps are over-relaxed with a secant pull-back, empty space is leapt
          on a rigorous voxel distance bound, and grazing rays are cone-traced
          to their sample's footprint and anti-aliased from how near they passed.
          Worker processes trace interleaved rows into shared memory while the
          main process turns the previous frame into text.
  shade   Chrome reflecting the sky above the clouds, or glossy plastic under
          studio softboxes: Fresnel, Blinn-Phong, ACES tone mapping. The
          environments are baked into octahedral maps per palette; the sun's
          glints stay analytic and get twinkling four-point stars.
  ascii   Shape-based glyph choice after Alex Harri, "ASCII characters are not
          pixels" (alexharri.com/blog/ascii-rendering): each cell's six samples
          are compared with six sampling circles measured on every printable
          glyph of DejaVu Sans Mono, after directional and global contrast
          enhancement, so the shading is a rich mix of letters and symbols while
          edges are drawn by glyphs whose shape follows them. Truecolor ink per
          cell, only changed rows sent, inside synchronized output. The hash,
          value noise and the ramp mode's 4x4 Bayer blend follow asciify-engine
          (ayangabryl, MIT).

Needs Python 3.8+ and numpy.
"""
import argparse
import hashlib
import math
import os
import re
import select
import signal
import struct
import sys
import time
import zlib

import numpy as np

# --------------------------------------------------------------------------- #
# The outline, traced from the ASCII logo. Units: one terminal column; a row
# is two units tall, so the proportions are the art's as it appears on screen.
# --------------------------------------------------------------------------- #
LOGO_POLY = [(19.305, -17.443), (19.841, -17.204), (20.196, -16.939), (20.566, -16.535), (20.830, -16.058), (20.961, -15.644), (21.000, -15.039), (20.946, -14.561), (11.140, 13.039), (10.874, 13.675), (10.367, 14.599), (9.856, 15.299), (9.118, 16.062), (8.454, 16.572), (7.526, 17.071), (6.826, 17.316), (6.285, 17.441), (5.393, 17.531), (-16.540, 17.544), (-17.527, 17.459), (-18.355, 17.200), (-18.864, 16.943), (-19.246, 16.687), (-19.564, 16.424), (-19.946, 16.036), (-20.328, 15.528), (-20.583, 15.079), (-20.831, 14.439), (-20.951, 13.930), (-21.000, 13.389), (-20.995, 12.816), (-20.952, 12.434), (-20.826, 11.893), (-20.583, 11.275), (-20.323, 10.810), (-20.067, 10.460), (-19.658, 10.014), (-19.161, 9.601), (-18.758, 9.346), (-18.215, 9.091), (-17.814, 8.960), (-17.209, 8.836), (-16.604, 8.805), (-10.492, 8.805), (-9.823, 8.843), (-9.346, 8.955), (-8.966, 9.091), (-8.457, 9.346), (-7.882, 9.728), (-6.990, 10.516), (-5.381, 12.338), (-5.074, 12.593), (-4.794, 12.748), (-4.475, 12.859), (-4.061, 12.895), (-3.711, 12.861), (-3.361, 12.734), (-3.011, 12.495), (-2.788, 12.265), (-2.637, 12.052), (-2.501, 11.735), (-2.437, 11.129), (-2.507, 10.715), (-2.644, 10.396), (-13.563, -9.245), (-13.694, -9.563), (-13.758, -9.945), (-13.767, -10.232), (-13.719, -10.582), (-13.564, -11.028), (-13.422, -11.251), (-13.134, -11.571), (-12.752, -11.843), (-12.497, -11.955), (-11.924, -12.058), (-11.256, -11.973), (-10.746, -11.718), (-10.558, -11.569), (-10.301, -11.289), (-4.623, -4.279), (-4.221, -3.850), (-3.870, -3.598), (-3.520, -3.492), (-2.247, -3.418), (-1.677, -3.292), (-1.324, -3.165), (-0.645, -2.783), (-0.082, -2.286), (0.171, -1.987), (0.405, -1.637), (0.675, -1.064), (0.807, -0.618), (0.888, 0.178), (0.813, 0.942), (0.678, 1.419), (0.427, 1.960), (0.098, 2.502), (-0.027, 2.884), (-0.070, 3.393), (-0.018, 3.850), (0.093, 4.189), (0.222, 4.444), (0.484, 4.794), (0.813, 5.080), (1.255, 5.328), (1.669, 5.448), (2.019, 5.477), (2.401, 5.452), (2.847, 5.323), (3.282, 5.080), (3.610, 4.794), (3.865, 4.457), (16.557, -16.440), (16.981, -16.933), (17.331, -17.196), (17.554, -17.316), (17.904, -17.453), (18.127, -17.494), (18.541, -17.544), (18.859, -17.524)]

# Shape vectors of the 95 printable ASCII glyphs (DejaVu Sans Mono): the share
# of ink inside each sampling circle, normalised per circle across glyphs.
GLYPH_CHARS = "".join(chr(c) for c in range(32, 127))
GLYPH_VEC = (
    "0.000,0.000,0.000,0.000,0.000,0.000,0.419,0.279,0.291,0.387,0.105,0.211,0.512,0.406,0.025,0.140,"
    "0.000,0.000,0.442,0.492,0.908,1.000,0.257,0.252,0.655,0.490,0.524,0.624,0.599,0.687,0.684,0.001,"
    "0.572,0.627,0.000,0.480,0.800,0.449,0.811,0.541,0.471,0.704,0.375,0.243,0.000,0.052,0.000,0.000,"
    "0.237,0.381,0.480,0.193,0.178,0.417,0.449,0.078,0.165,0.490,0.410,0.305,0.559,0.395,0.261,0.405,"
    "0.000,0.000,0.025,0.000,0.646,0.664,0.011,0.108,0.000,0.000,0.001,0.000,0.491,0.404,0.000,0.000,"
    "0.239,0.200,0.000,0.000,0.000,0.000,0.002,0.000,0.135,0.265,0.000,0.452,0.432,0.434,0.492,0.000,"
    "0.824,0.730,0.780,0.841,0.369,0.506,0.612,0.421,0.210,0.518,0.375,0.597,0.643,0.744,0.368,0.556,"
    "0.545,0.444,0.597,0.739,0.190,0.774,0.516,0.538,0.274,0.645,0.706,0.708,0.000,0.355,0.898,0.533,"
    "0.285,0.611,0.527,0.514,0.770,0.597,0.775,0.634,0.374,0.537,0.626,0.889,0.265,0.547,0.230,0.052,"
    "0.873,0.780,0.724,0.761,0.441,0.560,0.872,0.725,0.538,0.861,0.429,0.473,0.000,0.000,0.110,0.241,"
    "0.135,0.265,0.000,0.000,0.109,0.241,0.491,0.404,0.000,0.000,0.701,0.495,0.000,0.216,0.000,0.000,"
    "0.754,0.652,0.000,0.000,0.040,0.000,0.559,0.698,0.000,0.000,0.530,0.787,0.312,0.536,0.137,0.175,"
    "0.540,0.474,0.765,0.766,0.686,0.760,0.630,0.424,0.768,0.690,0.219,0.347,0.971,0.711,0.807,0.791,"
    "0.525,0.548,0.773,0.722,0.636,0.000,0.268,0.479,1.000,0.600,0.592,0.650,0.532,0.455,0.942,0.688,"
    "0.792,0.379,0.499,0.480,0.910,0.725,0.775,0.376,0.273,0.000,0.802,0.700,0.628,0.445,0.323,0.590,"
    "0.588,0.453,0.814,0.871,0.248,0.345,0.767,0.679,0.390,0.406,0.450,0.491,0.370,0.745,0.000,0.631,"
    "0.526,0.451,0.588,0.550,1.000,0.545,0.248,0.425,0.605,0.000,0.616,0.000,0.484,0.511,0.896,0.684,"
    "0.837,0.976,0.196,0.295,0.947,0.435,0.825,0.928,0.238,0.550,0.842,0.749,0.619,0.632,0.388,0.518,"
    "0.942,0.762,0.823,0.645,0.266,0.000,0.842,0.749,0.618,0.632,0.393,0.788,0.993,0.694,0.852,0.782,"
    "0.250,0.368,0.842,0.650,0.427,0.579,0.507,0.544,0.932,0.882,0.384,0.410,0.105,0.229,0.592,0.457,"
    "0.599,0.610,0.416,0.529,0.600,0.458,0.536,0.573,0.162,0.342,0.496,0.335,0.927,0.979,0.300,0.442,"
    "0.607,0.503,0.604,0.714,0.256,0.374,0.622,0.503,0.498,0.579,0.107,0.227,0.583,1.000,0.467,0.491,"
    "0.538,0.536,0.598,0.477,0.454,0.221,0.551,0.441,0.567,0.000,0.395,0.246,0.000,0.495,0.512,0.503,"
    "0.205,0.472,0.481,0.534,0.674,0.518,0.011,0.156,0.000,0.000,0.000,0.000,0.000,0.000,0.494,0.043,"
    "0.416,0.214,0.000,0.000,0.000,0.000,0.191,0.000,0.526,0.830,0.469,0.622,0.665,0.000,0.638,0.649,"
    "0.463,0.528,0.049,0.000,0.613,0.256,0.264,0.452,0.153,0.519,0.602,0.726,0.419,0.619,0.109,0.000,"
    "0.837,0.764,0.364,0.470,0.582,0.706,0.413,0.522,0.129,0.167,0.153,0.000,0.607,0.716,0.839,1.000,"
    "0.650,0.000,0.604,0.644,0.241,0.323,0.387,0.310,0.312,0.411,0.427,0.512,0.287,0.327,0.174,0.488,"
    "0.769,0.542,0.647,0.000,0.884,0.633,0.256,0.394,0.831,0.188,0.461,0.202,0.037,0.389,0.212,0.000,"
    "0.810,0.979,0.268,0.467,0.122,0.000,0.604,0.644,0.241,0.323,0.131,0.000,0.611,0.646,0.398,0.523,"
    "0.139,0.000,0.641,0.644,1.000,0.528,0.142,0.000,0.606,0.724,0.407,0.982,0.078,0.000,0.574,0.392,"
    "0.228,0.013,0.130,0.000,0.518,0.534,0.452,0.509,0.583,0.059,0.488,0.370,0.087,0.389,0.094,0.000,"
    "0.559,0.565,0.422,0.598,0.071,0.000,0.536,0.555,0.164,0.354,0.016,0.000,0.844,0.733,0.299,0.437,"
    "0.095,0.000,0.671,0.610,0.282,0.364,0.077,0.000,0.529,0.552,0.850,0.506,0.153,0.000,0.369,0.641,"
    "0.483,0.376,0.339,0.614,0.525,0.303,0.359,0.689,0.438,0.327,0.338,0.354,0.544,0.427,0.680,0.195,"
    "0.278,0.558,0.703,0.377,0.000,0.000,0.439,0.444,0.000,0.000"
)
# Sampling circle centres in cell units (a cell is 1 wide, 2 tall): two
# columns of three, the left lowered and the right raised, as in Harri's.
SAMPLES = ((0.28, 0.42), (0.72, 0.30), (0.28, 1.06), (0.72, 0.94), (0.28, 1.70), (0.72, 1.58))
SAMPLE_R = 0.30
# Which external samples (neighbouring cells) sharpen which internal one.
AFFECT = ((0, 1, 2, 4), (0, 1, 3, 5), (2, 4, 6), (3, 5, 7), (4, 6, 8, 9), (5, 7, 8, 9))

# Finishes. Their colours come from the palette (see PALETTES): `tint` names the
# palette entry that colours the finish.
MATERIALS = {
    "chrome":   dict(env="sky", metal=True, tint="chrome", env_k=1.0),
    "gloss":    dict(env="sky", metal=False, tint="gloss", f0=0.06, shine=150.0, spec=1.0, env_k=1.0),
    "satin":    dict(env="studio", metal=False, tint="satin", f0=0.05, shine=120.0, spec=1.0, env_k=1.15),
    "obsidian": dict(env="studio", metal=False, tint="obsidian", f0=0.06, shine=160.0, spec=1.4, env_k=1.9),
    "gold":     dict(env="sky", metal=True, tint="gold", env_k=1.0),
    "bone":     dict(env="studio", metal=False, tint="bone", f0=0.04, shine=60.0, spec=0.7, env_k=0.9),
}
MATERIAL_ALIASES = {"candy": "gloss", "lilac": "satin"}

# --------------------------------------------------------------------------- #
# Palettes, from the focus / cornholio Design System: six roles each (ground,
# ink, accent the lamp, title the cold highlight, panel, caution) and the
# palette's own aurora. Everything else is derived from these roles, so one
# palette is never mixed with another. "aphex" is the original sky, hand-set.
# --------------------------------------------------------------------------- #
PALETTE_ROLES = {
    "axel clay":    ("#1b1b1b", "#d5c4a1", "#fd4a00", "#83a598", "#201d1b", "#fb4934",
                     ("#f6e8a0", "#f45e18", "#a3283c", "#de3030", "#ffaa3c")),
    "miasma":       ("#161413", "#d7c483", "#83a598", "#b36d43", "#201d1b", "#eca824",
                     ("#eccb7c", "#8b9a13", "#84405a", "#d3869b", "#d8a04a")),
    "gruvbox":      ("#1c1c1c", "#fbf1c7", "#fb4934", "#b8bb26", "#85a598", "#fe8019",
                     ("#96e846", "#48a555", "#2d6671", "#29305c", "#2b174c")),
    "graphite":     ("#222222", "#d8d8d8", "#585858", "#ffe5cf", "#d8d8d8", "#6e6e6e",
                     ("#d8d9fb", "#c5c6fa", "#9ea1f7", "#8b8ef5", "#777cf3")),
    "dracula":      ("#1c1d26", "#f8f8f2", "#ff0000", "#bd93f9", "#2b2e3b", "#ffb86c",
                     ("#f6e8a0", "#ff8f6c", "#c056a0", "#ff5555", "#ffb86c")),
    "nord":         ("#20242d", "#d8dee9", "#7d64a3", "#88c0d0", "#3b4252", "#ebcb8b",
                     ("#ebcb8b", "#ff5555", "#c4a7e7", "#bf616a", "#ebcb8b")),
    "catppuccin":   ("#11111a", "#cdd6f4", "#fab387", "#f5c2e7", "#313244", "#fae3b0",
                     ("#4b3d3a", "#7a5d57", "#b9a7a2", "#d4b2a0", "#fae3b0")),
    "integral lab": ("#262626", "#d5c4a1", "#fd4a00", "#83a598", "#201d1b", "#cc241d",
                     ("#da1b61", "#fd4a00", "#5e4b8b", "#fb4934", "#fdab3c")),
}
PALETTE_ORDER = ["aphex"] + list(PALETTE_ROLES)


def _hex(h):
    h = h.lstrip("#")
    return np.array([int(h[i:i + 2], 16) for i in (0, 2, 4)], float) / 255.0


def _lin(c):
    return np.asarray(c, float) ** 2.2


def _mix(a, b, t):
    return np.asarray(a, float) + (np.asarray(b, float) - np.asarray(a, float)) * t


def palette(name):
    """Everything the renderer and the interface colour, for one palette: sky
    and studio radiances (linear), finish tints, the white point highlights
    converge on, sparkle inks and the interface roles (sRGB 0-1)."""
    if name == "aphex":
        return dict(
            zen=np.array([0.09, 0.17, 0.52]), mid=np.array([0.50, 0.64, 0.95]),
            hor=np.array([1.0, 0.95, 0.97]), cloud=np.array([0.96, 0.94, 1.0]),
            sea=np.array([0.20, 0.09, 0.13]), sea_hi=np.array([0.50, 0.24, 0.26]),
            haze=np.array([0.94, 0.86, 0.92]), glow=np.array([1.0, 0.97, 0.98]),
            sun=np.array([1.0, 0.96, 0.9]),
            st_base=np.array([0.92, 0.86, 1.10]), st_top=np.array([1.0, 1.0, 1.0]),
            st_left=np.array([1.0, 0.86, 0.70]), st_right=np.array([0.62, 0.78, 1.10]),
            st_front=np.array([0.95, 0.92, 1.00]), st_floor=np.array([0.9, 0.8, 1.1]),
            key=np.array([1.0, 0.94, 0.86]), fill=np.array([0.50, 0.62, 1.0]), rim=np.array([1.0, 0.90, 1.0]),
            amb_lo=np.array([0.012, 0.010, 0.018]), amb_hi=np.array([0.075, 0.07, 0.10]),
            chrome=np.array([0.96, 0.95, 1.0]), gloss=np.array([0.86, 0.10, 0.38]),
            satin=np.array([0.26, 0.10, 0.70]), obsidian=np.array([0.035, 0.03, 0.045]),
            gold=np.array([1.0, 0.78, 0.40]), bone=np.array([0.78, 0.74, 0.66]),
            white=np.array([1.0, 1.0, 1.0]),
            star=(np.array([1.0, 1.0, 1.0]), _hex("#e4e8ff"), _hex("#a9b2e6")),
            ground=None, night=_hex("#0e0d13"),
            ink=_hex("#dcdcf0"), muted=_hex("#8b8fa8"), faint=_hex("#55576a"), accent=_hex("#f07ab4"),
        )
    g, ink, acc, title, panel, caution, aur = PALETTE_ROLES[name]
    g, ink, acc, title, panel, caution = (_hex(c) for c in (g, ink, acc, title, panel, caution))
    aur = [_hex(c) for c in aur]
    L = _lin
    return dict(
        zen=L(_mix(g, title, 0.72)) * 1.15, mid=L(title) * 1.55,
        hor=L(_mix(ink, aur[0], 0.2)) * 1.05, cloud=L(_mix(ink, title, 0.3)) * 1.1,
        sea=L(_mix(g, acc, 0.62)) * 1.3, sea_hi=L(_mix(acc, caution, 0.3)) * 1.05,
        haze=L(_mix(ink, acc, 0.3)) * 1.05, glow=L(ink), sun=L(ink),
        st_base=L(_mix(g, title, 0.35)) * 4.0, st_top=L(ink) * 1.1,
        st_left=L(_mix(ink, caution, 0.35)), st_right=L(_mix(ink, title, 0.55)),
        st_front=L(ink), st_floor=L(_mix(g, acc, 0.4)) * 6.0,
        key=L(ink) * 1.15, fill=L(title) * 1.2, rim=L(_mix(ink, title, 0.3)) * 1.1,
        amb_lo=L(g) * 0.6, amb_hi=L(_mix(g, title, 0.35)) * 1.2,
        chrome=L(_mix(np.ones(3), ink, 0.3)), gloss=L(acc), satin=L(title),
        obsidian=L(g) * 1.2 + 0.01, gold=L(_mix(caution, ink, 0.45)), bone=L(ink),
        white=np.minimum(1.0, ink * 1.08),
        star=(np.minimum(1.0, ink * 1.08), _mix(ink, title, 0.35), _mix(ink, g, 0.55)),
        ground=g, night=g,
        ink=ink, muted=_mix(ink, g, 0.38), faint=_mix(ink, g, 0.64), accent=acc,
    )


MATERIAL_ORDER = list(MATERIALS)
GLYPH_MODES = ("shape", "ramp", "block")
RAMP = " .:-=+*#%@"
BAYER = (0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

DEFAULT_LIGHT = (-0.55, 0.62, 0.75)
FILL_LIGHT = (0.78, -0.22, 0.58)
RIM_LIGHT = (0.10, 0.62, -0.78)


def _unit(v):
    v = np.asarray(v, float)
    return v / max(1e-12, float(np.linalg.norm(v)))


def smoothstep(a, b, x):
    t = np.clip((x - a) / (b - a), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def band(v, a, b, s):
    return smoothstep(a - s, a + s, v) * (1.0 - smoothstep(b - s, b + s, v))


def rot_x(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[1.0, 0, 0], [0, c, -s], [0, s, c]])


def rot_y(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, 0, s], [0, 1.0, 0], [-s, 0, c]])


def orthonormal(R):
    x = R[0] / np.linalg.norm(R[0])
    y = R[1] - x * (x @ R[1])
    y /= np.linalg.norm(y)
    return np.array([x, y, np.cross(x, y)])


# --------------------------------------------------------------------------- #
# The model: a balloon.
#
# The outline is inflated like a membrane under pressure. phi solves the Poisson
# problem -lap(phi) = 1 inside the outline with phi = 0 on it (conjugate
# gradients, once, then cached), and the surface is z^2 = Q(phi) with
# Q ~ 2 s^2 phi. For a strip that is an exact circle and for a disc a sphere, so
# thin parts become round tubes and wide parts (the forehead) swell into a
# domed pillow, every corner and rim rounded the way a balloon rounds it.
# Near the outline phi is rebuilt from the exact distance to it, so the rim
# follows the traced curve instead of the solver's pixel grid.
# --------------------------------------------------------------------------- #
def polygon_sdf(p, poly):
    """Exact signed distance from points p (n,2) to a closed polygon, and its
    gradient (unit, pointing outward)."""
    A = poly
    E = np.roll(poly, -1, 0) - A
    EE = (E * E).sum(1)
    Ey = np.where(E[:, 1] == 0, 1e-12, E[:, 1])
    n = len(p)
    dist = np.empty(n)
    near = np.empty((n, 2))
    inside = np.empty(n, bool)
    for s in range(0, n, 1024):
        q = p[s:s + 1024]
        W = q[:, None, :] - A[None]
        t = np.clip((W * E[None]).sum(2) / EE[None], 0.0, 1.0)
        C = A[None] + t[..., None] * E[None]
        dd = ((q[:, None, :] - C) ** 2).sum(2)
        k = dd.argmin(1)
        r = np.arange(len(q))
        dist[s:s + 1024] = np.sqrt(dd[r, k])
        near[s:s + 1024] = C[r, k]
        ay, py = A[None, :, 1], q[:, None, 1]
        by = ay + E[None, :, 1]
        xi = A[None, :, 0] + (py - ay) * E[None, :, 0] / Ey[None]
        inside[s:s + 1024] = (((ay > py) != (by > py)) & (q[:, None, 0] < xi)).sum(1) % 2 == 1
    sign = np.where(inside, -1.0, 1.0)
    g = (p - near) / np.maximum(dist, 1e-9)[:, None] * sign[:, None]
    return dist * sign, g


def poisson(mask, h):
    """Solve -lap(phi) = 1 on mask, phi = 0 off it (matrix-free CG)."""
    def A(v):
        v = v * mask
        out = 4.0 * v
        out[1:, :] -= v[:-1, :]
        out[:-1, :] -= v[1:, :]
        out[:, 1:] -= v[:, :-1]
        out[:, :-1] -= v[:, 1:]
        return out * mask
    b = (h * h) * mask
    x = np.zeros_like(b)
    r = b.copy()
    p = r.copy()
    rs = float((r * r).sum())
    stop = 1e-10 * rs
    for _ in range(6000):
        Ap = A(p)
        a = rs / float((p * Ap).sum())
        x += a * p
        r -= a * Ap
        rn = float((r * r).sum())
        if rn < stop:
            break
        p = r + (rn / rs) * p
        rs = rn
    return x


def _blur(a, k):
    """Separable box blur, radius k cells, applied twice (~Gaussian)."""
    for _ in range(2):
        for ax in (0, 1):
            c = np.cumsum(np.pad(a, [(k + 1, k) if i == ax else (0, 0) for i in (0, 1)]), axis=ax)
            hi = np.take(c, np.arange(2 * k + 1, c.shape[ax]), axis=ax)
            lo = np.take(c, np.arange(0, c.shape[ax] - 2 * k - 1), axis=ax)
            a = (hi - lo) / (2 * k + 1)
    return a


def _slide_max(a, k, axis):
    """Max over a window of 2k+1 along an axis (sparse-table doubling)."""
    n = a.shape[axis]
    w = 2 * k + 1
    pad = [(k, k) if i == axis else (0, 0) for i in range(a.ndim)]
    m = np.pad(a, pad, constant_values=-np.inf)
    span = 1
    while span * 2 <= w:
        sh = np.take(m, np.arange(span, m.shape[axis]), axis=axis)
        m = np.maximum(np.take(m, np.arange(0, m.shape[axis] - span), axis=axis), sh)
        span *= 2
    a1 = np.take(m, np.arange(0, n), axis=axis)
    a2 = np.take(m, np.arange(w - span, w - span + n), axis=axis)
    return np.maximum(a1, a2)


def _slide_min(a, k, axis):
    return -_slide_max(-a, k, axis)


class Model:
    VERSION = 3

    def __init__(self, poly, puff=0.82, squash=0.30, res=6.0):
        P = np.asarray(poly, float)
        self.outline = P
        self.puff, self.squash = puff, squash
        self.res = res
        lo = P.min(0) - 3.0
        hi = P.max(0) + 3.0
        self.x0, self.y0 = float(lo[0]), float(lo[1])
        self.nx = int((hi[0] - lo[0]) * res) + 2
        self.ny = int((hi[1] - lo[1]) * res) + 2
        D, PHI = self._fields()
        self.D = D

        # height profile: Q = H^2, ~2 s^2 phi at the rim, gently squashed where
        # the balloon is wide so the forehead is a pillow, not a sphere
        s2, K = 2.0 * puff * puff, 6.0
        pos = np.maximum(PHI, 0.0)
        u = 1.0 + pos / K
        Q = np.where(PHI > 0, s2 * pos / u ** squash, s2 * PHI)
        dQ = np.where(PHI > 0, s2 * u ** (-squash - 1.0) * (1.0 + (1.0 - squash) * pos / K), s2)
        c = 1.0 / res
        py, px = np.gradient(PHI, c)
        self.F = np.stack([Q, dQ * px, dQ * py], -1).reshape(-1, 3).astype(np.float32)
        H = np.sqrt(np.maximum(Q, 0.0))
        self.hmax = float(H.max())
        ext = np.abs(P).max(0)
        self.half = np.array([ext[0] + 0.05, ext[1] + 0.05, self.hmax + 0.05])
        self.reach_x = float(np.sqrt(ext[0] ** 2 + self.hmax ** 2))
        self.reach_y = float(ext[1])
        self.radius = float(np.sqrt((P ** 2).sum(1).max() + self.hmax ** 2))
        self._bounds(H)
        self._pack()

    def _fields(self):
        key = hashlib.sha1(repr((np.round(self.outline, 4).tolist(), self.res, self.VERSION)).encode()).hexdigest()[:16]
        cache = _cache_dir()
        path = os.path.join(cache, "balloon-%s.npz" % key) if cache else None
        if path and os.path.exists(path):
            try:
                z = np.load(path)
                return z["D"].astype(np.float64), z["PHI"].astype(np.float64)
            except Exception:
                pass
        res = self.res
        xs = self.x0 + np.arange(self.nx) / res
        ys = self.y0 + np.arange(self.ny) / res
        X, Y = np.meshgrid(xs, ys)
        d, _ = polygon_sdf(np.stack([X.ravel(), Y.ravel()], 1), self.outline)
        D = d.reshape(self.ny, self.nx)
        c = 1.0 / res
        phi = poisson((D < 0).astype(float), c)
        # rim: phi ~ Gamma * (distance inside), Gamma read off a band just inside
        # and spread smoothly; blended into the solver's phi a few cells in
        u = -D
        band_ = (u > 1.5 * c) & (u < 4.5 * c)
        G = np.where(band_, phi / np.maximum(u, 1e-9), 0.0)
        wsum = _blur(band_.astype(float), 6)
        Gs = _blur(G, 6) / np.maximum(wsum, 1e-9)
        far = float(np.median(G[band_])) if band_.any() else 1.0
        Gs = np.where(wsum > 1e-3, Gs, far)
        w = np.clip((u - 1.5 * c) / (3.0 * c), 0.0, 1.0)
        w = w * w * (3 - 2 * w)
        PHI = (u * Gs) * (1 - w) + phi * w
        if path:
            try:
                np.savez_compressed(path, D=D.astype(np.float32), PHI=PHI.astype(np.float32))
            except OSError:
                pass
        return D, PHI

    def _bounds(self, H):
        """A coarse voxel grid of lower bounds on the distance to the balloon:
        at any point, the nearest solid point is either >= r away sideways or
        within r, where nothing is taller than the window max M_r, so
        dist >= max_r min(r, |z| - M_r). Sampled at voxel centres, minus the
        half diagonal, it is a bound everywhere."""
        res = self.res
        v = 1.0
        self.v = v
        self.vlo = np.array([self.x0, self.y0, -self.hmax - 1.0])
        nvx = int((self.nx / res) / v) + 1
        nvy = int((self.ny / res) / v) + 1
        nvz = int((2 * self.hmax + 2.0) / v) + 1
        cx = self.x0 + (np.arange(nvx) + 0.5) * v
        cy = self.y0 + (np.arange(nvy) + 0.5) * v
        cz = self.vlo[2] + (np.arange(nvz) + 0.5) * v
        ix = np.clip(((cx - self.x0) * res).round().astype(int), 0, self.nx - 1)
        iy = np.clip(((cy - self.y0) * res).round().astype(int), 0, self.ny - 1)
        best = np.full((nvz, nvy, nvx), -np.inf)
        az = np.abs(cz)[:, None, None]
        for r in (0.6, 1.2, 2.0, 3.0, 4.5, 6.5, 9.0, 13.0):
            k = int(math.ceil(r * res)) + 1
            M = _slide_max(_slide_max(H, k, 0), k, 1)[np.ix_(iy, ix)]
            best = np.maximum(best, np.minimum(r, az - M[None]))
        self.VB = (best - v * math.sqrt(3) / 2).ravel().astype(np.float32)
        self.vn = (nvx, nvy, nvz)

    def _pack(self):
        """Pre-pack each grid cell's bilinear coefficients (Q, Qx, Qy at the four
        corners as a + b*tx + c*ty + d*tx*ty): one gather per lookup, not four."""
        F = self.F.reshape(self.ny, self.nx, 3).astype(np.float64)
        a, b, c, d = F[:-1, :-1], F[:-1, 1:], F[1:, :-1], F[1:, 1:]
        C = np.stack([a, b - a, c - a, a - b - c + d], 2)            # (ny-1, nx-1, 4, 3)
        self.C = np.ascontiguousarray(C.transpose(0, 1, 3, 2)).reshape(-1, 3, 4).astype(np.float32)
        self.C12 = np.ascontiguousarray(self.C.reshape(-1, 12).T)   # 12 rows: one gather, contiguous maths
        self.cnx = self.nx - 1
        f = np.float32
        self._k = (f(self.x0), f(self.y0), f(self.res), f(self.nx - 1.001), f(self.ny - 1.001))
        nvx, nvy, nvz = self.vn
        VB = self.VB.reshape(nvz, nvy, nvx)
        self.VBp = np.pad(VB, 1, mode="edge").ravel()             # padded: no clipping needed
        self._vk = (f(self.vlo[0] - self.v), f(self.vlo[1] - self.v), f(self.vlo[2] - self.v),
                    f(1.0 / self.v), nvx + 2, nvy + 2, nvz + 2)

    def _field(self, x, y):
        x0, y0, res, mx, my = self._k
        fx = np.minimum(np.maximum((x - x0) * res, 0.0), mx)
        fy = np.minimum(np.maximum((y - y0) * res, 0.0), my)
        ixf = np.floor(fx)
        iyf = np.floor(fy)
        tx = fx - ixf
        ty = fy - iyf
        K = self.C12.take(iyf.astype(np.intp) * self.cnx + ixf.astype(np.intp), axis=1)   # (12, n)
        txy = tx * ty
        return (K[0] + K[1] * tx + K[2] * ty + K[3] * txy,       # Q, dQ/dx, dQ/dy
                K[4] + K[5] * tx + K[6] * ty + K[7] * txy,
                K[8] + K[9] * tx + K[10] * ty + K[11] * txy)

    def ge(self, x, y, z):
        """g = z^2 - Q (negative inside) and the distance estimate g / |grad g|."""
        Q, Qx, Qy = self._field(x, y)
        zz = z * z
        g = zz - Q
        return g, g / (np.sqrt(Qx * Qx + Qy * Qy + 4.0 * zz) + np.float32(1e-12))

    def eval(self, P):
        """g = z^2 - Q (negative inside), its gradient, and |gradient|."""
        Q, Qx, Qy = self._field(P[:, 0], P[:, 1])
        z = P[:, 2]
        g = z * z - Q
        gx, gy, gz = -Qx, -Qy, 2.0 * z
        gl = np.sqrt(gx * gx + gy * gy + gz * gz) + 1e-12
        return g, gx, gy, gz, gl

    def bound_xyz(self, x, y, z):
        vx, vy, vz, inv, nx, ny, nz = self._vk
        # inside the box the indices are in range; a ray that has just leapt past
        # the box may index out of it, and is dropped before its value matters
        ix = ((x - vx) * inv).astype(np.intp)
        iy = ((y - vy) * inv).astype(np.intp)
        iz = ((z - vz) * inv).astype(np.intp)
        return self.VBp.take((iz * ny + iy) * nx + ix, mode="clip")

    def bound(self, P):
        return self.bound_xyz(P[:, 0], P[:, 1], P[:, 2])

    def normal(self, P):
        _, gx, gy, gz, gl = self.eval(P)
        return np.stack([gx / gl, gy / gl, gz / gl], 1)


def _cache_dir():
    base = os.environ.get("XDG_CACHE_HOME") or os.path.join(os.path.expanduser("~"), ".cache")
    path = os.path.join(base, "aphex_spin")
    try:
        os.makedirs(path, exist_ok=True)
        return path
    except OSError:
        return None


# --------------------------------------------------------------------------- #
# Rendering: sphere tracing, then chrome or glossy plastic.
# --------------------------------------------------------------------------- #
def _hash(x, y):
    """asciify-engine's shader hash, vectorised."""
    px = np.mod(x * 123.34, 1.0)
    py = np.mod(y * 456.21, 1.0)
    d = px * (px + 45.32) + py * (py + 45.32)
    return np.mod((px + d) * (py + d), 1.0)


def _noise_texture(n=64):
    """Tileable value noise (two octaves of the hash, smoothly interpolated),
    baked once so the environment costs four lookups instead of hashing."""
    y, x = np.mgrid[0:n * 4, 0:n * 4] / 4.0
    out = np.zeros_like(x)
    for scale, amp in ((1.0, 0.65), (2.0, 0.35)):
        xs, ys = x * scale, y * scale
        ix, iy = np.floor(xs), np.floor(ys)
        fx, fy = xs - ix, ys - iy
        ux, uy = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy)
        p = n * scale
        h = lambda a, b: _hash(np.mod(a, p), np.mod(b, p))  # noqa: E731
        v = (h(ix, iy) * (1 - ux) + h(ix + 1, iy) * ux) * (1 - uy) + (h(ix, iy + 1) * (1 - ux) + h(ix + 1, iy + 1) * ux) * uy
        out += v * amp
    return out.astype(np.float32)


_NOISE = None


def vnoise(x, y):
    """Smooth noise in [0, 1], one unit per lattice cell, tiling every 64."""
    global _NOISE
    if _NOISE is None:
        _NOISE = _noise_texture()
    T = _NOISE
    n = T.shape[0]
    fx = np.mod(x * 4.0, n)
    fy = np.mod(y * 4.0, n)
    ix = fx.astype(np.intp)
    iy = fy.astype(np.intp)
    tx, ty = fx - ix, fy - iy
    ix1, iy1 = (ix + 1) % n, (iy + 1) % n
    a = T[iy, ix]
    b = T[iy, ix1]
    c = T[iy1, ix]
    d = T[iy1, ix1]
    return (a + (b - a) * tx) * (1 - ty) + (c + (d - c) * tx) * ty


def sky(r, P):
    """Above the clouds: a cold zenith with streaks of cloud, a glowing horizon
    haze, a sea of cloud below (the sun is added analytically, see sun_glow).
    Clouds and the sea are projected on planes above and below: no seams."""
    x, y, z = r[:, 0], r[:, 1], r[:, 2]
    up = np.clip(y, 0.0, 1.0)
    down = np.clip(-y, 0.0, 1.0)
    t1 = smoothstep(0.0, 0.32, up)[:, None]
    t2 = smoothstep(0.30, 0.95, up)[:, None]
    skyc = P["hor"] + (P["mid"] - P["hor"]) * t1
    skyc = skyc + (P["zen"] - skyc) * t2
    k = 1.0 / (up + 0.18)
    cl = vnoise(x * k * 1.3 + 11.0, z * k * 0.45) * 0.7 + vnoise(x * k * 3.1, z * k * 1.2 + 5.0) * 0.3
    cl = smoothstep(0.50, 0.82, cl) * (1.0 - smoothstep(0.45, 0.85, up))
    skyc = skyc + (P["cloud"] - skyc) * (cl * 0.8)[:, None]
    k = 1.0 / (down + 0.10)
    n = vnoise(x * k * 0.9 + 3.0, z * k * 0.9) * 0.65 + vnoise(x * k * 2.2, z * k * 2.2 + 9.0) * 0.35
    ground = P["sea"] + P["sea_hi"] * smoothstep(0.30, 0.78, n)[:, None]
    haze = np.exp(-down / 0.11)[:, None]
    ground = ground + (P["haze"] - ground) * haze
    out = np.where((y >= 0)[:, None], skyc, ground)
    return out + (np.exp(-(y / 0.05) ** 2) * 1.4)[:, None] * P["glow"]


def sun_glow(r, sun, P):
    cs = np.clip(r @ sun, 0.0, 1.0)
    c2 = cs * cs
    c4 = c2 * c2
    c60 = (c4 * c4 * c4) ** 5                       # cs ** 60, without pow
    return (c60 ** 15 * 60.0 + c60 * 0.9)[:, None] * P["sun"], cs


def studio(r, P):
    """A dim studio with three softboxes, seen in reflection."""
    x, y, z = r[:, 0], r[:, 1], r[:, 2]
    base = (0.035 + 0.11 * smoothstep(-0.35, 0.95, y))[:, None]
    top = (band(y, 0.60, 1.05, 0.07) * band(x, -0.58, 0.58, 0.07) * 3.4)[:, None]
    left = (band(x, -1.05, -0.60, 0.06) * band(y, -0.30, 0.62, 0.07) * smoothstep(-0.5, 0.0, z) * 2.4)[:, None]
    right = (band(x, 0.64, 1.05, 0.06) * band(y, -0.48, 0.40, 0.07) * 1.5)[:, None]
    front = (band(x, -0.80, -0.08, 0.16) * band(y, 0.02, 0.62, 0.16) * smoothstep(0.3, 0.8, z) * 0.9)[:, None]
    floor = (0.05 * smoothstep(-0.1, -0.9, y))[:, None]
    return (base * P["st_base"] + top * P["st_top"] + left * P["st_left"] + right * P["st_right"]
            + front * P["st_front"] + floor * P["st_floor"])


ENVIRONMENTS = {"sky": sky, "studio": studio}


class EnvMap:
    """An environment baked into an octahedral map (one bilinear lookup per ray
    instead of evaluating clouds and softboxes), per palette and kind."""
    N = 256
    _cache = {}

    def __init__(self, tex):
        self.T = tex

    @classmethod
    def get(cls, kind, pal_name, P):
        key = (kind, pal_name)
        em = cls._cache.get(key)
        if em is None:
            n = cls.N
            u = (np.arange(n) + 0.5) / n * 2.0 - 1.0
            U, V = np.meshgrid(u, u)
            Z = 1.0 - np.abs(U) - np.abs(V)
            X = np.where(Z < 0, (1.0 - np.abs(V)) * np.sign(U), U)
            Y = np.where(Z < 0, (1.0 - np.abs(U)) * np.sign(V), V)
            d = np.stack([X.ravel(), Y.ravel(), Z.ravel()], 1)
            d /= np.linalg.norm(d, axis=1)[:, None]
            tex = ENVIRONMENTS[kind](d, P).reshape(n, n, 3).astype(np.float32)
            em = cls._cache[key] = EnvMap(np.pad(tex, ((0, 1), (0, 1), (0, 0)), mode="edge"))
        return em

    @staticmethod
    def mixed(a, b, w):
        return a if w <= 0 else b if w >= 1 else EnvMap(a.T + (b.T - a.T) * np.float32(w))

    def lookup(self, r):
        n = self.N
        s = np.abs(r).sum(1) + 1e-12
        px = r[:, 0] / s
        py = r[:, 1] / s
        neg = r[:, 2] < 0
        qx = np.where(neg, (1.0 - np.abs(py)) * np.sign(px), px)
        qy = np.where(neg, (1.0 - np.abs(px)) * np.sign(py), py)
        fx = np.clip((qx * 0.5 + 0.5) * n - 0.5, 0.0, n - 1.0)
        fy = np.clip((qy * 0.5 + 0.5) * n - 0.5, 0.0, n - 1.0)
        ix = fx.astype(np.intp)
        iy = fy.astype(np.intp)
        tx = (fx - ix)[:, None].astype(np.float32)
        ty = (fy - iy)[:, None].astype(np.float32)
        T = self.T
        a = T[iy, ix]
        top = a + (T[iy, ix + 1] - a) * tx
        c = T[iy + 1, ix]
        return top + (c + (T[iy + 1, ix + 1] - c) * tx - top) * ty


def aces(x):
    return np.clip((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0)


def coherent_starts(radius, Rp, R, depth, rows, cols, cell):
    """Where each ray may safely start this frame: just in front of the nearest
    depth its neighbourhood hit last frame, allowing for how far any surface
    point can have moved since (rotation angle x radius). None when unsafe."""
    if Rp is None or depth is None:
        return None
    c = max(-1.0, min(1.0, (float(np.trace(R @ Rp.T)) - 1.0) / 2.0))
    move = math.acos(c) * radius
    reach = int(math.ceil(move / cell)) + 1
    if reach > 4:
        return None
    T = np.where(np.isfinite(depth), depth, np.inf).reshape(rows, cols, 6).min(2)
    T = _slide_min(_slide_min(T, reach, 0), reach, 1)
    start = T - np.float32(2.0 * move + 0.25 + 0.6 * cell)
    return np.repeat(start.ravel(), 6).astype(np.float32)


def sample_grid(model, cols, rows, zoom):
    """View-space sample points of a terminal (six per cell), the footprint of
    one sample and the width of one cell, in model units."""
    S = zoom * 0.94 * min(cols / (2.0 * model.reach_x), 2.0 * rows / (2.0 * (model.reach_y + 1.2)))
    sp = np.array(SAMPLES)
    xs = (np.arange(cols)[None, :, None] + sp[None, None, :, 0]) - cols / 2.0
    ys = rows - (2.0 * np.arange(rows)[:, None, None] + sp[None, None, :, 1])
    xs = (np.broadcast_to(xs, (rows, cols, 6)).ravel() / S).astype(np.float32)
    ys = (np.broadcast_to(ys, (rows, cols, 6)).ravel() / S).astype(np.float32)
    return xs, ys, SAMPLE_R * 1.15 / S, 1.0 / S


class Renderer:
    MAX_STEPS = 40
    OMEGA = 1.5          # over-relaxed steps; an overshoot is pulled back by a secant

    def __init__(self, model):
        self.m = model

    def trace(self, xv, yv, R, light, material, footprint, start=None, pal=None, also=(None, 1.0)):
        """Rays through view-space points (xv, yv) looking down -z, against the
        model turned by R (object -> view). Returns sRGB colour (n,3), its
        luminance, the sun's glint (for sparkles), coverage and hit depth (NaN
        where a ray missed); `start` gives coherent start depths per ray."""
        m = self.m
        n = len(xv)
        f32 = np.float32
        Z0 = f32(m.radius + 5.0)
        R32 = R.astype(f32)
        ox = xv * R32[0, 0] + yv * R32[1, 0] + Z0 * R32[2, 0]
        oy = xv * R32[0, 1] + yv * R32[1, 1] + Z0 * R32[2, 1]
        oz = xv * R32[0, 2] + yv * R32[1, 2] + Z0 * R32[2, 2]
        dx, dy, dz = (-R32[2, 0], -R32[2, 1], -R32[2, 2])
        tmin = np.zeros(n, f32)
        tmax = np.full(n, np.inf, f32)
        for o_, d_, h in ((ox, dx, m.half[0]), (oy, dy, m.half[1]), (oz, dz, m.half[2])):
            dd = d_ if abs(d_) > 1e-9 else f32(1e-9)
            a = (f32(-h) - o_) / dd
            b = (f32(h) - o_) / dd
            np.maximum(tmin, np.minimum(a, b), out=tmin)
            np.minimum(tmax, np.maximum(a, b), out=tmax)

        hit_t = np.full(n, np.nan, f32)
        cov = np.zeros(n, f32)
        idx = np.nonzero(tmax > tmin)[0]
        t = tmin[idx]
        warm = np.zeros(len(idx), bool)
        if start is not None:
            s0 = start[idx]
            warm = np.isfinite(s0) & (s0 > t)
            t = np.where(warm, s0, t)
        # phase 1: rays with no coherent start leap through empty space on the
        # rigorous voxel bound alone (one lookup a step)
        cold = np.nonzero(~warm)[0]
        if len(cold):
            tc = t[cold]
            oxc, oyc, ozc = ox[idx[cold]], oy[idx[cold]], oz[idx[cold]]
            for _ in range(10):
                b = m.bound_xyz(oxc + tc * dx, oyc + tc * dy, ozc + tc * dz)
                far = b > 0.4
                if not far.any():
                    break
                tc = np.where(far, tc + b, tc)
            t[cold] = tc
            inbox = t < tmax[idx]
            if not inbox.all():
                idx, t, warm = idx[inbox], t[inbox], warm[inbox]
        eps = f32(max(0.004, 0.12 * footprint))       # cone tracing: nothing finer than
        min_step = f32(max(0.004, 0.30 * footprint))  # a sample's footprint can be seen
        # state, packed so compaction is one gather: ox oy oz t tmax tp gp best tb tmin
        S = np.empty((10, len(idx)), f32)
        S[0], S[1], S[2] = ox[idx], oy[idx], oz[idx]
        S[3], S[4], S[5] = t, tmax[idx], t
        S[6] = np.inf
        S[7] = np.inf
        S[8] = t
        S[9] = tmin[idx]
        miss = []
        total = max(1, len(idx))
        om = f32(self.OMEGA)
        for step_no in range(self.MAX_STEPS):
            if S.shape[1] == 0 or (step_no > 9 and S.shape[1] < total * 0.02):
                break     # the rest graze silhouettes: they end as near misses below
            t = S[3]
            x = S[0] + t * dx
            y = S[1] + t * dy
            z = S[2] + t * dz
            g, e = m.ge(x, y, z)
            inside = g < 0
            first = ~np.isfinite(S[6])
            # a coherent start that landed inside the solid: the surface moved in
            # front of it, so that ray starts over from the box with a full march
            redo = inside & first & warm
            if redo.any():
                S[3] = np.where(redo, S[9], t)
                warm = warm & ~redo
                inside = inside & ~redo
                t = S[3]
            done = inside | ((e < eps) & ~redo)
            if done.any():
                th = t.copy()
                cross = inside & ~first
                if cross.any():
                    gp, gc, tp = S[6][cross], g[cross], S[5][cross]
                    th[cross] = tp + (t[cross] - tp) * gp / (gp - gc)
                j = idx[done]
                hit_t[j] = th[done]
                cov[j] = 1.0
            closer = (e < S[7]) & ~redo
            S[7] = np.where(closer, e, S[7])
            S[8] = np.where(closer, t, S[8])
            step = np.maximum(om * e, min_step)
            far = np.nonzero(e > 0.4)[0]           # only these can take a rigorous leap
            if len(far):
                b = m.bound_xyz(x[far], y[far], z[far])
                step[far] = np.where(b > 0.4, b, step[far])
            if redo.any():
                S[5] = np.where(redo, S[5], t)
                S[6] = np.where(redo, np.inf, g)
                S[3] = np.where(redo, t, t + step)
            else:
                S[5] = t
                S[6] = g
                S[3] = t + step
            gone = ~done & (S[3] >= S[4])
            if gone.any():
                miss.append((idx[gone], S[7][gone], S[8][gone]))
            keep = ~done & ~gone
            if not keep.all():
                S = S[:, keep]
                idx = idx[keep]
                warm = warm[keep]
        if S.shape[1]:
            miss.append((idx, S[7], S[8]))
        if miss:   # near misses: shade where the ray passed closest, by coverage
            mi = np.concatenate([a for a, _, _ in miss])
            md = np.concatenate([b for _, b, _ in miss])
            mt = np.concatenate([c for _, _, c in miss])
            near = md < footprint
            mi, md, mt = mi[near], md[near], mt[near]
            c = np.clip(1.0 - md / f32(footprint), 0.0, 1.0)
            hit_t[mi] = mt
            cov[mi] = c * c * (3.0 - 2.0 * c)
        depth = np.where(cov >= 1.0, hit_t, np.nan).astype(f32)

        shaded = np.nonzero(cov > 0)[0]
        rgb = np.zeros((n, 3), f32)
        glint = np.zeros(n, f32)
        if len(shaded):
            th = hit_t[shaded]
            P = np.stack([ox[shaded] + th * dx, oy[shaded] + th * dy, oz[shaded] + th * dz], 1)
            N = m.normal(P)
            Nv, Pv = N @ R32.T, P @ R32.T
            col, gl = self.shade(Nv, Pv, light, material, pal)
            if also[0] is not None and also[0] != material:    # a finish fading in
                c0, g0 = self.shade(Nv, Pv, light, also[0], pal)
                wf = np.float32(also[1])
                col = c0 + (col - c0) * wf
                gl = g0 + (gl - g0) * wf
            rgb[shaded] = col * cov[shaded, None]
            glint[shaded] = gl * cov[shaded]
        lum = rgb @ np.array([0.2126, 0.7152, 0.0722], f32)
        return rgb, lum, glint, cov, depth

    def shade(self, N, Pv, light, material, pal=None):
        """Shading in view space. The rays are orthographic, but the camera and
        the key light sit at finite distances for shading, so broad faces get
        gradients and highlights travel across them. `pal` is (name, palette,
        environment maps); highlights converge on the palette's white point."""
        if pal is None:
            pal = resolve_palette("aphex")
        name, P, envs = pal
        mat = MATERIALS[material]
        tint = P[mat["tint"]]
        rad = self.m.radius
        V = np.array([0.0, 0.0, 2.4 * rad], np.float32) - Pv
        V /= np.linalg.norm(V, axis=1)[:, None]
        ndv = np.clip((N * V).sum(1), 0.0, 1.0)
        sun = _unit(light)
        r = 2.0 * ndv[:, None] * N - V
        env = envs[mat["env"]].lookup(r)
        if mat["env"] == "sky":
            sg, cs = sun_glow(r, sun, P)
            env = env + sg
        else:
            cs = np.clip(r @ sun, 0.0, 1.0)
        c2 = cs * cs
        c4 = c2 * c2
        c16 = c4 * c4
        glint = c16 * c16 * c16 * c16 * c16              # cs ** 80, a wide lobe to find glints
        fr = (1.0 - ndv) ** 5
        if mat["metal"]:
            F = tint[None] + (1.0 - tint[None]) * fr[:, None]
            col = F * env * mat["env_k"] + 0.02 * tint[None]
        else:
            kp = np.array([light[0] * 1.7 * rad, light[1] * 1.7 * rad, max(0.35, light[2]) * 1.5 * rad])
            K = kp[None] - Pv
            K /= np.linalg.norm(K, axis=1)[:, None]
            lights = ((K, P["key"] * 1.65),
                      (np.broadcast_to(_unit(FILL_LIGHT), N.shape), P["fill"] * 0.30),
                      (np.broadcast_to(_unit(RIM_LIGHT), N.shape), P["rim"] * 1.10))
            hemi = (0.5 + 0.5 * N[:, 1])[:, None]
            diff = P["amb_lo"][None] * (1 - hemi) + P["amb_hi"][None] * hemi
            spec = np.zeros_like(diff)
            shine = mat["shine"]
            norm = (shine + 8.0) / (8.0 * math.pi)
            for i, (L, c) in enumerate(lights):
                ndl = np.clip((N * L).sum(1), 0.0, None)
                diff = diff + ndl[:, None] * c[None]
                Hh = L + V
                Hh = Hh / np.maximum(np.linalg.norm(Hh, axis=1), 1e-9)[:, None]
                nh = np.clip((N * Hh).sum(1), 0.0, None)
                spec = spec + ((nh ** shine) * norm * (ndl > 0))[:, None] * c[None]
                if i == 0:
                    glint = nh ** (shine * 4)
            F = (mat["f0"] + (1.0 - mat["f0"]) * fr)[:, None]
            col = tint[None] * diff * (1.0 - F) + F * env * mat["env_k"] + spec * mat["spec"] * 0.10
        out = aces(col) ** (1 / 2.2)
        white = P["white"]
        if white.min() < 0.999:      # highlights converge on the palette's ink, never white
            lum = out @ np.array([0.2126, 0.7152, 0.0722])
            h = smoothstep(0.55, 1.0, lum)[:, None]
            out = out + (np.minimum(out, white) - out) * h
        return out.astype(np.float32), glint.astype(np.float32)


_RESOLVED = {}


def resolve_palette(name):
    """(name, palette, {environment kind: baked map}) for one base palette."""
    hit = _RESOLVED.get(name)
    if hit is None:
        P = palette(name)
        hit = _RESOLVED[name] = (name, P, {k: EnvMap.get(k, name, P) for k in ENVIRONMENTS})
    return hit


def mix_palettes(mix):
    """A palette as a weighted mix of base palettes, (name, weight) pairs: a
    cross-fade, even one started half way through another. Everything is
    linear in the palette's colours, the baked environments included."""
    mix = tuple((n, float(w)) for n, w in mix if w > 1e-4)
    if len(mix) == 1:
        return resolve_palette(mix[0][0])
    parts = [resolve_palette(n) for n, _ in mix]
    ws = [w for _, w in mix]
    P = {}
    for k in parts[0][1]:
        vals = [q[1][k] for q in parts]
        if k == "ground":
            if sum(w for w, v in zip(ws, vals) if v is not None) < 0.5:
                P[k] = None
                continue
            vals = [v if v is not None else q[1]["night"] for v, q in zip(vals, parts)]
        if isinstance(vals[0], tuple):
            P[k] = tuple(sum(w * v[i] for w, v in zip(ws, vals)) for i in range(len(vals[0])))
        else:
            P[k] = sum(w * v for w, v in zip(ws, vals))
    envs = {kind: EnvMap(sum(np.float32(w) * q[2][kind].T for w, q in zip(ws, parts))) for kind in ENVIRONMENTS}
    return (mix[-1][0], P, envs)


# --------------------------------------------------------------------------- #
# ASCII: one frame of the terminal from the samples.
# --------------------------------------------------------------------------- #
CHARSETS = {
    "symbols": " .,:;'`\"-_~^!|/\\()[]{}<>+=*#%@&$?",   # classic ASCII-art texture
    "all": GLYPH_CHARS,                                    # every printable glyph
}


class Glyphs:
    def __init__(self, charset="symbols"):
        V = np.array([float(v) for v in GLYPH_VEC.split(",")]).reshape(-1, 6)
        keep = np.array([c in CHARSETS[charset] for c in GLYPH_CHARS])
        self.chars = np.array(list(GLYPH_CHARS))[keep]
        self.V = V[keep].astype(np.float32)
        self.VT = np.ascontiguousarray(self.V.T)
        self.VV = (self.V * self.V).sum(1).astype(np.float32)

    def match(self, S):
        """Nearest glyph by shape: argmin |S - V|^2 = argmin (|V|^2 - 2 S.V)."""
        d = S @ self.VT
        d *= np.float32(-2.0)
        d += self.VV
        return d.argmin(1)


_POW = {}


def _pow_lut(e):
    """x ** e on [0, 1] as a 2049-entry table (contrast enhancement runs on
    every sample of every frame; a gather is far cheaper than pow)."""
    lut = _POW.get(e)
    if lut is None:
        lut = _POW[e] = (np.linspace(0.0, 1.0, 2049) ** e).astype(np.float32)
    return lut


def _pw(ratio, e):
    return _pow_lut(round(e, 3)).take((ratio * np.float32(2048.0)).astype(np.intp), mode="clip")


def enhance(S, e_dir, e_glob):
    """Harri's contrast enhancement. S is (6, rows, cols): each sampling circle
    is pushed down where a neighbouring cell's adjacent circle is brighter
    (directional), then each cell's circles are crunched against its own
    brightest (global)."""
    P = np.pad(S, ((0, 0), (1, 1), (1, 1)))
    up, down = P[:, :-2, 1:-1], P[:, 2:, 1:-1]
    left, right = P[:, 1:-1, :-2], P[:, 1:-1, 2:]
    ext = (up[4], up[5], left[1], right[0], left[3], right[2], left[5], right[4], down[0], down[1])
    out = np.empty_like(S)
    eps = np.float32(1e-6)
    for i, aff in enumerate(AFFECT):
        mx = S[i]
        for k in aff:
            mx = np.maximum(mx, ext[k])
        out[i] = _pw(S[i] / np.maximum(mx, eps), e_dir) * mx
    mx = np.maximum(out.max(0), eps)
    return _pw(out / mx, e_glob) * mx


def pack(rgb):
    c = np.clip(rgb * 255.0 + 0.5, 0, 255).astype(np.int64) & 0xFC
    return (c[..., 0] << 16) | (c[..., 1] << 8) | c[..., 2]


class Frame:
    """Characters and colours for a terminal of `cols` x `rows` cells, and the
    ground painted behind them (None: the terminal's own background)."""

    def __init__(self, chars, fg, bg=None, ground=None):
        self.chars, self.fg, self.bg, self.ground = chars, fg, bg, ground


STAR_INK = (0xFFFFFF, 0xE4E8FF, 0xA9B2E6)
# four-point stars, small to large: (row, col, glyph, ink). A cell is twice as
# tall as it is wide, so the horizontal arms are twice as long.
STARS = (
    ((0, 0, "*", 0), (0, -1, "-", 1), (0, 1, "-", 1)),
    ((0, 0, "*", 0), (0, -1, "-", 0), (0, 1, "-", 0), (0, -2, "-", 2), (0, 2, "-", 2),
     (-1, 0, "|", 1), (1, 0, "|", 1)),
    ((0, 0, "*", 0), (0, -1, "-", 0), (0, 1, "-", 0), (0, -2, "-", 1), (0, 2, "-", 1),
     (0, -3, "-", 2), (0, 3, "-", 2), (0, -4, "-", 2), (0, 4, "-", 2),
     (-1, 0, "|", 0), (1, 0, "|", 0), (-2, 0, "'", 2), (2, 0, ".", 2)),
)


def sparkles(chars, fg, glint, rows, cols, clock, limit=5, inks=None):
    """Four-point stars on the brightest glints of the sun, twinkling."""
    G = glint.reshape(rows, cols, 6).max(2)
    if G.max() < 0.45:
        return
    peak = (G >= _slide_max(_slide_max(G, 2, 0), 4, 1)) & (G > 0.45)
    ys, xs = np.nonzero(peak)
    order = np.argsort(-G[ys, xs])[:limit]
    for y, x in zip(ys[order].tolist(), xs[order].tolist()):
        phase = float(_hash(np.array(x * 0.731 + 0.5), np.array(y * 0.377 + 0.5))) * 6.283
        size = (0.5 + 0.5 * math.sin(clock * 4.5 + phase)) * min(1.0, 0.4 + float(G[y, x]))
        for dy, dx, c, ink in STARS[min(2, int(size * 3.0))]:
            yy, xx = y + dy, x + dx
            if 0 <= yy < rows and 0 <= xx < cols:
                chars[yy, xx] = c
                fg[yy, xx] = (inks or STAR_INK)[ink]


def make_frame(glyphs, rgb, lum, rows, cols, mode, contrast, glint=None, cov=None,
               backdrop=None, stars=False, clock=0.0, exposure=1.0, inks=None, ground=None):
    f32 = np.float32
    gp = None if ground is None else _ink(ground)
    rgb = rgb.reshape(rows, cols, 6, 3)
    if backdrop is not None and cov is not None:
        c6 = cov.reshape(rows, cols, 6)
        near = _slide_max(_slide_max(c6.max(2), 1, 0), 2, 1)          # a clear zone around the logo
        fade = (f32(1.0) - c6) * (f32(1.0) - f32(0.85) * near)[..., None]
        rgb = rgb + backdrop.reshape(rows, cols, 6, 3) * fade[..., None]
        lum = (rgb @ np.array([0.2126, 0.7152, 0.0722], f32)).reshape(-1)
    if mode == "block":
        if ground is not None and cov is not None:      # composite over the ground
            rgb = rgb + np.asarray(ground, f32) * (f32(1.0) - cov.reshape(rows, cols, 6, 1))
        top = (rgb[:, :, 0] + rgb[:, :, 1] + 0.5 * (rgb[:, :, 2] + rgb[:, :, 3])) / 3.0
        bot = (rgb[:, :, 4] + rgb[:, :, 5] + 0.5 * (rgb[:, :, 2] + rgb[:, :, 3])) / 3.0
        chars = np.full((rows, cols), "\u2580")
        return Frame(chars, pack(np.clip(top * exposure, 0, 1)), pack(np.clip(bot * exposure, 0, 1)), gp)

    L = np.ascontiguousarray(lum.reshape(rows * cols, 6).T).reshape(6, rows, cols)   # six planes
    C = np.ascontiguousarray(rgb.reshape(rows * cols, 6, 3).transpose(1, 0, 2)).reshape(6, rows, cols, 3)
    wsum = L[0] + L[1] + L[2] + L[3] + L[4] + L[5]
    ink = (C[0] * L[0][..., None] + C[1] * L[1][..., None] + C[2] * L[2][..., None]
           + C[3] * L[3][..., None] + C[4] * L[4][..., None] + C[5] * L[5][..., None])
    ink /= np.maximum(wsum, f32(1e-6))[..., None]
    peak = np.maximum(np.maximum(np.maximum(L[0], L[1]), np.maximum(L[2], L[3])), np.maximum(L[4], L[5]))
    if mode == "ramp":
        mean = wsum * f32(exposure / 6.0)
        y = np.arange(rows)[:, None]
        x = np.arange(cols)[None, :]
        th = (np.array(BAYER)[(y % 4) * 4 + (x % 4)] + 0.5) / 16.0 - 0.5
        k = np.clip(np.floor(mean ** 0.85 * (len(RAMP) - 1) + 0.5 + th * 0.9), 0, len(RAMP) - 1)
        chars = np.array(list(RAMP))[k.astype(np.intp)]
    else:
        S = np.minimum(L * f32(exposure * 0.85), f32(1.0))
        S = enhance(S, contrast, 1.0 + 0.45 * contrast)
        chars = glyphs.chars[glyphs.match(S.reshape(6, -1).T)].reshape(rows, cols)
    # ink colour: the hue of the lit samples; brightness compressed (the glyph
    # already carries part of it) but kept, so the shading shows in colour too
    il = ink @ np.array([0.2126, 0.7152, 0.0722], f32)
    target = _pw(np.maximum(il, peak * f32(0.8)), 0.6) * f32(1.25)
    np.minimum(target, f32(1.0), out=target)
    fg = ink * (target / np.maximum(il, f32(1e-6)))[..., None]
    fg = fg / np.maximum(fg.max(2, keepdims=True), f32(1.0))
    fgp = pack(fg)
    if stars and glint is not None:
        chars = chars.copy()
        sparkles(chars, fgp, glint, rows, cols, clock, inks=inks)
    fgp[chars == " "] = -1
    return Frame(chars, fgp, None, gp)


# --------------------------------------------------------------------------- #
# Writing frames: synchronized output, only the rows that changed.
# --------------------------------------------------------------------------- #
def _truecolor_default():
    ct = os.environ.get("COLORTERM", "").lower()
    term = os.environ.get("TERM", "").lower()
    return ct in ("truecolor", "24bit") or any(k in term for k in ("direct", "kitty", "alacritty", "wezterm", "foot", "ghostty"))


_CUBE = (0, 95, 135, 175, 215, 255)


def _to256(r, g, b):
    def lv(v):
        return 0 if v < 48 else 1 if v < 115 else (v - 35) // 40
    ri, gi, bi = lv(r), lv(g), lv(b)
    cr, cg, cb = _CUBE[ri], _CUBE[gi], _CUBE[bi]
    grey = max(0, min(23, round(((r + g + b) / 3 - 8) / 10)))
    gv = 8 + 10 * grey
    if (r - cr) ** 2 + (g - cg) ** 2 + (b - cb) ** 2 <= (r - gv) ** 2 + (g - gv) ** 2 + (b - gv) ** 2:
        return 16 + 36 * ri + 6 * gi + bi
    return 232 + grey


class Writer:
    def __init__(self, truecolor):
        self.truecolor = truecolor
        self.prev = None
        self._status = None
        self._fg, self._bg = {}, {}

    def sgr(self, c, bg=False):
        cache = self._bg if bg else self._fg
        s = cache.get(c)
        if s is None:
            r, g, b = (c >> 16) & 255, (c >> 8) & 255, c & 255
            if self.truecolor:
                s = "\x1b[%d;2;%d;%d;%dm" % (48 if bg else 38, r, g, b)
            else:
                s = "\x1b[%d;5;%dm" % (48 if bg else 38, _to256(r, g, b))
            cache[c] = s
        return s

    def compose(self, fr, status=None):
        """One frame: synchronized output, only the rows that changed. The
        palette's ground (when solid) is painted behind every cell, so a change
        of ground repaints everything."""
        rows, cols = fr.chars.shape
        prev = self.prev
        base = "\x1b[0m" if fr.ground is None else "\x1b[0m" + self.sgr(fr.ground, True)
        full = (prev is None or prev.chars.shape != fr.chars.shape or (prev.bg is None) != (fr.bg is None)
                or prev.ground != fr.ground)
        out = ["\x1b[?2026h"]
        if full:
            out.append(base + "\x1b[2J")
        for y in range(rows):
            ch, fg = fr.chars[y], fr.fg[y]
            bg = fr.bg[y] if fr.bg is not None else None
            if not full and np.array_equal(ch, prev.chars[y]) and np.array_equal(fg, prev.fg[y]) and \
                    (bg is None or np.array_equal(bg, prev.bg[y])):
                continue
            out.append("\x1b[%d;1H%s" % (y + 1, base))
            chl, fgl = ch.tolist(), fg.tolist()
            bgl = bg.tolist() if bg is not None else None
            cf = cb = None
            buf = []
            for x in range(cols):
                c = chl[x]
                f = fgl[x]
                if bgl is not None:
                    b = bgl[x]
                    if b != cb:
                        buf.append(self.sgr(b, True))
                        cb = b
                if f >= 0 and f != cf:
                    buf.append(self.sgr(f))
                    cf = f
                buf.append(c)
            out.append("".join(buf))
        if status is not None and (full or status != self._status):
            out.append("\x1b[%d;1H%s%s%s\x1b[K" % (rows + 1, base, status, base))
            self._status = status
        out.append("\x1b[0m\x1b[?2026l")
        self.prev = fr
        return "".join(out)


def _fit(hint, width):
    """The hints that fit in `width`, cut between two of them, never mid-word."""
    if len(hint) <= width:
        return hint
    cut = hint[:max(0, width)].rfind(" · ")
    return hint[:cut] if cut > 0 else ""


class Hud:
    """The quiet bottom line, after the Design System: key hints in muted that
    fade after 12 s of stillness, and short lowercase notes in a fixed place
    that leave on their own after 2 s (the value in the lamp colour)."""
    IDLE, FADE, NOTE = 12.0, 2.5, 2.0
    HINT = ("drag spins · hover moves the sun · wheel zooms · t palette · m finish · g glyphs · "
            "c letters · b sky · s stars · o ground · space pauses · q quits")

    def __init__(self, now):
        self.active = now
        self.note = None
        self.note_at = -10.0
        self.fps = False
        self.shown = True

    def touch(self, now):
        self.active = now

    def say(self, now, label, value=""):
        self.note = (label, value)
        self.note_at = now
        self.active = now

    def line(self, now, cols, P, writer, fps):
        dark = P["ground"] if P["ground"] is not None else P["night"]
        fade = min(1.0, max(0.0, (now - self.active - self.IDLE) / self.FADE)) if self.shown else 1.0
        parts = []
        used = 0
        if fade < 1.0:
            hint = " " + self.HINT
            if self.fps:
                hint = " %2.0f fps · " % fps + self.HINT
            hint = _fit(hint, cols - 2)
            parts.append(writer.sgr(_ink(_mix(P["muted"], dark, fade))))
            parts.append(hint)
            used = len(hint)
        age = now - self.note_at
        if self.note is not None and age < self.NOTE + 0.5:
            nf = min(1.0, max(0.0, (age - self.NOTE) / 0.5))
            label, value = self.note
            text = label + (" · " if value else "")
            width = len(text) + len(value) + 1
            if used + width + 2 > cols:          # the note keeps its place; the hints make room
                room = max(0, cols - width - 4)
                if parts:
                    parts[-1] = _fit(parts[-1], room)
                used = min(used, len(parts[-1]) if parts else 0)
            parts.append(" " * max(1, cols - 1 - used - width))
            parts.append(writer.sgr(_ink(_mix(P["ink"], dark, nf))) + text)
            parts.append(writer.sgr(_ink(_mix(P["accent"], dark, nf))) + value)
        elif self.fps and fade >= 1.0:
            parts.append(writer.sgr(_ink(P["faint"])) + (" %2.0f fps" % fps).rjust(cols - 1))
        return "".join(parts)


# --------------------------------------------------------------------------- #
# The view: rotation, light, zoom, and the sample grid for a terminal size.
# --------------------------------------------------------------------------- #
FADE_PALETTE = 0.9      # a palette change melts everything into the next, all at once
FADE_FINISH = 0.6


def _ease(x):
    x = min(1.0, max(0.0, x))
    return x * x * (3.0 - 2.0 * x)


class View:
    """What is on screen: orientation, light, zoom, palette and finish (with
    their cross-fades), glyph mode, ground."""

    def __init__(self, model, args):
        self.model = model
        self.material = MATERIAL_ALIASES.get(args.material, args.material)
        self.palette_name = getattr(args, "palette", None) or "aphex"
        self.mode = args.glyphs
        self.contrast = args.contrast
        self.backdrop = getattr(args, "backdrop", False)
        self.charset = getattr(args, "charset", "all")
        self.stars = not getattr(args, "no_sparkles", False)
        self.solid = getattr(args, "solid", None)     # None: the palette decides
        self.zoom = 1.0
        self.speed = args.speed
        self.clock = 0.0
        self._mix_from = None
        self._mix_t0 = -10.0
        self._mat_from = None
        self._mat_t0 = -10.0
        self._grid = None
        self._sky = {}
        self._pal_cache = None
        self.reset()

    def reset(self):
        self.R = rot_x(0.16) @ rot_y(-0.42)
        self.light = _unit(DEFAULT_LIGHT)
        self.light_target = self.light.copy()
        self.spin = np.zeros(2)            # inertia, rad/s about view x and y

    # ---- palette and finish, with their cross-fades
    def mix(self):
        """The palette now, as (name, weight) pairs over base palettes."""
        if self._mix_from is None:
            return ((self.palette_name, 1.0),)
        w = _ease((self.clock - self._mix_t0) / FADE_PALETTE)
        if w >= 1.0:
            self._mix_from = None
            return ((self.palette_name, 1.0),)
        d = {}
        for n, v in self._mix_from:
            d[n] = d.get(n, 0.0) + v * (1.0 - w)
        d.pop(self.palette_name, None)
        cur = sum(v for v in d.values())
        out = tuple(d.items()) + ((self.palette_name, 1.0 - cur),)
        return out

    def set_palette(self, name):
        if name != self.palette_name:
            self._mix_from = self.mix()
            self._mix_t0 = self.clock
            self.palette_name = name

    def pal(self):
        mix = self.mix()
        if self._pal_cache is None or self._pal_cache[0] != mix:
            self._pal_cache = (mix, mix_palettes(mix))
        return self._pal_cache[1]

    def set_material(self, name):
        if name != self.material:
            self._mat_from = self.material
            self._mat_t0 = self.clock
            self.material = name

    def finish_mix(self):
        """(previous finish, weight of the current one) while a finish fades in."""
        if self._mat_from is None:
            return None, 1.0
        w = _ease((self.clock - self._mat_t0) / FADE_FINISH)
        if w >= 1.0:
            self._mat_from = None
            return None, 1.0
        return self._mat_from, w

    def ground(self, P):
        """The ground painted behind everything, or None for the terminal's own."""
        solid = self.solid if self.solid is not None else (P["ground"] is not None)
        if not solid:
            return None
        return P["ground"] if P["ground"] is not None else P["night"]

    def grid(self, cols, rows):
        key = (cols, rows, self.zoom)
        if self._grid is None or self._grid[0] != key:
            self._grid = (key,) + sample_grid(self.model, cols, rows, self.zoom)
            self._sky = {}
        return self._grid[1:4]

    @property
    def cell(self):
        return self._grid[4]

    def sky(self, cols, rows, name):
        """The environment behind the logo, for the backdrop (the same sky the
        chrome reflects), sparse and dim; once per size, finish and palette."""
        xs, ys, _ = self.grid(cols, rows)
        key = (MATERIALS[self.material]["env"], name)
        if key not in self._sky:
            span = (ys.max() - ys.min()) or 1.0
            # screen top looks ~38 deg up, the horizon sits ~60% of the way down
            dy = 0.78 * (ys - ys.min()) / span - 0.47
            dx = 0.78 * xs / span
            dirs = np.stack([dx, dy, -np.ones_like(xs)], 1).astype(np.float64)
            dirs /= np.linalg.norm(dirs, axis=1)[:, None]
            P = resolve_palette(name)[1]
            env = aces(ENVIRONMENTS[key[0]](dirs, P) * 0.8) ** (1 / 2.2)
            lum = env @ np.array([0.2126, 0.7152, 0.0722])
            # sparse, as ASCII skies must be: only clouds and the horizon glow get ink
            wisp = smoothstep(0.35, 0.8, vnoise(dx * 9.0 + 2.0, dy * 26.0) * 0.7 + vnoise(dx * 23.0, dy * 61.0) * 0.3)
            keep = smoothstep(0.50, 1.0, lum) * (0.30 + 0.70 * wisp) * 0.34
            self._sky[key] = (env * keep[:, None]).astype(np.float32)
        return self._sky[key]

    def backdrop_now(self, cols, rows):
        if not self.backdrop:
            return None
        return sum(np.float32(w) * self.sky(cols, rows, n) for n, w in self.mix())

    def job(self, cols, rows):
        """Everything a renderer needs for one frame (small enough to send)."""
        old, w = self.finish_mix()
        return dict(cols=cols, rows=rows, zoom=self.zoom, R=self.R.copy(), light=np.array(self.light),
                    material=self.material, also=(old, w), mix=self.mix())


def _ink(c):
    c = np.clip(np.asarray(c, float), 0.0, 1.0)
    return (int(c[0] * 255 + 0.5) << 16) | (int(c[1] * 255 + 0.5) << 8) | int(c[2] * 255 + 0.5)


def frame_from(glyphs, view, cols, rows, res):
    """Text for the terminal from a traced frame (rgb, lum, glint, cov)."""
    rgb, lum, glint, cov = res
    P = view.pal()[1]
    return make_frame(glyphs, rgb, lum, rows, cols, view.mode, view.contrast, glint, cov,
                      view.backdrop_now(cols, rows), view.stars, view.clock,
                      inks=tuple(_ink(c) for c in P["star"]), ground=view.ground(P))


# --------------------------------------------------------------------------- #
# Engines: trace frames here, or in worker processes in parallel.
# --------------------------------------------------------------------------- #
class Engine:
    """Traces whole frames in this process, remembering depths for coherence."""
    workers = 0

    def __init__(self, model):
        self.m = model
        self.rend = Renderer(model)
        self._grids = {}
        self.prev = None          # (grid key, R, depth)
        self.out = None

    def grid(self, cols, rows, zoom):
        key = (cols, rows, zoom)
        g = self._grids.get(key)
        if g is None:
            self._grids.clear()
            g = self._grids[key] = sample_grid(self.m, cols, rows, zoom)
        return key, g

    def submit(self, job):
        key, (xs, ys, fp, cell) = self.grid(job["cols"], job["rows"], job["zoom"])
        start = None
        if self.prev is not None and self.prev[0] == key:
            start = coherent_starts(self.m.radius, self.prev[1], job["R"], self.prev[2],
                                    job["rows"], job["cols"], cell)
        rgb, lum, glint, cov, depth = self.rend.trace(xs, ys, job["R"], job["light"], job["material"], fp,
                                                      start=start, pal=mix_palettes(job["mix"]),
                                                      also=job["also"])
        self.prev = (key, job["R"], depth)
        self.out = (rgb, lum, glint, cov)

    def collect(self):
        return self.out

    def close(self):
        pass


def _worker_main(conn, slots, depths, outline, puff, squash, wid, nw):
    """A render worker: traces its share of rows (every nw-th, so the work is
    balanced wherever the logo sits) straight into shared memory."""
    signal.signal(signal.SIGINT, signal.SIG_IGN)
    np.seterr(all="ignore")
    import warnings
    warnings.filterwarnings("ignore")
    try:
        model = Model(outline, puff=puff, squash=squash)
        rend = Renderer(model)
        S = [np.frombuffer(s, np.float32).reshape(-1, 6) for s in slots]
        Dp = [np.frombuffer(d, np.float32) for d in depths]
        grids = {}
        conn.send("ready")
        while True:
            job = conn.recv()
            if job is None:
                break
            cols, rows = job["cols"], job["rows"]
            key = (cols, rows, job["zoom"])
            g = grids.get(key)
            if g is None:
                grids.clear()
                xs, ys, fp, cell = sample_grid(model, cols, rows, job["zoom"])
                mine = np.concatenate([np.arange(y * cols * 6, (y + 1) * cols * 6) for y in range(wid, rows, nw)]
                                      or [np.zeros(0, np.intp)])
                g = grids[key] = (xs[mine], ys[mine], fp, cell, mine)
            xs, ys, fp, cell, mine = g
            n = cols * rows * 6
            start = None
            if job["Rp"] is not None:
                st = coherent_starts(model.radius, job["Rp"], job["R"], Dp[job["read"]][:n], rows, cols, cell)
                start = None if st is None else st[mine]
            rgb, lum, glint, cov, depth = rend.trace(xs, ys, job["R"], job["light"], job["material"], fp,
                                                     start=start, pal=mix_palettes(job["mix"]), also=job["also"])
            out = S[job["slot"]]
            out[mine, 0:3] = rgb
            out[mine, 3] = lum
            out[mine, 4] = glint
            out[mine, 5] = cov
            Dp[job["write"]][mine] = depth
            conn.send(job["seq"])
    except (EOFError, BrokenPipeError, KeyboardInterrupt):
        pass


class Pool:
    """Render workers (separate processes, stdlib multiprocessing) sharing the
    frame through shared memory. While they trace frame N+1, this process turns
    frame N into text: that is where the frame rate comes from."""

    def __init__(self, n, model, capacity):
        import multiprocessing as mp
        ctx = mp.get_context("spawn")
        self.m = model
        self.workers = n
        self.cap = capacity
        self.slots = [ctx.RawArray("f", capacity * 6) for _ in range(2)]
        self.depths = [ctx.RawArray("f", capacity) for _ in range(2)]
        self.views = [np.frombuffer(s, np.float32).reshape(-1, 6) for s in self.slots]
        self.dviews = [np.frombuffer(d, np.float32) for d in self.depths]
        self.procs, self.conns = [], []
        for w in range(n):
            a, b = ctx.Pipe()
            pr = ctx.Process(target=_worker_main, daemon=True,
                             args=(b, self.slots, self.depths, model.outline, model.puff, model.squash, w, n))
            pr.start()
            b.close()
            self.procs.append(pr)
            self.conns.append(a)
        for c in self.conns:
            if not c.poll(60.0) or c.recv() != "ready":
                self.close()
                raise RuntimeError("a render worker did not start")
        self.seq = 0
        self.prevR = None
        self.prevkey = None
        self.busy = None

    def submit(self, job):
        n = job["cols"] * job["rows"] * 6
        if n > self.cap:
            raise OverflowError
        key = (job["cols"], job["rows"], job["zoom"])
        self.seq += 1
        slot = self.seq & 1
        msg = dict(job, seq=self.seq, slot=slot, read=(self.seq - 1) & 1, write=slot,
                   Rp=self.prevR if self.prevkey == key else None)
        for c in self.conns:
            c.send(msg)
        self.prevR, self.prevkey = job["R"], key
        self.busy = (self.seq, slot, n)

    def collect(self):
        seq, slot, n = self.busy
        for c in self.conns:
            if c.recv() != seq:
                raise RuntimeError("render worker out of step")
        v = self.views[slot][:n]
        return (v[:, 0:3].copy(), v[:, 3].copy(), v[:, 4].copy(), v[:, 5].copy())

    def close(self):
        for c in self.conns:
            try:
                c.send(None)
            except (OSError, BrokenPipeError):
                pass
        for pr in self.procs:
            pr.join(timeout=1.0)
            if pr.is_alive():
                pr.terminate()


def make_engine(model, workers, cols, rows):
    if workers <= 0:
        return Engine(model)
    try:
        return Pool(workers, model, max(cols * rows * 6 * 2, 120 * 40 * 6))
    except Exception:
        return Engine(model)


def auto_workers():
    """One render process per core, up to six (this process mostly waits on
    them, and turns the previous frame into text meanwhile); none on one core."""
    n = os.cpu_count() or 1
    return 0 if n < 2 else min(6, n)


# --------------------------------------------------------------------------- #
# The terminal app.
# --------------------------------------------------------------------------- #
MOUSE_ON = "\x1b[?1003h\x1b[?1006h"
MOUSE_OFF = "\x1b[?1003l\x1b[?1006l"
SGR_MOUSE = re.compile(r"\x1b\[<(\d+);(\d+);(\d+)([Mm])")


def _write(s):
    data = s.encode("utf-8")
    fd = sys.stdout.fileno()
    while data:
        data = data[os.write(fd, data):]


def _cycle(seq, cur, step):
    return seq[(seq.index(cur) + step) % len(seq)]


def run(view, model, glyphs, args):
    import termios
    import tty
    fd = sys.stdin.fileno()
    saved = termios.tcgetattr(fd)
    np.seterr(all="ignore")
    writer = Writer(args.truecolor)
    stop = {"flag": False}
    signal.signal(signal.SIGTERM, lambda *a: stop.update(flag=True))
    try:
        size = os.get_terminal_size(sys.stdout.fileno())
        cols0, rows0 = max(20, size.columns), max(8, size.lines - 1)
    except OSError:
        cols0, rows0 = 100, 40
    engine = make_engine(model, args.workers, cols0, rows0)
    tty.setcbreak(fd)
    try:  # Ctrl-Z would leave the alternate screen up; q is the way out
        attrs = termios.tcgetattr(fd)
        attrs[6][termios.VSUSP] = os.fpathconf(fd, "PC_VDISABLE").to_bytes(1, "little")
        termios.tcsetattr(fd, termios.TCSADRAIN, attrs)
    except (OSError, ValueError, termios.error):
        pass
    _write("\x1b[?1049h\x1b[?25l\x1b[?7l\x1b[2J" + MOUSE_ON)
    frame_dt = 1.0 / max(1.0, args.fps)
    hud = Hud(time.monotonic())
    paused = False
    light_locked = False
    dragging = False
    last = None             # (x, y, time) of the last drag report
    vel = np.zeros(2)
    released = -10.0
    buf = ""
    t_prev = time.monotonic()
    shown_at = t_prev
    fps_est = args.fps
    ready = None            # a traced frame waiting to be written: (result, cols, rows)
    try:
        while not stop["flag"]:
            now = time.monotonic()
            dt = min(0.1, now - t_prev)
            t_prev = now
            try:
                size = os.get_terminal_size(sys.stdout.fileno())
                cols, rows = max(20, size.columns), max(8, size.lines - 1)
            except OSError:
                cols, rows = 100, 40

            # ---- input
            while select.select([fd], [], [], 0)[0]:
                chunk = os.read(fd, 4096)
                if not chunk:
                    stop["flag"] = True
                    break
                buf += chunk.decode("utf-8", "replace")
            while buf:
                mm = SGR_MOUSE.match(buf)
                if mm:
                    buf = buf[mm.end():]
                    b, x, y, rel = int(mm.group(1)), int(mm.group(2)), int(mm.group(3)), mm.group(4) == "m"
                    if b & 64:                          # wheel
                        view.zoom = min(3.0, max(0.35, view.zoom * (1.08 if b & 1 == 0 else 1 / 1.08)))
                        hud.touch(now)
                    elif (b & 3) == 0 and not (b & 32) and not rel:
                        dragging, last, vel[:] = True, (x, y, now), 0.0
                        hud.touch(now)
                    elif rel and dragging:
                        dragging = False
                        released = now
                        if last is not None and now - last[2] < 0.08:
                            view.spin = np.clip(vel, -9.0, 9.0)
                    elif b & 32:
                        if dragging and (b & 3) == 0 and last is not None:
                            ax, ay = (y - last[1]) * 0.085, (x - last[0]) * 0.045
                            view.R = orthonormal(rot_x(ax) @ rot_y(ay) @ view.R)
                            et = max(1 / 120.0, now - last[2])
                            vel = 0.55 * vel + 0.45 * np.array([ax, ay]) / et
                            last = (x, y, now)
                            view.spin[:] = 0.0
                        if not light_locked:
                            u = (x - 0.5) / cols * 2.0 - 1.0
                            v = 1.0 - (y - 0.5) / rows * 2.0
                            view.light_target = _unit((u * 1.4, v * 1.4, 1.0))
                    continue
                if buf.startswith("\x1b[<"):
                    if len(buf) > 32:   # garbage
                        buf = ""
                    break
                if buf.startswith("\x1b") and len(buf) > 1:
                    end = re.match(r"\x1b(\[[0-9;?]*[ -/]*[@-~]|O.|.)", buf)
                    buf = buf[end.end():] if end else ""
                    continue
                k, buf = buf[0], buf[1:]
                hud.touch(now)
                up = k.isupper()
                kl = k.lower()
                if k in ("q", "Q", "\x03") or (k == "\x1b" and not buf):
                    stop["flag"] = True
                elif k == " ":
                    paused = not paused
                    hud.say(now, "paused" if paused else "spinning")
                elif k == "r":
                    view.reset()
                    view.zoom = 1.0
                    hud.say(now, "back to the start")
                elif kl == "t":
                    view.set_palette(_cycle(PALETTE_ORDER, view.palette_name, -1 if up else 1))
                    hud.say(now, "palette", view.palette_name)
                elif kl == "m":
                    view.set_material(_cycle(MATERIAL_ORDER, view.material, -1 if up else 1))
                    hud.say(now, "finish", view.material)
                elif kl == "g":
                    view.mode = _cycle(GLYPH_MODES, view.mode, -1 if up else 1)
                    hud.say(now, "glyphs", view.mode)
                elif kl == "c":
                    view.charset = _cycle(list(CHARSETS), view.charset, -1 if up else 1)
                    glyphs = Glyphs(view.charset)
                    hud.say(now, "letters", view.charset)
                elif kl == "b":
                    view.backdrop = not view.backdrop
                    hud.say(now, "sky", "on" if view.backdrop else "off")
                elif kl == "s":
                    view.stars = not view.stars
                    hud.say(now, "stars", "on" if view.stars else "off")
                elif kl == "o":
                    view.solid = view.ground(view.pal()[1]) is None
                    hud.say(now, "ground", "solid" if view.solid else "the terminal's own")
                elif kl == "l":
                    light_locked = not light_locked
                    hud.say(now, "sun", "held" if light_locked else "follows the pointer")
                elif k in ("+", "="):
                    view.speed = min(6.0, view.speed * 1.25 + 0.02)
                    hud.say(now, "spin", "%.2f" % view.speed)
                elif k in ("-", "_"):
                    view.speed = max(0.0, view.speed / 1.25 - 0.01)
                    hud.say(now, "spin", "%.2f" % view.speed)
                elif k == "]":
                    view.contrast = min(4.0, view.contrast + 0.25)
                    hud.say(now, "contrast", "%.2f" % view.contrast)
                elif k == "[":
                    view.contrast = max(1.0, view.contrast - 0.25)
                    hud.say(now, "contrast", "%.2f" % view.contrast)
                elif kl == "f":
                    hud.fps = not hud.fps
                elif kl == "h":
                    hud.shown = not hud.shown
            if stop["flag"]:
                break

            # ---- motion
            if not dragging:
                if np.any(np.abs(view.spin) > 1e-3):
                    view.R = orthonormal(rot_x(view.spin[0] * dt) @ rot_y(view.spin[1] * dt) @ view.R)
                    view.spin *= math.exp(-dt / 1.3)
                if not paused:
                    blend_in = min(1.0, max(0.0, (now - released - 0.6) / 1.8))
                    view.R = orthonormal(rot_y(view.speed * dt * blend_in) @ view.R)
            view.light = _unit(view.light + (view.light_target - view.light) * (1 - math.exp(-dt / 0.12)))
            view.clock += dt

            # ---- trace the next frame (in parallel workers when there are any)
            view.grid(cols, rows)
            job = view.job(cols, rows)
            try:
                engine.submit(job)
            except (OverflowError, RuntimeError, OSError, EOFError):
                engine.close()
                engine = make_engine(model, args.workers, cols, rows)
                engine.submit(job)
            # ---- meanwhile, write the frame traced last time round
            if ready is not None:
                res, c0, r0 = ready
                if (c0, r0) == (cols, rows):
                    fr = frame_from(glyphs, view, c0, r0, res)
                    P = view.pal()[1]
                    _write(writer.compose(fr, hud.line(now, cols, P, writer, fps_est)))
                    t_shown = time.monotonic()
                    fps_est = 0.9 * fps_est + 0.1 / max(1e-3, t_shown - shown_at)
                    shown_at = t_shown
            try:
                ready = (engine.collect(), cols, rows)
            except (RuntimeError, OSError, EOFError):
                engine.close()
                engine = Engine(model)
                ready = None
                continue

            # ---- wait for the next frame's moment, keeping input as it comes
            deadline = now + frame_dt
            while True:
                left = deadline - time.monotonic()
                if left <= 0:
                    break
                if select.select([fd], [], [], left)[0]:
                    chunk = os.read(fd, 4096)
                    if not chunk:
                        stop["flag"] = True
                        break
                    buf += chunk.decode("utf-8", "replace")
    finally:
        try:
            engine.close()
        finally:
            _write(MOUSE_OFF + "\x1b[0m\x1b[?7h\x1b[?25h\x1b[?1049l")
            termios.tcsetattr(fd, termios.TCSADRAIN, saved)


# --------------------------------------------------------------------------- #
# Extras: one frame to stdout, a PNG of the model, tracing another logo.
# --------------------------------------------------------------------------- #
def write_png(path, img):
    img = np.clip(img * 255 + 0.5, 0, 255).astype(np.uint8)
    h, w, _ = img.shape
    raw = b"".join(b"\x00" + img[y].tobytes() for y in range(h))

    def chunk(t, data):
        c = struct.pack(">I", len(data)) + t + data
        return c + struct.pack(">I", zlib.crc32(t + data) & 0xFFFFFFFF)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def render_png(view, rend, path, w=1200, h=1000, ss=2):
    m = view.model
    S = 0.94 * min(w / (2 * m.reach_x), h / (2 * (m.reach_y + 1.2)))
    W, H = w * ss, h * ss
    xs = (np.arange(W) + 0.5) / ss - w / 2
    ys = h / 2 - (np.arange(H) + 0.5) / ss
    X, Y = np.meshgrid(xs / S, ys / S)
    rgb = np.zeros((H * W, 3))
    xr, yr = X.ravel(), Y.ravel()
    for s in range(0, len(xr), 200000):
        c = rend.trace(xr[s:s + 200000], yr[s:s + 200000], view.R, view.light, view.material, 0.7 / (S * ss),
                       pal=view.pal())[0]
        rgb[s:s + 200000] = c
    img = rgb.reshape(H, W, 3).reshape(h, ss, w, ss, 3).mean((1, 3))
    bg = np.array([0.055, 0.05, 0.07])
    alpha = np.clip(img.max(2, keepdims=True) * 4, 0, 1)
    write_png(path, img + bg * (1 - alpha))


def fillet(poly, radius, step=math.pi / 14):
    """Replace every corner with a circular arc tangent to both edges."""
    P = np.asarray(poly, float)
    n = len(P)
    out = []
    for i in range(n):
        p0, p1, p2 = P[i - 1], P[i], P[(i + 1) % n]
        a, b = p0 - p1, p2 - p1
        la, lb = np.linalg.norm(a), np.linalg.norm(b)
        a, b = a / la, b / lb
        theta = math.acos(max(-1.0, min(1.0, float(a @ b))))
        if radius <= 0 or theta < 1e-3 or math.pi - theta < 1e-3:
            out.append(p1)
            continue
        tt = min(radius / math.tan(theta / 2), 0.45 * la, 0.45 * lb)
        r = tt * math.tan(theta / 2)
        bis = (a + b) / np.linalg.norm(a + b)
        c = p1 + bis * (r / math.sin(theta / 2))
        q0, q2 = p1 + a * tt, p1 + b * tt
        f0 = math.atan2(q0[1] - c[1], q0[0] - c[0])
        f2 = math.atan2(q2[1] - c[1], q2[0] - c[0])
        sweep = (f2 - f0 + math.pi) % (2 * math.pi) - math.pi
        k = max(2, int(math.ceil(abs(sweep) / step)))
        for j in range(k + 1):
            f = f0 + sweep * j / k
            out.append(c + r * np.array([math.cos(f), math.sin(f)]))
    return np.array(out)


def _to_units(pts, width=42.0):
    """Centre an outline, scale it to `width` columns, make it counter-clockwise."""
    pts = np.asarray(pts, float)
    pts = (pts - (pts.max(0) + pts.min(0)) / 2) * (width / max(1e-9, np.ptp(pts[:, 0])))
    area = 0.5 * np.sum(pts[:, 0] * np.roll(pts[:, 1], -1) - np.roll(pts[:, 0], -1) * pts[:, 1])
    return pts[::-1] if area < 0 else pts


def trace_logo(path):
    """Trace a logo into an outline: an image (the biggest blob that is not the
    frame or ring around it, dark-on-light or light-on-dark) or ASCII art.
    Needs Pillow, scipy and scikit-image; the balloon built from it is cached."""
    try:
        from PIL import Image, ImageDraw, ImageFont
        from scipy import ndimage
        from skimage import measure
    except ImportError as e:
        sys.exit("--logo needs Pillow, scipy and scikit-image (%s)" % e)
    if os.path.splitext(path)[1].lower() in (".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp", ".tif", ".tiff"):
        im = np.asarray(Image.open(path).convert("RGBA"), float) / 255.0
        a = im[..., 3]
        lum = im[..., :3].mean(2) * a + (1.0 - a)
        best = None
        for ink in (1.0 - lum, lum):                    # dark logo, then light logo
            lab, n = ndimage.label(ink > 0.5)
            if n == 0:
                continue
            h, w = ink.shape
            for i in range(1, n + 1):
                ys, xs = np.nonzero(lab == i)
                touches = ys.min() == 0 or xs.min() == 0 or ys.max() == h - 1 or xs.max() == w - 1
                if touches or len(ys) < 0.01 * h * w:
                    continue
                if best is None or len(ys) > best[0]:
                    best = (len(ys), ink, lab == i)
        if best is None:
            sys.exit("no logo shape found in %s" % path)
        _, ink, blob = best
        cov = np.pad(np.where(ndimage.binary_dilation(blob, iterations=3), ink, 0.0), 8)
        up = ndimage.zoom(cov, 4, order=3)
        c = sorted(measure.find_contours(up, 0.5), key=len, reverse=True)[0] / 4.0 - 8
        P = measure.approximate_polygon(c, tolerance=0.18)[:-1]
        return _to_units([(x, -y) for y, x in P]).tolist()

    cw, chh = 12, 24
    font = None
    for f in ("/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf", "/Library/Fonts/Menlo.ttc",
              "/System/Library/Fonts/Menlo.ttc", "C:/Windows/Fonts/consola.ttf"):
        if os.path.exists(f):
            font = ImageFont.truetype(f, 20)
            break
    lines = open(path, encoding="utf-8").read().split("\n")
    while lines and not lines[0].strip():
        lines.pop(0)
    while lines and not lines[-1].strip():
        lines.pop()
    w = max(map(len, lines))
    lines = [ln.ljust(w) for ln in lines]

    def glyph(ch):
        if not font:
            return np.full((chh, cw), 0.0 if ch == " " else 0.6)
        im = Image.new("L", (cw, chh), 0)
        ImageDraw.Draw(im).text((0, -2), ch, font=font, fill=255)
        return np.asarray(im, float) / 255
    full = glyph("@").mean()
    img = np.zeros((len(lines) * chh, w * cw))
    for r, ln in enumerate(lines):
        for c, ch in enumerate(ln):
            if ch != " ":
                g = glyph(ch)
                gb = ndimage.gaussian_filter(g, 3)
                img[r * chh:(r + 1) * chh, c * cw:(c + 1) * cw] = np.clip(
                    gb / max(gb.mean(), 1e-6) * min(1.0, g.mean() / full), 0, 1.5)
    pad = 30
    field = np.pad(ndimage.gaussian_filter(img, (chh * 0.35, cw * 0.6)), pad)
    cs = sorted(measure.find_contours(field, 0.5), key=len, reverse=True)
    if not cs:
        sys.exit("nothing to trace in %s" % path)
    P = measure.approximate_polygon(cs[0], tolerance=6.0)[:-1]
    pts = np.array([((x - pad) / cw, -(y - pad) / cw) for y, x in P])
    while len(pts) > 3:   # drop near-collinear kinks the coarse art leaves on straight edges
        u = pts - np.roll(pts, 1, 0)
        v = np.roll(pts, -1, 0) - pts
        turn = np.abs(np.degrees(np.arctan2(u[:, 0] * v[:, 1] - u[:, 1] * v[:, 0], (u * v).sum(1))))
        i = int(turn.argmin())
        if turn[i] >= 14.0:
            break
        pts = np.delete(pts, i, 0)
    return fillet(_to_units(pts), 1.6).tolist()


def _palette_arg(s):
    name = re.sub(r"[-_]+", " ", s.strip().lower())
    for full in PALETTE_ORDER:
        if name == full or full.split()[0] == name:
            return full
    raise argparse.ArgumentTypeError("unknown palette %r (try: %s)" % (s, ", ".join(PALETTE_ORDER)))


def render_cells(engine, glyphs, view, cols, rows):
    """One frame, start to finish (for previews and tests)."""
    view.grid(cols, rows)
    engine.submit(view.job(cols, rows))
    return frame_from(glyphs, view, cols, rows, engine.collect())


def main(argv=None):
    ap = argparse.ArgumentParser(description="An inflated chrome logo, raymarched into ASCII.")
    ap.add_argument("--palette", type=_palette_arg, default="aphex", metavar="NAME",
                    help="colour palette (key t): " + ", ".join(PALETTE_ORDER) +
                         " (spaces may be written as - or _)")
    ap.add_argument("--material", choices=MATERIAL_ORDER + list(MATERIAL_ALIASES), default="chrome",
                    help="finish (key m)")
    ap.add_argument("--glyphs", choices=GLYPH_MODES, default="shape",
                    help="shape: glyphs chosen by shape (sharpest); ramp: density ramp; block: half blocks")
    ap.add_argument("--puff", type=float, default=0.82, help="how far the balloon is inflated (0.4-1.2)")
    ap.add_argument("--squash", type=float, default=0.30, help="how much the wide parts are flattened (0-0.8)")
    ap.add_argument("--speed", type=float, default=0.55, help="auto-spin, rad/s")
    ap.add_argument("--contrast", type=float, default=2.25, help="shape mode edge contrast (1-4)")
    ap.add_argument("--charset", choices=list(CHARSETS), default="all",
                    help="glyphs the shape mode may use: all printable ASCII (richest), or classic symbols")
    ap.add_argument("--backdrop", action="store_true", help="draw the sky behind the logo (key b)")
    ap.add_argument("--no-sparkles", dest="no_sparkles", action="store_true", help="no star glints (key s)")
    ap.add_argument("--ground", choices=("palette", "solid", "terminal"), default="palette",
                    help="paint the palette's ground, or keep the terminal's own background (key o)")
    ap.add_argument("--fps", type=float, default=60.0)
    ap.add_argument("--workers", type=int, default=None,
                    help="render processes (default: one per spare core, up to 6; 0 renders in this one)")
    ap.add_argument("--logo", help="a logo to inflate instead: an image (dark or light on a plain "
                                   "background) or an ASCII-art text file")
    ap.add_argument("--truecolor", dest="truecolor", action="store_true", default=None)
    ap.add_argument("--256", dest="truecolor", action="store_false")
    ap.add_argument("--preview", action="store_true", help="print one frame and exit")
    ap.add_argument("--size", help="preview size, COLSxROWS")
    ap.add_argument("--yaw", type=float, default=0.0, help="preview: extra turn, radians")
    ap.add_argument("--pitch", type=float, default=0.0, help="preview: extra tilt, radians")
    ap.add_argument("--png", help="render the 3D model to this PNG and exit")
    args = ap.parse_args(argv)
    if args.truecolor is None:
        args.truecolor = _truecolor_default() or args.preview
    args.material = MATERIAL_ALIASES.get(args.material, args.material)
    args.solid = {"palette": None, "solid": True, "terminal": False}[args.ground]
    if args.workers is None:
        args.workers = auto_workers()

    poly = LOGO_POLY
    if args.logo:
        poly = trace_logo(args.logo)
    model = Model(poly, puff=args.puff, squash=args.squash)
    glyphs = Glyphs(args.charset)
    view = View(model, args)
    if args.yaw or args.pitch:
        view.R = rot_x(args.pitch) @ rot_y(args.yaw) @ view.R

    if args.png:
        render_png(view, Renderer(model), args.png)
        print("wrote", args.png)
        return
    if args.preview or not sys.stdin.isatty() or not sys.stdout.isatty():
        if args.size:
            cols, rows = (int(v) for v in args.size.lower().split("x"))
        else:
            sz = os.get_terminal_size() if sys.stdout.isatty() else os.terminal_size((100, 42))
            cols, rows = sz.columns, sz.lines - 1
        fr = render_cells(Engine(model), glyphs, view, cols, rows)
        sys.stdout.write(_rows_to_text(fr, args.truecolor))
        return
    try:
        run(view, model, glyphs, args)
    except KeyboardInterrupt:
        pass


def _rows_to_text(fr, truecolor):
    w = Writer(truecolor)
    lines = []
    for y in range(fr.chars.shape[0]):
        one = Frame(fr.chars[y:y + 1], fr.fg[y:y + 1], None if fr.bg is None else fr.bg[y:y + 1], fr.ground)
        w.prev = None
        s = w.compose(one)
        s = re.sub(r"\x1b\[\?2026[hl]|\x1b\[2J|\x1b\[\d+;1H", "", s)
        lines.append(s.rstrip() + "\x1b[0m")
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    main()
