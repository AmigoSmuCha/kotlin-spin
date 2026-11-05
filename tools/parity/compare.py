"""Frame-for-frame check of the Java port against aphex_spin.py --preview (run via tools/parity/run.sh)."""
import re, subprocess, sys
SGR = re.compile(r"\x1b\[([0-9;]*)m")

def parse_py(text, cols, rows):
    grid = []
    for line in text.split("\n")[:rows]:
        fg = bg = None
        cells = []
        i = 0
        while i < len(line):
            m = SGR.match(line, i)
            if m:
                ps = [int(x) for x in m.group(1).split(";") if x != ""] or [0]
                j = 0
                while j < len(ps):
                    p = ps[j]
                    if p == 0: fg = None; bg = None; j += 1
                    elif p == 38 and ps[j+1] == 2: fg = (ps[j+2] << 16) | (ps[j+3] << 8) | ps[j+4]; j += 5
                    elif p == 48 and ps[j+1] == 2: bg = (ps[j+2] << 16) | (ps[j+3] << 8) | ps[j+4]; j += 5
                    else: j += 1
                i = m.end(); continue
            cells.append((line[i], fg, bg)); i += 1
        while len(cells) < cols: cells.append((" ", None, None))
        grid.append(cells[:cols])
    return grid

def run(cols, rows, pal, mat, mode, charset, backdrop, yaw=0.0, pitch=0.0):
    args = ["python3", "aphex_spin.py", "--preview", "--size", "%dx%d" % (cols, rows), "--palette", pal,
            "--material", mat, "--glyphs", mode, "--charset", charset, "--workers", "0", "--yaw", str(yaw), "--pitch", str(pitch)]
    if backdrop: args.append("--backdrop")
    py = subprocess.run(args, capture_output=True, text=True).stdout
    G = parse_py(py, cols, rows)
    jv = subprocess.run(["java", "-cp", "build/parity", "Parity", "frame", str(cols), str(rows), pal, mat, mode, charset,
                         "1" if backdrop else "0", str(yaw), str(pitch)], capture_output=True, text=True).stdout.split("\n")
    tot = dch = dfg = 0
    maxd = 0
    for r in range(rows):
        for c in range(cols):
            ch, f1, b1 = jv[r * cols + c].split()
            ch, f1, b1 = chr(int(ch)), int(f1), int(b1)
            pc, pf, pb = G[r][c]
            if mode == "block":
                tot += 1
                # python: fg = top, bg = bottom (ground cells come as the base bg)
                for jvv, pyv in ((f1, pf), (b1, pb)):
                    if pyv is None: pyv = 0
                    d = max(abs(((jvv >> s) & 255) - ((pyv >> s) & 255)) for s in (0, 8, 16))
                    maxd = max(maxd, d)
                    if d > 4: dfg += 1
                continue
            if ch == " " and pc == " ": continue
            tot += 1
            if ch != pc: dch += 1
            elif ch != " " and pf is not None and f1 >= 0:
                d = max(abs(((f1 >> s) & 255) - ((pf >> s) & 255)) for s in (0, 8, 16))
                maxd = max(maxd, d)
                if d > 4: dfg += 1
    return tot, dch, dfg, maxd

cases = [
    (100, 42, "aphex", "chrome", "shape", "all", False),
    (100, 42, "axel clay", "chrome", "shape", "all", False),
    (100, 42, "nord", "gloss", "shape", "all", False),
    (100, 42, "miasma", "satin", "shape", "symbols", False),
    (100, 42, "gruvbox", "gold", "ramp", "all", False),
    (100, 42, "dracula", "obsidian", "shape", "all", True),
    (100, 42, "graphite", "bone", "block", "all", False),
    (44, 39, "catppuccin", "chrome", "shape", "all", True),
    (44, 39, "integral lab", "gloss", "shape", "all", False),
]
if len(sys.argv) > 1: cases = cases[:int(sys.argv[1])]
for cs in cases:
    for yaw, pitch in ((0.0, 0.0), (1.1, 0.3)):
        tot, dch, dfg, maxd = run(*cs, yaw=yaw, pitch=pitch)
        print("%-58s yaw %.1f  cells %5d  glyph diff %4d (%.2f%%)  colour diff>4: %d  max channel diff %d" % (
            " ".join(str(x) for x in cs), yaw, tot, dch, 100.0 * dch / max(1, tot), dfg, maxd))
