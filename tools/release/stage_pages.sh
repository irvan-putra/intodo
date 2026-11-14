#!/bin/sh
# Stage a freshly built review-canvas bundle into a gh-pages checkout.
#
# Usage: stage_pages.sh DIST_DIR PAGES_CHECKOUT
#
# Replaces PAGES_CHECKOUT/review/ with the contents of DIST_DIR, then
# verifies - before anything is committed, uploaded or deployed - that the
# pending changes touch ONLY the review/ directory. If anything outside
# review/ would change (the demo page at the branch root, photos/, CNAME,
# anything), the script fails and leaves the checkout dirty for inspection
# instead of letting a bad publish through.
#
# Guards, all before staging is considered valid:
#   - origin must be exactly the irvan-putra/intodo GitHub repo - a local
#     review clone, a fork, or any other remote is refused;
#   - a checkout on a branch must be on gh-pages (a detached CI HEAD is
#     accepted; the caller verified the tip it checked out);
#   - the tree must be clean before staging.
set -e

DIST="$1"
PAGES="$2"

if [ ! -f "$DIST/index.html" ]; then
    echo "stage_pages: $DIST does not look like a build output (no index.html)" >&2
    exit 1
fi
if [ ! -d "$PAGES/.git" ]; then
    echo "stage_pages: $PAGES is not a git checkout" >&2
    exit 1
fi

# Exact official repo only - never a local clone or a same-named fork.
ORIGIN="$(git -C "$PAGES" remote get-url origin 2>/dev/null || true)"
case "$ORIGIN" in
    https://github.com/irvan-putra/intodo|https://github.com/irvan-putra/intodo.git|git@github.com:irvan-putra/intodo.git) ;;
    *)
        echo "stage_pages: refusing - origin is not exactly irvan-putra/intodo on GitHub (origin: ${ORIGIN:-none})" >&2
        exit 1
        ;;
esac

BRANCH="$(git -C "$PAGES" symbolic-ref --short -q HEAD || true)"
if [ -n "$BRANCH" ] && [ "$BRANCH" != "gh-pages" ]; then
    echo "stage_pages: refusing - checkout is on branch '$BRANCH', expected gh-pages" >&2
    exit 1
fi

if [ -n "$(git -C "$PAGES" status --porcelain)" ]; then
    echo "stage_pages: refusing - checkout has uncommitted changes before staging:" >&2
    git -C "$PAGES" status --porcelain >&2
    exit 1
fi

rm -rf "$PAGES/review"
mkdir -p "$PAGES/review"
cp -R "$DIST"/. "$PAGES/review/"

# Nothing outside review/ may change; deletions and renames inside
# review/ are expected (the directory is fully replaced).
OUTSIDE="$(git -C "$PAGES" status --porcelain | awk '{print $2}' | grep -v '^review/' || true)"
if [ -n "$OUTSIDE" ]; then
    echo "stage_pages: refusing to publish - changes outside review/ detected:" >&2
    echo "$OUTSIDE" >&2
    exit 1
fi

echo "stage_pages: staged $(git -C "$PAGES" status --porcelain | wc -l | tr -d ' ') changed path(s), all under review/"
