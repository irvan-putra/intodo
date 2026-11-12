#!/bin/sh
# Package the .xdc (a zip). Dev/test files are NOT included.
set -e
cd "$(dirname "$0")"
rm -f itdxdc-v0.3.xdc
zip -X itdxdc-v0.3.xdc manifest.toml icon.png index.html js/mergedoc.js js/app.js js/webxdc-mock.js >/dev/null
unzip -l itdxdc-v0.3.xdc
