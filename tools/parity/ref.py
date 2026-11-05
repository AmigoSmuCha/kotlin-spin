"""Reference frames from cornholio.py itself: its AudioTap playing the demo song,
its NCS scene drawing, under a fake clock. Output: one JSON object per frame."""
import importlib.util, json, sys, time, types

spec = importlib.util.spec_from_file_location("ch", "cornholio.py")
ch = importlib.util.module_from_spec(spec)
sys.modules["ch"] = ch
spec.loader.exec_module(ch)

CLOCK = [1000.0]
ch.time = types.SimpleNamespace(monotonic=lambda: CLOCK[0], time=time.time, sleep=time.sleep,
                                perf_counter=time.perf_counter)
# the pure-Python FFT (what a numpy-less cornholio runs): the port mirrors it exactly
_orig = ch.Spectrum.__init__
def _init(self, n):
    _orig(self, n)
    self.np = None
ch.Spectrum.__init__ = _init


def run(visual="bars", W=48, H=42, frames=120, theme="default", top=None, dt=1 / 30, dump=True, title="", finger=False):
    ch.C.__init__(theme)
    tap = ch.AudioTap({}, demo=True)
    ncs = ch.NCS(tap)
    cv = ch.Canvas(W, H, ch.C.bg)
    mask = [[1.0] * W for _ in range(H)]
    if top is None:
        top = H - 2 - int(H * 0.42) + 1
    plan = types.SimpleNamespace(label_y=top - 2, bar_y=None, dots_y=None, note_y=None, vis_top=top)
    out = []
    trail = ch.Trail()
    import math
    for f in range(frames):
        CLOCK[0] += dt
        now = CLOCK[0]
        tap.tick(now, True, dt)
        cv.reset(ch.C.bg)
        wake = {}
        if finger:
            if 20 <= f < 80 or 110 <= f < 140:
                trail.feed(int(W / 2 + math.cos(f * 0.15) * W * 0.35), int(H / 2 + math.sin(f * 0.15) * H * 0.3), now)
            wake = trail.grid(now, ch.fx_level(W, H))
        t = title if f < frames // 2 else ("Řeka Ůsvitů" if title else "")
        ncs.draw(cv, now, dt, 0.0, True, True, t, plan, wake, mask, None, set(), None, visual, None, None)
        fr = tap.frame()
        rec = {"f": f, "levels": fr.levels, "raw": fr.raw, "peaks": fr.peaks, "energy": fr.energy,
               "bass": fr.bass, "db": fr.db, "silent": fr.silent, "beats": fr.beats, "hits": fr.hits}
        if dump:
            rec["ch"] = "".join(c if c else "\0" for c in cv.ch)
            rec["fg"] = ["%02x%02x%02x" % c for c in cv.fg]
            rec["bg"] = ["%02x%02x%02x" % c for c in cv.bg]
            rec["bold"] = "".join("1" if b else "0" for b in cv.bold)
        out.append(rec)
    return out


if __name__ == "__main__":
    a = sys.argv[1:]
    res = run(visual=a[0], W=int(a[1]), H=int(a[2]), frames=int(a[3]), theme=a[4] if len(a) > 4 else "default",
              title=a[5] if len(a) > 5 else "", finger=len(a) > 6 and a[6] == "1")
    for r in res:
        print(json.dumps(r))
