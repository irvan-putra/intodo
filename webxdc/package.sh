#!/bin/sh
# Package the .xdc (a zip). Dev/test files are NOT included.
set -e
cd "$(dirname "$0")"
rm -f shared-list.xdc
zip -X shared-list.xdc manifest.toml icon.png index.html js/mergedoc.js js/app.js js/webxdc-mock.js >/dev/null
unzip -l shared-list.xdc
