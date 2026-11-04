# Aphex Spin — live wallpaper

A glossy 3D logo, raymarched and drawn in ASCII, as an Android live wallpaper.
It's a Java port of `aphex_spin.py`: same model, lighting and shape-matched
glyphs. No libraries, about 1 MB.

## Gestures

Everything uses two or three fingers, so the launcher's one-finger swipes
(pages, app drawer) never move the model.

| Gesture | Does |
|---|---|
| two-finger drag | spin it (let go mid-swing and it keeps the momentum); the light follows your fingers |
| pinch | zoom |
| two-finger tap | next material (lilac, obsidian, chrome, bone, acid, ember) |
| three-finger tap | reset |

## Getting it on the phone

1. Create a new GitHub repository and upload everything in this folder,
   including the hidden `.github` folder. (If the web uploader skips it, use
   *Add file → Create new file*, name it `.github/workflows/build.yml` and paste
   the file's contents in.)
2. GitHub builds the app automatically: see the **Actions** tab (about 3–4 minutes).
3. On the phone, open the repo's **Releases** page, download `AphexSpin.apk`
   and open it. Allow your browser to install unknown apps when asked.
4. Open **Aphex Spin** from the app drawer → **Set as wallpaper**, and choose
   home screen, or home and lock screen.

Each push to `main` builds a new release, and it installs over the old one.

## Settings

Material, glyph style, characters across the screen, contrast, auto-spin speed,
frame rate, a battery saver (auto-spin stops 30 s after you last touched it or
unlocked), and the clock: always, lock screen only, or off, at the top or
bottom. The clock follows the phone's time zone; tick *Fixed UTC+1* to pin it
to UTC+1 all year.

The wallpaper draws nothing while it's hidden or the screen is off, and it
sleeps when nothing is moving.

## Files

- `app/src/main/java/.../AsciiRenderer.java`: the renderer (pure Java, testable on a PC)
- `SpinWallpaper.java`: the wallpaper service, gestures and clock
- `SettingsActivity.java`: the settings screen
- `keystore/aphexspin.jks`: the signing key, kept here so updates install over each other
