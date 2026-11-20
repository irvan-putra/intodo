#!/bin/sh
# Stage the additive shared-list simulation under its existing Pages site.
# Usage: stage-demo-pages.sh SOURCE_DIR GH_PAGES_CHECKOUT
set -e
SRC="$1"
PAGES="$2"
[ -f "$SRC/index.html" ] || { echo "stage-demo-pages: no source index.html" >&2; exit 1; }
[ -d "$PAGES/.git" ] || { echo "stage-demo-pages: not a git checkout" >&2; exit 1; }
ORIGIN="$(git -C "$PAGES" remote get-url origin 2>/dev/null || true)"
case "$ORIGIN" in
  https://github.com/irvan-putra/tindak|https://github.com/irvan-putra/tindak.git|git@github.com:irvan-putra/tindak.git) ;;
  *) echo "stage-demo-pages: refusing - unexpected origin: ${ORIGIN:-none}" >&2; exit 1 ;;
esac
BRANCH="$(git -C "$PAGES" symbolic-ref --short -q HEAD || true)"
if [ -n "$BRANCH" ] && [ "$BRANCH" != gh-pages ]; then
  echo "stage-demo-pages: refusing - expected gh-pages, got $BRANCH" >&2; exit 1
fi
# This step follows stage_pages.sh, which may already have changed review/.
# No preexisting change outside review/ is allowed.
OUTSIDE_BEFORE="$(git -C "$PAGES" status --porcelain | awk '{print $2}' | grep -v '^code-review/' || true)"
[ -z "$OUTSIDE_BEFORE" ] || { echo "stage-demo-pages: refusing preexisting changes outside review/:" >&2; echo "$OUTSIDE_BEFORE" >&2; exit 1; }
mkdir -p "$PAGES/shared-list-simulation"
cp -R "$SRC"/. "$PAGES/shared-list-simulation/"
OUTSIDE_AFTER="$(git -C "$PAGES" status --porcelain | awk '{print $2}' | grep -Ev '^(code-review/|shared-list-simulation/)' || true)"
[ -z "$OUTSIDE_AFTER" ] || { echo "stage-demo-pages: refused changes outside approved paths:" >&2; echo "$OUTSIDE_AFTER" >&2; exit 1; }
echo "stage-demo-pages: staged additive simulation under shared-list-simulation/; review/ preserved"
