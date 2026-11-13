#!/bin/sh
# Package the .xdc (a zip). Dev/test files are NOT included.
# test/package.test.mjs enforces that every script referenced by the HTML
# entry points is in this list and that versions are coherent - keep both
# in sync when adding a file. Known-fixed omission: digest.js/seedfix.js/
# restoregen.js were missing from a previous list while index.html
# referenced them.
set -e
cd "$(dirname "$0")"
VERSION=$(sed -n 's/^version = "\(.*\)"/\1/p' manifest.toml)
test -n "$VERSION" || { echo "no version in manifest.toml" >&2; exit 1; }
rm -f "itdxdc-v${VERSION}.xdc"
zip -X "itdxdc-v${VERSION}.xdc" \
  manifest.toml icon.png index.html canvas.html \
  js/mergedoc.js js/canvas-math.js js/canvas.js \
  js/digest.js js/seedfix.js js/restoregen.js js/app.js js/webxdc-mock.js \
  >/dev/null
unzip -l "itdxdc-v${VERSION}.xdc"
