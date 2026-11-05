#!/bin/sh
# Checks the app's renderer against aphex_spin.py, frame for frame: the same
# glyph and the same colour in every cell, across palettes, finishes and modes.
# Needs python3 with numpy, and a JDK. Run from anywhere: sh tools/parity/run.sh
set -e
cd "$(dirname "$0")/../.."
mkdir -p build/parity
S=app/src/main/java/com/aphexspin/chrome
javac -d build/parity $S/Palette.java $S/EnvMap.java $S/Balloon.java $S/Tracer.java $S/Ascii.java $S/Frame.java \
    $S/Scene.java $S/Data.java tools/parity/Debug.java tools/parity/Parity.java
python3 aphex_spin.py --preview --size 20x8 > /dev/null    # builds the script's own balloon cache once
python3 tools/parity/compare.py "$@"
