#!/bin/sh
# Stage the task app at the project root and preserve both review pages.
set -eu
SRC="$1"
PAGES="$2"
[ -d "$PAGES/.git" ] || exit 1
case "$(git -C "$PAGES" remote get-url origin)" in
 https://github.com/irvan-putra/tindak|https://github.com/irvan-putra/tindak.git|git@github.com:irvan-putra/tindak.git) ;;
 *) echo 'Unexpected Pages origin' >&2; exit 1 ;;
esac
BRANCH="$(git -C "$PAGES" symbolic-ref --short -q HEAD || true)"
[ -z "$BRANCH" ] || [ "$BRANCH" = gh-pages ] || exit 1
OUTSIDE="$(git -C "$PAGES" status --porcelain | awk '{print $2}' | grep -Ev '^(code-review/|shared-list-simulation/|tasks/)' || true)"
[ -z "$OUTSIDE" ] || { echo 'Unexpected pending page changes' >&2; exit 1; }
# The original full code-review canvas is built under code-review/ first.
[ -f "$PAGES/code-review/index.html" ] || exit 1
mkdir -p "$PAGES/review"
# Only the known prototype files; no private/generated project folders.
for n in index.html app.js style.css; do cp "$SRC/review-prototype/$n" "$PAGES/review/$n"; done
cp "$SRC/browser-tasks/index.html" "$PAGES/index.html"
echo 'Tasks homepage staged; prototype review and code-review both preserved.'
