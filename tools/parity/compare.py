"""Frame-for-frame check of the app's scene against cornholio.py itself: the same glyph, ink,
ground and boldness in every cell, the same levels, beats and hits. Run through run.sh."""
import json, sys, subprocess
def load(cmd):
    out = subprocess.run(cmd, capture_output=True, text=True)
    if out.returncode != 0: print(out.stderr[-3000:]); sys.exit(1)
    return [json.loads(l) for l in out.stdout.strip().split("\n") if l.startswith("{")]
def compare(visual, W=48, H=42, frames=150, theme="default", show_first=True, title="", finger=False):
    extra = [title, "1" if finger else "0"]
    py = load(["python3", "tools/parity/ref.py", visual, str(W), str(H), str(frames), theme] + extra)
    jv = load(["java", "-cp", "build/parity", "Run", visual, str(W), str(H), str(frames), theme] + extra)
    first_bad = None; tot = 0; bad_ch = bad_fg = bad_bg = bad_bold = 0; lvl = 0.0; beatdiff = 0
    for p, j in zip(py, jv):
        lvl = max(lvl, max(abs(a - b) for a, b in zip(p["levels"], j["levels"])))
        if p["beats"] != j["beats"] or p["hits"] != j["hits"]: beatdiff += 1
        n = W * H
        for i in range(n):
            tot += 1
            c1, c2 = p["ch"][i], j["ch"][i]
            e = False
            if c1 != c2: bad_ch += 1; e = True
            elif c1 != " " and p["fg"][i] != j["fg"][i]: bad_fg += 1; e = True
            if p["bg"][i] != j["bg"][i]: bad_bg += 1; e = True
            if p["bold"][i] != j["bold"][i]: bad_bold += 1; e = True
            if e and first_bad is None: first_bad = (p["f"], i % W, i // W, c1, c2, p["fg"][i], j["fg"][i], p["bg"][i], j["bg"][i])
    print("%-10s %-14s %dx%d %d frames: cells %d  glyph diff %d  ink diff %d  ground diff %d  bold diff %d  | max level diff %.2e  frames with other beat/hit counts %d"
          % (visual, theme, W, H, frames, tot, bad_ch, bad_fg, bad_bg, bad_bold, lvl, beatdiff))
    if first_bad and show_first: print("   first difference: frame %d at (%d, %d): %r vs %r  fg %s vs %s  bg %s vs %s" % first_bad)
if __name__ == "__main__":
    looks = sys.argv[1:] or ["bars", "mirror", "kerosene", "lorn", "diffision"]
    for v in looks:
        compare(v)
    for v in looks:
        compare(v, 60, 52, 160, "phosphor", title="Mezi Mosty", finger=True)
