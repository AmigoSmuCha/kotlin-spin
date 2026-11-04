#!/usr/bin/env python3
"""aphex_spin: a glossy 3D logo, raymarched and drawn in ASCII, in your terminal.

    python3 aphex_spin.py                    run it
    python3 aphex_spin.py --preview          print one frame and exit (no mouse needed)
    python3 aphex_spin.py --png model.png    render the 3D model itself as an image
    python3 aphex_spin.py --material chrome --glyphs block --fps 30
    python3 aphex_spin.py --logo other.txt   trace another ASCII logo (needs Pillow,
                                             scipy and scikit-image, once; then cached)

Mouse   drag    spin it; let go mid-swing and it keeps the momentum
        hover   the key light follows the pointer, highlights slide with it
        wheel   zoom
Keys    space pause  r reset  m/M material  g glyph mode  l lock light
        + / - spin speed  [ / ] contrast  h help line  q quit

How it is made
  model   The outline is a 31-point polygon of straight edges traced once from
          the ASCII art (glyph-weighted coverage, contour, Douglas-Peucker).
          Every corner is filleted, the exact signed distance to that outline is
          baked into a grid, and the solid is Inigo Quilez's rounded extrusion of
          it: a slab whose rims are rounded by a bevel radius. Normals are
          analytic, so the surface shades smoothly across the bevels.
  render  Orthographic sphere tracing, six rays per character cell. The flat
          caps are solved analytically, so only rays near edges and walls are
          marched; rays that pass close to the surface are anti-aliased from the
          distance they missed by.
  shade   Glossy plastic: key, fill and rim lights (Blinn-Phong), a studio of
          softboxes seen in reflection, Schlick Fresnel, ACES tone mapping.
  ascii   Shape-based glyph choice after Alex Harri, "ASCII characters are not
          pixels" (alexharri.com/blog/ascii-rendering): each cell's six samples
          are compared with the same six sampling circles measured on every
          printable glyph of DejaVu Sans Mono, after directional and global
          contrast enhancement, so edges are drawn by glyphs whose shape follows
          them. Ink is truecolor per cell. The ramp mode's 4x4 Bayer blend
          follows asciify-engine (ayangabryl, MIT).

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
LOGO_POLY = [(20.45, -16.54), (20.45, -12.89), (19.64, -12.37), (13.09, 12.21), (11.55, 13.38), (9.44, 16.96), (-19.35, 16.96), (-20.45, 14.04), (-17.80, 9.36), (-8.22, 9.22), (-5.30, 12.63), (-4.05, 12.39), (-3.25, 11.04), (-3.38, 9.46), (-9.67, -2.46), (-11.30, -3.30), (-13.37, -7.46), (-12.89, -10.48), (-9.86, -10.62), (-4.14, -3.17), (0.20, -2.13), (1.07, 0.04), (0.67, 4.54), (2.95, 5.24), (11.67, -8.29), (13.20, -9.36), (17.03, -16.96), (18.78, -16.96)]

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

#            albedo                 F0     metal  shininess  spec  env
MATERIALS = {
    "lilac":    ((0.26, 0.10, 0.70), 0.05, False, 120.0, 1.0, 1.15),
    "obsidian": ((0.035, 0.03, 0.045), 0.06, False, 160.0, 1.4, 1.9),
    "chrome":   ((0.92, 0.93, 0.98), 0.92, True,  300.0, 1.2, 1.0),
    "bone":     ((0.78, 0.74, 0.66), 0.04, False, 60.0,  0.7, 0.9),
    "acid":     ((0.40, 0.85, 0.06), 0.05, False, 110.0, 1.0, 1.1),
    "ember":    ((0.95, 0.28, 0.06), 0.05, False, 110.0, 1.0, 1.1),
}
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
# The model: filleted outline -> 2D signed distance grid -> rounded extrusion.
# --------------------------------------------------------------------------- #
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
        t = min(radius / math.tan(theta / 2), 0.45 * la, 0.45 * lb)
        r = t * math.tan(theta / 2)
        bis = a + b
        bis /= np.linalg.norm(bis)
        c = p1 + bis * (r / math.sin(theta / 2))
        q0, q2 = p1 + a * t, p1 + b * t
        f0 = math.atan2(q0[1] - c[1], q0[0] - c[0])
        f2 = math.atan2(q2[1] - c[1], q2[0] - c[0])
        sweep = (f2 - f0 + math.pi) % (2 * math.pi) - math.pi
        k = max(2, int(math.ceil(abs(sweep) / step)))
        for j in range(k + 1):
            f = f0 + sweep * j / k
            out.append(c + r * np.array([math.cos(f), math.sin(f)]))
    return np.array(out)


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
    for s in range(0, n, 2048):
        q = p[s:s + 2048]
        W = q[:, None, :] - A[None]
        t = np.clip((W * E[None]).sum(2) / EE[None], 0.0, 1.0)
        C = A[None] + t[..., None] * E[None]
        dd = ((q[:, None, :] - C) ** 2).sum(2)
        k = dd.argmin(1)
        r = np.arange(len(q))
        dist[s:s + 2048] = np.sqrt(dd[r, k])
        near[s:s + 2048] = C[r, k]
        ay, py = A[None, :, 1], q[:, None, 1]
        by = ay + E[None, :, 1]
        xi = A[None, :, 0] + (py - ay) * E[None, :, 0] / Ey[None]
        inside[s:s + 2048] = (((ay > py) != (by > py)) & (q[:, None, 0] < xi)).sum(1) % 2 == 1
    sign = np.where(inside, -1.0, 1.0)
    g = (p - near) / np.maximum(dist, 1e-9)[:, None] * sign[:, None]
    return dist * sign, g


class Model:
    def __init__(self, poly, depth=5.0, bevel=1.1, corner=0.9, res=7.0):
        self.outline = fillet(poly, corner)
        self.H = depth / 2.0
        self.rb = max(0.05, min(bevel, self.H * 0.95))
        lo = self.outline.min(0) - 2.5
        hi = self.outline.max(0) + 2.5
        self.x0, self.y0, self.res = float(lo[0]), float(lo[1]), res
        self.nx = int((hi[0] - lo[0]) * res) + 2
        self.ny = int((hi[1] - lo[1]) * res) + 2
        key = hashlib.sha1(repr((np.round(self.outline, 4).tolist(), res)).encode()).hexdigest()[:16]
        D = G = None
        cache = _cache_dir()
        path = os.path.join(cache, "sdf-%s.npz" % key) if cache else None
        if path and os.path.exists(path):
            try:
                z = np.load(path)
                D, G = z["D"], z["G"]
            except Exception:
                D = None
        if D is None:
            xs = self.x0 + np.arange(self.nx) / res
            ys = self.y0 + np.arange(self.ny) / res
            X, Y = np.meshgrid(xs, ys)
            d, g = polygon_sdf(np.stack([X.ravel(), Y.ravel()], 1), self.outline)
            D, G = d.astype(np.float32), g.astype(np.float32)
            if path:
                try:
                    np.savez_compressed(path, D=D, G=G)
                except OSError:
                    pass
        self.D = D.astype(np.float64)
        self.GX = G[:, 0].astype(np.float64)
        self.GY = G[:, 1].astype(np.float64)
        ext = np.abs(self.outline).max(0)
        self.half = np.array([ext[0] + 0.05, ext[1] + 0.05, self.H])
        self.reach_x = float(np.sqrt(self.outline[:, 0] ** 2 + self.H ** 2).max())
        self.reach_y = float(ext[1])
        self.radius = float(np.sqrt((self.outline ** 2).sum(1).max() + self.H ** 2))

    def _lookup(self, x, y):
        fx = (x - self.x0) * self.res
        fy = (y - self.y0) * self.res
        cx = np.clip(fx, 0.0, self.nx - 1.001)
        cy = np.clip(fy, 0.0, self.ny - 1.001)
        ix = cx.astype(np.intp)
        iy = cy.astype(np.intp)
        tx = cx - ix
        ty = cy - iy
        i = iy * self.nx + ix
        out = np.hypot(fx - cx, fy - cy) / self.res
        return i, tx, ty, out

    def _bilerp(self, F, i, tx, ty):
        a = F[i]
        b = F[i + 1]
        c = F[i + self.nx]
        d = F[i + self.nx + 1]
        top = a + (b - a) * tx
        return top + (c + (d - c) * tx - top) * ty

    def d2(self, x, y):
        i, tx, ty, out = self._lookup(x, y)
        return self._bilerp(self.D, i, tx, ty) + out

    def sdf(self, P):
        q = self.d2(P[:, 0], P[:, 1]) + self.rb
        w = np.abs(P[:, 2]) - (self.H - self.rb)
        return (np.minimum(np.maximum(q, w), 0.0)
                + np.sqrt(np.maximum(q, 0.0) ** 2 + np.maximum(w, 0.0) ** 2) - self.rb)

    def normal(self, P):
        i, tx, ty, out = self._lookup(P[:, 0], P[:, 1])
        q = self._bilerp(self.D, i, tx, ty) + out + self.rb
        gx = self._bilerp(self.GX, i, tx, ty)
        gy = self._bilerp(self.GY, i, tx, ty)
        gl = np.maximum(np.hypot(gx, gy), 1e-9)
        gx, gy = gx / gl, gy / gl
        sz = np.where(P[:, 2] >= 0, 1.0, -1.0)
        w = np.abs(P[:, 2]) - (self.H - self.rb)
        both = (q > 0) & (w > 0)
        wall = ~both & (q > w)
        qq = np.where(both, q, np.where(wall, 1.0, 0.0))
        ww = np.where(both, w, np.where(wall, 0.0, 1.0))
        N = np.stack([gx * qq, gy * qq, sz * ww], 1)
        return N / np.maximum(np.linalg.norm(N, axis=1), 1e-9)[:, None]


def _cache_dir():
    base = os.environ.get("XDG_CACHE_HOME") or os.path.join(os.path.expanduser("~"), ".cache")
    path = os.path.join(base, "aphex_spin")
    try:
        os.makedirs(path, exist_ok=True)
        return path
    except OSError:
        return None


# --------------------------------------------------------------------------- #
# Rendering: sphere tracing and glossy plastic.
# --------------------------------------------------------------------------- #
def studio(r):
    """A dim lilac studio with three softboxes, as seen in a reflection."""
    x, y, z = r[:, 0], r[:, 1], r[:, 2]
    base = 0.035 + 0.11 * smoothstep(-0.35, 0.95, y)
    top = band(y, 0.60, 1.05, 0.07) * band(x, -0.58, 0.58, 0.07) * 3.4
    left = band(x, -1.05, -0.60, 0.06) * band(y, -0.30, 0.62, 0.07) * smoothstep(-0.5, 0.0, z) * 2.4
    right = band(x, 0.64, 1.05, 0.06) * band(y, -0.48, 0.40, 0.07) * 1.5
    front = band(x, -0.80, -0.08, 0.16) * band(y, 0.02, 0.62, 0.16) * smoothstep(0.3, 0.8, z) * 0.9
    floor = 0.05 * smoothstep(-0.1, -0.9, y)
    out = np.empty((len(r), 3))
    out[:, 0] = base * 0.92 + top + left * 1.00 + right * 0.62 + front * 0.95 + floor * 0.9
    out[:, 1] = base * 0.86 + top + left * 0.86 + right * 0.78 + front * 0.92 + floor * 0.8
    out[:, 2] = base * 1.10 + top + left * 0.70 + right * 1.10 + front * 1.00 + floor * 1.1
    return out


def aces(x):
    return np.clip((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0)


class Renderer:
    MAX_STEPS = 48
    EPS = 0.015

    def __init__(self, model):
        self.m = model

    def trace(self, xv, yv, R, light, material, footprint):
        """Rays through view-space points (xv, yv), looking down -z, against the
        model rotated by R (object -> view). Returns sRGB colour (n,3) and its
        luminance, both 0 where the ray misses."""
        m = self.m
        n = len(xv)
        Z0 = m.radius + 5.0
        o = xv[:, None] * R[0] + yv[:, None] * R[1] + Z0 * R[2]
        d = -R[2].copy()
        dsafe = np.where(np.abs(d) < 1e-9, 1e-9, d)
        t1 = (-m.half - o) / dsafe
        t2 = (m.half - o) / dsafe
        tmin = np.maximum(np.minimum(t1, t2).max(1), 0.0)
        tmax = np.maximum(t1, t2).min(1)
        valid = tmax > tmin

        hit_t = np.full(n, np.nan)
        cov = np.zeros(n)
        cap = np.zeros(n, bool)
        march = valid.copy()
        s = -1.0 if d[2] > 0 else 1.0
        if abs(d[2]) > 0.06:
            tc = (s * m.H - o[:, 2]) / d[2]
            hx = o[:, 0] + tc * d[0]
            hy = o[:, 1] + tc * d[1]
            flat = valid & (m.d2(hx, hy) < -m.rb - 0.02)
            hit_t[flat] = tc[flat]
            cap = flat
            cov[flat] = 1.0
            march &= ~flat

        idx = np.nonzero(march)[0]
        t = tmin[idx].copy()
        tmx = tmax[idx]
        O = o[idx]
        best = np.full(len(idx), np.inf)
        tb = t.copy()
        miss_i, miss_d, miss_t = [], [], []
        for _ in range(self.MAX_STEPS):
            if len(idx) == 0:
                break
            P = O + t[:, None] * d
            dist = m.sdf(P)
            closer = dist < best
            best = np.where(closer, dist, best)
            tb = np.where(closer, t, tb)
            hit = dist < self.EPS
            if hit.any():
                hit_t[idx[hit]] = t[hit]
                cov[idx[hit]] = 1.0
            t = t + np.maximum(dist, self.EPS * 0.5)
            out = ~hit & (t >= tmx)
            if out.any():
                miss_i.append(idx[out])
                miss_d.append(best[out])
                miss_t.append(tb[out])
            keep = ~hit & ~out
            if not keep.all():
                idx, t, tmx, O, best, tb = idx[keep], t[keep], tmx[keep], O[keep], best[keep], tb[keep]
        if len(idx):
            miss_i.append(idx)
            miss_d.append(best)
            miss_t.append(tb)

        # near misses: shade where the ray passed closest, weighted by coverage
        if miss_i:
            mi = np.concatenate(miss_i)
            md = np.concatenate(miss_d)
            mt = np.concatenate(miss_t)
            near = md < footprint
            mi, md, mt = mi[near], md[near], mt[near]
            c = np.clip(1.0 - md / footprint, 0.0, 1.0)
            hit_t[mi] = mt
            cov[mi] = c * c * (3.0 - 2.0 * c)

        shaded = np.nonzero(cov > 0)[0]
        rgb = np.zeros((n, 3))
        if len(shaded):
            P = o[shaded] + hit_t[shaded, None] * d
            N = np.empty((len(shaded), 3))
            capped = cap[shaded]
            N[capped] = (0.0, 0.0, s)
            if (~capped).any():
                N[~capped] = m.normal(P[~capped])
            col = self.shade(N @ R.T, P @ R.T, light, material)
            rgb[shaded] = col * cov[shaded, None]
        lum = rgb @ np.array([0.2126, 0.7152, 0.0722])
        return rgb, lum

    def shade(self, N, Pv, light, material):
        """Glossy plastic in view space. N normals, Pv positions (view space).
        The rays are orthographic, but shading uses a camera and a key light at
        finite distances, so flat faces get gradients and highlights travel."""
        albedo, f0, metal, shine, ks, ke = MATERIALS[material]
        albedo = np.array(albedo)
        rad = self.m.radius
        V = np.array([0.0, 0.0, 2.4 * rad]) - Pv
        V /= np.linalg.norm(V, axis=1)[:, None]
        ndv = np.clip((N * V).sum(1), 0.0, 1.0)
        kp = np.array([light[0] * 1.7 * rad, light[1] * 1.7 * rad, max(0.35, light[2]) * 1.5 * rad])
        K = kp[None] - Pv
        K /= np.linalg.norm(K, axis=1)[:, None]
        lights = ((K, np.array([1.0, 0.94, 0.86]) * 1.65),
                  (np.broadcast_to(_unit(FILL_LIGHT), N.shape), np.array([0.50, 0.62, 1.0]) * 0.30),
                  (np.broadcast_to(_unit(RIM_LIGHT), N.shape), np.array([1.0, 0.90, 1.0]) * 1.10))
        hemi = (0.5 + 0.5 * N[:, 1])[:, None]
        diff = np.array([0.012, 0.010, 0.018])[None] * (1 - hemi) + np.array([0.075, 0.07, 0.10])[None] * hemi
        spec = np.zeros_like(diff)
        norm = (shine + 8.0) / (8.0 * math.pi)
        for L, c in lights:
            ndl = np.clip((N * L).sum(1), 0.0, None)
            diff = diff + ndl[:, None] * c[None]
            Hh = L + V
            Hh = Hh / np.maximum(np.linalg.norm(Hh, axis=1), 1e-9)[:, None]
            spec = spec + ((np.clip((N * Hh).sum(1), 0.0, None) ** shine) * norm * (ndl > 0))[:, None] * c[None]
        r = 2.0 * ndv[:, None] * N - V
        env = studio(r)
        fr = (1.0 - ndv) ** 5
        if metal:
            F = albedo[None] + (1.0 - albedo[None]) * fr[:, None]
            col = F * (env * ke + spec * ks * 0.2)
        else:
            F = (f0 + (1.0 - f0) * fr)[:, None]
            col = albedo[None] * diff * (1.0 - F) + F * env * ke + spec * ks * 0.10
        return aces(col) ** (1 / 2.2)


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
        self.V = V[keep]
        self.VV = (self.V * self.V).sum(1)

    def match(self, S):
        d = self.VV[None, :] - 2.0 * (S @ self.V.T)
        return d.argmin(1)


def enhance(S, e_dir, e_glob):
    """Harri's contrast enhancement on samples S (rows, cols, 6)."""
    P = np.pad(S, ((1, 1), (1, 1), (0, 0)))
    up, down = P[:-2, 1:-1], P[2:, 1:-1]
    left, right = P[1:-1, :-2], P[1:-1, 2:]
    ext = (up[..., 4], up[..., 5], left[..., 1], right[..., 0], left[..., 3],
           right[..., 2], left[..., 5], right[..., 4], down[..., 0], down[..., 1])
    out = np.empty_like(S)
    for i, aff in enumerate(AFFECT):
        mx = S[..., i]
        for k in aff:
            mx = np.maximum(mx, ext[k])
        safe = np.maximum(mx, 1e-6)
        out[..., i] = (S[..., i] / safe) ** e_dir * mx
    mx = np.maximum(out.max(2, keepdims=True), 1e-6)
    return (out / mx) ** e_glob * mx


def pack(rgb):
    c = np.clip(rgb * 255.0 + 0.5, 0, 255).astype(np.int64) & 0xFC
    return (c[..., 0] << 16) | (c[..., 1] << 8) | c[..., 2]


class Frame:
    """Characters and colours for a terminal of `cols` x `rows` cells."""

    def __init__(self, chars, fg, bg=None):
        self.chars, self.fg, self.bg = chars, fg, bg


def make_frame(glyphs, rgb, lum, rows, cols, mode, contrast, exposure=1.0):
    rgb = rgb.reshape(rows, cols, 6, 3)
    lum = lum.reshape(rows, cols, 6)
    if mode == "block":
        top = (rgb[:, :, 0] + rgb[:, :, 1] + 0.5 * (rgb[:, :, 2] + rgb[:, :, 3])) / 3.0
        bot = (rgb[:, :, 4] + rgb[:, :, 5] + 0.5 * (rgb[:, :, 2] + rgb[:, :, 3])) / 3.0
        chars = np.full((rows, cols), "\u2580")
        return Frame(chars, pack(np.clip(top * exposure, 0, 1)), pack(np.clip(bot * exposure, 0, 1)))

    wsum = lum.sum(2)
    ink = (rgb * lum[..., None]).sum(2) / np.maximum(wsum, 1e-6)[..., None]
    peak = lum.max(2)
    if mode == "ramp":
        mean = lum.mean(2) * exposure
        y = np.arange(rows)[:, None]
        x = np.arange(cols)[None, :]
        th = (np.array(BAYER)[(y % 4) * 4 + (x % 4)] + 0.5) / 16.0 - 0.5
        k = np.clip(np.floor(mean ** 0.85 * (len(RAMP) - 1) + 0.5 + th * 0.9), 0, len(RAMP) - 1)
        chars = np.array(list(RAMP))[k.astype(np.intp)]
    else:
        S = np.clip(lum * exposure * 0.85, 0.0, 1.0)
        S = enhance(S, contrast, 1.0 + 0.45 * contrast)
        chars = glyphs.chars[glyphs.match(S.reshape(-1, 6))].reshape(rows, cols)
    # ink colour: the hue of the lit samples; brightness compressed (the glyph
    # already carries part of it) but kept, so the shading shows in colour too
    il = ink @ np.array([0.2126, 0.7152, 0.0722])
    target = np.clip(np.maximum(il, peak * 0.8) ** 0.6 * 1.25, 0.0, 1.0)
    fg = ink * (target / np.maximum(il, 1e-6))[..., None]
    fg = fg / np.maximum(fg.max(2, keepdims=True), 1.0)
    fgp = pack(fg)
    fgp[chars == " "] = -1
    return Frame(chars, fgp)


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
        rows, cols = fr.chars.shape
        prev = self.prev
        full = prev is None or prev.chars.shape != fr.chars.shape or (prev.bg is None) != (fr.bg is None)
        out = ["\x1b[?2026h"]
        if full:
            out.append("\x1b[0m\x1b[2J")
        for y in range(rows):
            ch, fg = fr.chars[y], fr.fg[y]
            bg = fr.bg[y] if fr.bg is not None else None
            if not full and np.array_equal(ch, prev.chars[y]) and np.array_equal(fg, prev.fg[y]) and \
                    (bg is None or np.array_equal(bg, prev.bg[y])):
                continue
            out.append("\x1b[%d;1H\x1b[0m" % (y + 1))
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
        out.append("\x1b[0m")
        if status is not None:
            out.append("\x1b[%d;1H\x1b[0m\x1b[2m%s\x1b[0m\x1b[K" % (rows + 1, status))
        out.append("\x1b[?2026l")
        self.prev = fr
        return "".join(out)


# --------------------------------------------------------------------------- #
# The view: rotation, light, zoom, and the sample grid for a terminal size.
# --------------------------------------------------------------------------- #
class View:
    def __init__(self, model, args):
        self.model = model
        self.material = args.material
        self.mode = args.glyphs
        self.contrast = args.contrast
        self.zoom = 1.0
        self.speed = args.speed
        self.reset()
        self._grid = None

    def reset(self):
        self.R = rot_x(0.22) @ rot_y(-0.5)
        self.light = _unit(DEFAULT_LIGHT)
        self.light_target = self.light.copy()
        self.spin = np.zeros(2)            # inertia, rad/s about view x and y

    def grid(self, cols, rows):
        key = (cols, rows, self.zoom)
        if self._grid is None or self._grid[0] != key:
            m = self.model
            S = self.zoom * 0.94 * min(cols / (2.0 * m.reach_x), 2.0 * rows / (2.0 * (m.reach_y + 1.2)))
            sp = np.array(SAMPLES)
            xs = (np.arange(cols)[None, :, None] + sp[None, None, :, 0]) - cols / 2.0
            ys = rows - (2.0 * np.arange(rows)[:, None, None] + sp[None, None, :, 1])
            xs = np.broadcast_to(xs, (rows, cols, 6)).ravel() / S
            ys = np.broadcast_to(ys, (rows, cols, 6)).ravel() / S
            self._grid = (key, xs, ys, SAMPLE_R * 1.15 / S)
        return self._grid[1:]


def render_cells(rend, glyphs, view, cols, rows):
    xs, ys, fp = view.grid(cols, rows)
    rgb, lum = rend.trace(xs, ys, view.R, view.light, view.material, fp)
    return make_frame(glyphs, rgb, lum, rows, cols, view.mode, view.contrast)


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


def run(view, rend, glyphs, args):
    import termios
    import tty
    fd = sys.stdin.fileno()
    saved = termios.tcgetattr(fd)
    writer = Writer(args.truecolor)
    stop = {"flag": False}
    signal.signal(signal.SIGTERM, lambda *a: stop.update(flag=True))
    tty.setcbreak(fd)
    try:  # Ctrl-Z would leave the alternate screen up; q is the way out
        attrs = termios.tcgetattr(fd)
        attrs[6][termios.VSUSP] = os.fpathconf(fd, "PC_VDISABLE").to_bytes(1, "little")
        termios.tcsetattr(fd, termios.TCSADRAIN, attrs)
    except (OSError, ValueError, termios.error):
        pass
    _write("\x1b[?1049h\x1b[?25l\x1b[?7l\x1b[2J" + MOUSE_ON)
    frame_dt = 1.0 / max(1.0, args.fps)
    paused = False
    light_locked = False
    dragging = False
    last = None             # (x, y, time) of the last drag report
    vel = np.zeros(2)
    released = -10.0
    help_until = time.monotonic() + 6.0
    help_on = True
    buf = ""
    t_prev = time.monotonic()
    fps_est = args.fps
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
                    elif (b & 3) == 0 and not (b & 32) and not rel:
                        dragging, last, vel[:] = True, (x, y, now), 0.0
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
                if k in ("q", "Q", "\x03") or (k == "\x1b" and not buf):
                    stop["flag"] = True
                elif k == " ":
                    paused = not paused
                elif k == "r":
                    view.reset()
                    view.zoom = 1.0
                elif k in ("m", "M"):
                    i = MATERIAL_ORDER.index(view.material) + (1 if k == "m" else -1)
                    view.material = MATERIAL_ORDER[i % len(MATERIAL_ORDER)]
                    help_until = now + 3.0
                elif k == "g":
                    view.mode = GLYPH_MODES[(GLYPH_MODES.index(view.mode) + 1) % len(GLYPH_MODES)]
                    help_until = now + 3.0
                elif k == "l":
                    light_locked = not light_locked
                elif k in ("+", "="):
                    view.speed = min(6.0, view.speed * 1.25 + 0.02)
                elif k in ("-", "_"):
                    view.speed = max(0.0, view.speed / 1.25 - 0.01)
                elif k == "]":
                    view.contrast = min(4.0, view.contrast + 0.25)
                    help_until = now + 3.0
                elif k == "[":
                    view.contrast = max(1.0, view.contrast - 0.25)
                    help_until = now + 3.0
                elif k == "h":
                    help_on = not help_on
                    help_until = now + 600.0 if help_on else now
            if stop["flag"]:
                break

            # ---- motion
            if not dragging:
                if np.any(np.abs(view.spin) > 1e-3):
                    view.R = orthonormal(rot_x(view.spin[0] * dt) @ rot_y(view.spin[1] * dt) @ view.R)
                    view.spin *= math.exp(-dt / 1.3)
                if not paused:
                    blend = min(1.0, max(0.0, (now - released - 0.6) / 1.8))
                    view.R = orthonormal(rot_y(view.speed * dt * blend) @ view.R)
            view.light = _unit(view.light + (view.light_target - view.light) * (1 - math.exp(-dt / 0.12)))

            # ---- draw
            fr = render_cells(rend, glyphs, view, cols, rows)
            status = None
            if help_on and now < help_until:
                status = (" drag spin . hover light . wheel zoom . m %s . g %s . [ ] contrast %.2f . "
                          "space %s . h hide . q quit  %4.0f fps" % (
                              view.material, view.mode, view.contrast, "play" if paused else "pause", fps_est))[:cols - 1]
            elif writer.prev is not None and writer.prev.chars.shape == fr.chars.shape:
                status = ""
            _write(writer.compose(fr, status))

            spent = time.monotonic() - now
            fps_est = 0.9 * fps_est + 0.1 / max(spent, frame_dt, 1e-3)
            wait = frame_dt - spent
            if wait > 0:
                select.select([fd], [], [], wait)
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
        c, _ = rend.trace(xr[s:s + 200000], yr[s:s + 200000], view.R, view.light, view.material, 0.7 / (S * ss))
        rgb[s:s + 200000] = c
    img = rgb.reshape(H, W, 3).reshape(h, ss, w, ss, 3).mean((1, 3))
    bg = np.array([0.055, 0.05, 0.07])
    alpha = np.clip(img.max(2, keepdims=True) * 4, 0, 1)
    write_png(path, img + bg * (1 - alpha))


def trace_logo(path):
    """Trace an ASCII logo into a polygon (needs Pillow, scipy, scikit-image)."""
    try:
        from PIL import Image, ImageDraw, ImageFont
        from scipy import ndimage
        from skimage import measure
    except ImportError as e:
        sys.exit("--logo needs Pillow, scipy and scikit-image (%s)" % e)
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
        im = Image.new("L", (cw, chh), 0)
        if font:
            ImageDraw.Draw(im).text((0, -2), ch, font=font, fill=255)
        else:
            return np.full((chh, cw), 0.0 if ch == " " else 0.6)
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
    pts -= (pts.max(0) + pts.min(0)) / 2
    # drop near-collinear vertices (kinks the coarse art leaves on straight edges)
    while len(pts) > 3:
        u = pts - np.roll(pts, 1, 0)
        v = np.roll(pts, -1, 0) - pts
        turn = np.abs(np.degrees(np.arctan2(u[:, 0] * v[:, 1] - u[:, 1] * v[:, 0], (u * v).sum(1))))
        i = int(turn.argmin())
        if turn[i] >= 14.0:
            break
        pts = np.delete(pts, i, 0)
    area = 0.5 * np.sum(pts[:, 0] * np.roll(pts[:, 1], -1) - np.roll(pts[:, 0], -1) * pts[:, 1])
    return (pts[::-1] if area < 0 else pts).tolist()


def main(argv=None):
    ap = argparse.ArgumentParser(description="A glossy 3D logo, raymarched into ASCII.")
    ap.add_argument("--material", choices=MATERIAL_ORDER, default="lilac")
    ap.add_argument("--glyphs", choices=GLYPH_MODES, default="shape",
                    help="shape: glyphs chosen by shape (sharpest); ramp: density ramp; block: half blocks")
    ap.add_argument("--depth", type=float, default=5.0, help="thickness of the slab, in columns")
    ap.add_argument("--bevel", type=float, default=1.1, help="radius of the rounded rims")
    ap.add_argument("--corner", type=float, default=0.9, help="radius of the outline's corner fillets")
    ap.add_argument("--speed", type=float, default=0.55, help="auto-spin, rad/s")
    ap.add_argument("--contrast", type=float, default=2.25, help="shape mode edge contrast (1-4)")
    ap.add_argument("--charset", choices=list(CHARSETS), default="symbols",
                    help="glyphs the shape mode may use: classic symbols, or all printable ASCII")
    ap.add_argument("--fps", type=float, default=30.0)
    ap.add_argument("--logo", help="an ASCII-art logo file to trace instead of the built-in one")
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

    poly = LOGO_POLY
    if args.logo:
        poly = trace_logo(args.logo)
    model = Model(poly, depth=args.depth, bevel=args.bevel, corner=args.corner)
    rend = Renderer(model)
    glyphs = Glyphs(args.charset)
    view = View(model, args)
    if args.yaw or args.pitch:
        view.R = rot_x(args.pitch) @ rot_y(args.yaw) @ view.R

    if args.png:
        render_png(view, rend, args.png)
        print("wrote", args.png)
        return
    if args.preview or not sys.stdin.isatty() or not sys.stdout.isatty():
        if args.size:
            cols, rows = (int(v) for v in args.size.lower().split("x"))
        else:
            sz = os.get_terminal_size() if sys.stdout.isatty() else os.terminal_size((100, 42))
            cols, rows = sz.columns, sz.lines - 1
        fr = render_cells(rend, glyphs, view, cols, rows)
        sys.stdout.write(_rows_to_text(fr, args.truecolor))
        return
    try:
        run(view, rend, glyphs, args)
    except KeyboardInterrupt:
        pass


def _rows_to_text(fr, truecolor):
    w = Writer(truecolor)
    lines = []
    for y in range(fr.chars.shape[0]):
        one = Frame(fr.chars[y:y + 1], fr.fg[y:y + 1], None if fr.bg is None else fr.bg[y:y + 1])
        w.prev = None
        s = w.compose(one)
        s = re.sub(r"\x1b\[\?2026[hl]|\x1b\[2J|\x1b\[\d+;1H", "", s)
        lines.append(s.rstrip() + "\x1b[0m")
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    main()
