# Aphex Chrome — live wallpaper

An inflated chrome logo, raymarched and drawn in ASCII, as an Android live
wallpaper. It's a Java port of `aphex_spin.py` (included): the same balloon,
sky, sparkles, nine palettes and six finishes. It's checked frame for frame
against the script's `--preview`, with the same glyph and the same colour in
every cell. No libraries; the APK is about 1 MB.

Colours follow the focus / cornholio Design System. Each palette is closed,
with every tone derived from its six roles, and a change of palette
cross-fades the whole picture in 0.9 s. Notes are short, lowercase and leave
on their own.

## Gestures

Everything takes two or three fingers, so the launcher's one-finger swipes
(pages, the app drawer) never move the model.

| Gesture | Does |
|---|---|
| two-finger drag | spin it, let go mid-swing and it keeps the momentum; the sun follows your fingers |
| pinch | zoom |
| tap with two fingers | next palette |
| tap with three fingers | next finish |
| hold two fingers still | back to the start |

## Getting it on the phone

1. Create a new GitHub repository and push everything in this folder,
   including the hidden `.github` folder, to its top level.
2. GitHub builds the app on its own: watch the **Actions** tab (3–4 minutes).
3. On the phone, open the repository's **Releases** page, download
   `AphexChrome.apk` and open it.
4. Open **Aphex Chrome** from the app drawer and tap **wallpaper · set it**.
   Choose home, or home and lock screen.

Every push to `main` builds a new release, which installs over the old one.
It lives alongside the first wallpaper (Aphex Spin); they are separate apps.

## Settings

One plate under a live preview (drag the preview with one finger). Tap a row
to go forward, hold it to go back. Every change shows at once and is saved.

palette · finish · glyphs (shape, ramp, blocks) · letters (all, symbols) ·
sky behind · sparkles · ground · size (characters across) · contrast · spin ·
puff (how far the balloon is inflated) · frame rate · battery saver ·
clock (always, lock screen only, off) · clock place · time zone (the phone's,
or fixed utc+1).

The wallpaper draws nothing while it's hidden or the screen is off. It sleeps
when nothing moves, runs at your frame rate while it spins, and at 45 fps at
least while you touch it.

## Files

- `app/src/main/java/com/aphexspin/chrome/`
  - `Palette`, `EnvMap`, `Balloon`, `Tracer`, `Ascii`, `Scene`: the renderer, pure Java
  - `Runner`, `Painter`, `ChromeWallpaper`, `PreviewView`, `SettingsActivity`: the Android side
- `app/src/main/assets/balloon.bin`: the balloon's inflation field (the script's
  Poisson solve, baked so the phone doesn't have to run it)
- `tools/bake_balloon.py`: rebakes it, also for another logo:
  `python3 tools/bake_balloon.py aphex_spin.py app/src/main/assets/balloon.bin logo.png`
- `tools/parity/run.sh`: checks the Java renderer against `aphex_spin.py --preview`
- `keystore/aphexchrome.jks`: the signing key, kept here so updates install over each other
