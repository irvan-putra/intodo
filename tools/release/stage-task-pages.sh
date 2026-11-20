#!/bin/sh
# Add only the browser task assets after the existing review/demo staging.
set -eu
SRC="$1"
PAGES="$2"
[ -f "$SRC/index.html" ] || { echo 'Missing task-page source' >&2; exit 1; }
[ -d "$PAGES/.git" ] || { echo 'Expected a Pages checkout' >&2; exit 1; }
ORIGIN="$(git -C "$PAGES" remote get-url origin)"
case "$ORIGIN" in
 https://github.com/irvan-putra/intodo|https://github.com/irvan-putra/intodo.git|git@github.com:irvan-putra/intodo.git) ;;
 *) echo 'Unexpected Pages origin' >&2; exit 1 ;;
esac
BRANCH="$(git -C "$PAGES" symbolic-ref --short -q HEAD || true)"
[ -z "$BRANCH" ] || [ "$BRANCH" = gh-pages ] || { echo 'Expected gh-pages' >&2; exit 1; }
OUTSIDE="$(git -C "$PAGES" status --porcelain | awk '{print $2}' | grep -Ev '^(code-review/|shared-list-simulation/|tasks/)' || true)"
[ -z "$OUTSIDE" ] || { echo 'Refusing changes outside approved web directories' >&2; echo "$OUTSIDE" >&2; exit 1; }
mkdir -p "$PAGES/tasks"
for name in index.html style.css app.mjs model.mjs; do cp "$SRC/$name" "$PAGES/tasks/$name"; done
# Deliberately no recursive copy: tests, screenshots and backups are not site assets.
echo 'Staged the task page under tasks/; root and review pages preserved.'
