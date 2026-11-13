#!/bin/sh
# Build the static review canvas for GitHub Pages.
# Usage: sh itdweb/review/build.sh [outdir]   (default: itdweb/review/dist)
# Then copy the outdir contents into a review/ directory on the gh-pages
# branch. The page expects to be served at <site>/intodo/review/ next to
# the existing photos/ directory (it reuses ../photos/review-concept-hero.png).
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="${1:-$HERE/dist}"
mkdir -p "$OUT"
cd "$HERE"
if [ ! -d node_modules ]; then
  npm install --no-fund --no-audit
fi
./node_modules/.bin/esbuild main.tsx --bundle --outfile="$OUT/app.js" --jsx=automatic --minify
cp index.html "$OUT/index.html"
# Content-fingerprint the JS/CSS asset names and rewrite index.html to match,
# so a new release is picked up immediately instead of waiting out the
# browser cache (GitHub Pages serves these with max-age=600).
JS_HASH=$(md5sum "$OUT/app.js" | cut -c1-8)
CSS_HASH=$(md5sum "$OUT/app.css" | cut -c1-8)
mv "$OUT/app.js" "$OUT/app.$JS_HASH.js"
mv "$OUT/app.css" "$OUT/app.$CSS_HASH.css"
sed -i "s|\./app\.js|./app.$JS_HASH.js|; s|\./app\.css|./app.$CSS_HASH.css|" "$OUT/index.html"
echo "Built: $OUT (index.html, app.$JS_HASH.js, app.$CSS_HASH.css)"
