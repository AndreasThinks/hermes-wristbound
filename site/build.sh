#!/bin/sh
# Assemble the website into _site/ from site/ and the shared assets elsewhere in the repo,
# so the watch screenshots, the Hermes girl and the pixel font have one source each.
set -eu
root=$(cd "$(dirname "$0")/.." && pwd)
out="${1:-$root/_site}"
rm -rf "$out"
mkdir -p "$out/assets/screens"
cp "$root/site/index.html" "$root/site/style.css" "$out/"
cp "$root/site/wave.svg" "$out/assets/wave.svg"
cp "$root/android/app/src/main/res/drawable-nodpi/hermes_girl.png" "$out/assets/hermes-girl.png"
cp "$root/watch/resources/fonts/Silkscreen-Regular.ttf" "$root/watch/resources/fonts/OFL.txt" "$out/assets/"
cp "$root/docs/screenshots/"*.png "$out/assets/screens/"
touch "$out/.nojekyll"
echo "Built $out"
