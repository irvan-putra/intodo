#!/bin/sh
# Verify that a review-canvas publish is actually LIVE, not merely pushed.
#
# Usage: verify_live.sh PAGE_URL DIST_DIR [TIMEOUT_SECONDS]
#
# A push to gh-pages only starts a Pages deployment; the old page keeps
# being served until it finishes. This script polls the public page until
# its index.html and referenced fingerprinted assets are byte-identical to
# DIST_DIR - an HTTP 200 alone is never enough (a stale or error page can
# return 200). Exits
# non-zero on timeout. Cache-busting query params defeat the CDN/browser
# cache during polling.
set -e

URL="$1"
DIST="$2"
TIMEOUT="${3:-240}"

JS="$(basename "$DIST"/app.*.js 2>/dev/null | head -1)"
CSS="$(basename "$DIST"/app.*.css 2>/dev/null | head -1)"
case "$JS" in app.*.js) : ;; *) echo "verify_live: no fingerprinted app.*.js in $DIST" >&2; exit 1 ;; esac
case "$CSS" in app.*.css) : ;; *) echo "verify_live: no fingerprinted app.*.css in $DIST" >&2; exit 1 ;; esac

# Build sanity: the built index must reference exactly these asset files,
# so a name mismatch is caught here rather than after deploy.
grep -q "\./$JS" "$DIST/index.html" || { echo "verify_live: $DIST/index.html does not reference ./$JS" >&2; exit 1; }
grep -q "\./$CSS" "$DIST/index.html" || { echo "verify_live: $DIST/index.html does not reference ./$CSS" >&2; exit 1; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

DEADLINE=$(( $(date +%s) + TIMEOUT ))
TRY=0
# every wait/fetch is capped by the remaining budget so the script never
# overshoots TIMEOUT by more than a single in-flight second
remaining() { echo $(( DEADLINE - $(date +%s) )); }
cap() { R="$(remaining)"; [ "$R" -gt "$1" ] && R="$1"; [ "$R" -lt 1 ] && R=1; echo "$R"; }
while [ "$(remaining)" -gt 0 ]; do
    TRY=$(( TRY + 1 ))
    rm -f "$TMP/live.index"
    curl -fsS --max-time "$(cap 20)" -o "$TMP/live.index" "$URL?cb=$TRY" 2>/dev/null || true
    if cmp -s "$TMP/live.index" "$DIST/index.html"; then
        if curl -fsS --max-time "$(cap 20)" -o "$TMP/live.js" "$URL$JS?cb=$TRY" 2>/dev/null \
           && curl -fsS --max-time "$(cap 20)" -o "$TMP/live.css" "$URL$CSS?cb=$TRY" 2>/dev/null; then
            if cmp -s "$TMP/live.js" "$DIST/$JS" && cmp -s "$TMP/live.css" "$DIST/$CSS"; then
                echo "verify_live: LIVE - $URL serves the exact index, $JS and $CSS bytes from the build after ${TRY} poll(s)"
                exit 0
            fi
            echo "verify_live: assets fetch with 200 but bytes differ from the build; still waiting"
        else
            echo "verify_live: index matches but one or more assets do not fetch cleanly yet; still waiting"
        fi
    else
        echo "verify_live: page index differs from the built index; still waiting"
    fi
    [ "$(remaining)" -gt 0 ] && sleep "$(cap 15)"
done

echo "verify_live: NOT LIVE after ${TIMEOUT}s - the push happened but the deployment did not go live" >&2
exit 1
