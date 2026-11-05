#!/usr/bin/env python3
"""Bake the balloon's field for the Android app.

    python3 tools/bake_balloon.py aphex_spin.py app/src/main/assets/balloon.bin [logo.png]

Runs aphex_spin.py's own Model once (the Poisson solve, then cached) and
writes phi, the inflation field, as little-endian float32 with a small header.
The app builds the surface, its gradients and the empty-space bounds from it,
so a different logo (or puff) needs only this file.
"""
import importlib.util
import struct
import sys

import numpy as np


def load(path):
    spec = importlib.util.spec_from_file_location("aphex_spin", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def main():
    src, out = sys.argv[1], sys.argv[2]
    a = load(src)
    poly = a.trace_logo(sys.argv[3]) if len(sys.argv) > 3 else a.LOGO_POLY
    m = a.Model(poly)
    D, PHI = m._fields()          # from the script's cache: float32, as the script itself uses
    P = np.asarray(poly, float)
    ext = np.abs(P).max(0)
    maxr2 = float((P ** 2).sum(1).max())
    with open(out, "wb") as f:
        f.write(b"BLN1")
        f.write(struct.pack("<ii", m.nx, m.ny))
        f.write(struct.pack("<dddddd", m.x0, m.y0, m.res, float(ext[0]), float(ext[1]), maxr2))
        f.write(np.ascontiguousarray(PHI, dtype="<f4").tobytes())
    print("wrote %s: %d x %d, hmax %.3f" % (out, m.nx, m.ny, m.hmax))


if __name__ == "__main__":
    main()
