#!/bin/sh
# Checks the app's scene against cornholio.py, frame for frame: the same glyph,
# ink, ground and boldness in every cell, the same levels, beats and hits, for
# every visualizer, with a finger's wake and a song change.
# Needs python3 (with numpy for diffision) and a JDK. Run from anywhere: sh tools/parity/run.sh [look ...]
set -e
cd "$(dirname "$0")/../.."
mkdir -p build/parity
S=app/src/main/java/com/cornholio/ncs
javac -nowarn -d build/parity $S/AudioTap.java $S/Cells.java $S/Diffision.java $S/Dust.java $S/GlyphField.java \
    $S/Kerosene.java $S/Lorn.java $S/Mirror.java $S/Ncs.java $S/NcsBars.java $S/PyRandom.java $S/Spectrum.java \
    $S/Stitch.java $S/Theme.java $S/Trail.java tools/parity/Run.java
python3 tools/parity/compare.py "$@"
