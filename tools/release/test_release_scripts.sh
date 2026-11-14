#!/bin/sh
# Offline tests for stage_pages.sh and verify_live.sh. Uses only local
# temp repos and a local HTTP stub; no network beyond 127.0.0.1, no
# credentials. Run: sh tools/release/test_release_scripts.sh
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
PASS=0

ok() { PASS=$(( PASS + 1 )); echo "ok $PASS - $1"; }
fail() { echo "FAIL - $1" >&2; exit 1; }

# --- fixture: a fake build output -------------------------------------
DIST="$WORK/dist"
mkdir -p "$DIST"
echo '<script src="./app.deadbeef.js"></script><link href="./app.cafebabe.css">' > "$DIST/index.html"
echo 'js' > "$DIST/app.deadbeef.js"
echo 'css' > "$DIST/app.cafebabe.css"

# --- fixture: a gh-pages-like checkout --------------------------------
PAGES="$WORK/pages"
mkdir -p "$PAGES/review" "$PAGES/photos"
cd "$PAGES"
git init -q
git config user.email test@example.com
git config user.name test
echo 'demo' > index.html
echo 'old' > review/index.html
git add -A
git commit -qm base
git checkout -qb gh-pages

# 1. staging into a checkout with a LOCAL origin must be refused
git remote add origin "$WORK/local-review-clone"
if sh "$HERE/stage_pages.sh" "$DIST" "$PAGES" 2>"$WORK/err"; then
    fail "local-origin checkout was accepted"
fi
grep -q 'not exactly irvan-putra/intodo' "$WORK/err" || fail "local-origin rejection message missing"
ok "staging refused when origin is a local clone"

# 2. staging works with the GitHub origin and touches only review/
git remote set-url origin https://github.com/irvan-putra/intodo.git
sh "$HERE/stage_pages.sh" "$DIST" "$PAGES" >/dev/null || fail "clean stage rejected"
[ -f "$PAGES/review/app.deadbeef.js" ] || fail "bundle not staged"
git -C "$PAGES" status --porcelain | awk '{print $2}' | grep -v '^review/' | grep . && fail "unexpected change outside review/"
ok "clean stage accepted, changes confined to review/"
git -C "$PAGES" add -A && git -C "$PAGES" commit -qm staged

# 3. a dirty change outside review/ must be refused (pre-staging guard)
git -C "$PAGES" checkout -q -- .
echo 'tampered' >> "$PAGES/index.html"
if sh "$HERE/stage_pages.sh" "$DIST" "$PAGES" 2>"$WORK/err2"; then
    fail "stage with root-demo change was accepted"
fi
grep -q 'uncommitted changes before staging' "$WORK/err2" || fail "pre-dirty rejection message missing"
ok "staging refused when the root demo would change"
git -C "$PAGES" checkout -q -- .

# 4a. a same-named FORK origin must be refused
git -C "$PAGES" remote set-url origin git@github.com:someone-else/intodo.git
if sh "$HERE/stage_pages.sh" "$DIST" "$PAGES" 2>"$WORK/err3"; then
    fail "fork origin was accepted"
fi
grep -q 'not exactly irvan-putra/intodo' "$WORK/err3" || fail "fork rejection message missing"
ok "staging refused when origin is a same-named fork"
git -C "$PAGES" remote set-url origin https://github.com/irvan-putra/intodo.git

# 4b. the https URL without .git suffix is also accepted
git -C "$PAGES" remote set-url origin https://github.com/irvan-putra/intodo
sh "$HERE/stage_pages.sh" "$DIST" "$PAGES" >/dev/null || fail "https bare origin rejected"
git -C "$PAGES" add -A && git -C "$PAGES" commit -qm staged || true
git -C "$PAGES" remote set-url origin https://github.com/irvan-putra/intodo.git
ok "the official https origin without .git suffix accepted"

# 4c. a checkout on the wrong branch must be refused
git -C "$PAGES" checkout -qb main
if sh "$HERE/stage_pages.sh" "$DIST" "$PAGES" 2>"$WORK/err4"; then
    fail "main-branch checkout was accepted"
fi
grep -q "expected gh-pages" "$WORK/err4" || fail "wrong-branch rejection message missing"
git -C "$PAGES" checkout -q --detach
git -C "$PAGES" branch -qD main 2>/dev/null || true
ok "staging refused on a branch other than gh-pages"

# 4e. stale files inside review/ are replaced (deletion is expected and safe)
sh "$HERE/stage_pages.sh" "$DIST" "$PAGES" >/dev/null || fail "clean stage rejected (4e setup)"
git -C "$PAGES" add -A && git -C "$PAGES" commit -qm stage1 || true
echo 'stale' > "$PAGES/review/stale.txt"
git -C "$PAGES" add -A && git -C "$PAGES" commit -qm stale
sh "$HERE/stage_pages.sh" "$DIST" "$PAGES" >/dev/null || fail "re-stage over stale review/ rejected"
[ ! -e "$PAGES/review/stale.txt" ] || fail "stale file inside review/ survived staging"
ok "staging replaces stale content inside review/ (deletion allowed there)"
git -C "$PAGES" checkout -q -- .

# 5. verify_live passes when the stub serves the new build
SITE="$WORK/site"
mkdir -p "$SITE"
cp "$DIST"/* "$SITE"/
PORT="$(python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')"
( cd "$SITE" && python3 -m http.server $PORT >/dev/null 2>&1 ) &
STUB=$!
sleep 1
sh "$HERE/verify_live.sh" "http://127.0.0.1:$PORT/" "$DIST" 60 >/dev/null || { kill $STUB; fail "verify_live rejected a live stub"; }
kill $STUB 2>/dev/null || true
ok "verify_live passes when the site serves the new build"

# 6. verify_live fails (distinct from pushed) when the site still serves the old build
OLD="$WORK/oldsite"
mkdir -p "$OLD"
echo '<script src="./app.00000000.js"></script>' > "$OLD/index.html"
( cd "$OLD" && python3 -m http.server $PORT >/dev/null 2>&1 ) &
STUB=$!
sleep 1
if sh "$HERE/verify_live.sh" "http://127.0.0.1:$PORT/" "$DIST" 2 >/dev/null; then
    kill $STUB; fail "verify_live accepted a stale page"
fi
kill $STUB 2>/dev/null || true
ok "verify_live times out when the site still serves the old build"

# 7. verify_live refuses a 200 whose bytes differ from the build
WRONG="$WORK/wrongsite"
mkdir -p "$WRONG"
cp "$DIST/index.html" "$WRONG/index.html"
echo 'different js' > "$WRONG/app.deadbeef.js"
cp "$DIST/app.cafebabe.css" "$WRONG/app.cafebabe.css"
( cd "$WRONG" && python3 -m http.server $PORT >/dev/null 2>&1 ) &
STUB=$!
sleep 1
if sh "$HERE/verify_live.sh" "http://127.0.0.1:$PORT/" "$DIST" 2 >/dev/null; then
    kill $STUB; fail "verify_live accepted a 200 with wrong asset bytes"
fi
kill $STUB 2>/dev/null || true
ok "verify_live refuses byte-mismatched assets even at 200"

# 8. verify_live rejects an index that mentions the exact asset names but is
# not byte-identical to the build (for example a mixed or stale index).
MIXED="$WORK/mixedsite"
mkdir -p "$MIXED"
cp "$DIST"/* "$MIXED"/
printf '\n<!-- stale or mixed index -->\n' >> "$MIXED/index.html"
( cd "$MIXED" && python3 -m http.server $PORT >/dev/null 2>&1 ) &
STUB=$!
sleep 1
if sh "$HERE/verify_live.sh" "http://127.0.0.1:$PORT/" "$DIST" 2 >/dev/null; then
    kill $STUB; fail "verify_live accepted an index with matching names but nonmatching bytes"
fi
kill $STUB 2>/dev/null || true
ok "verify_live refuses an index that only mentions the built asset names"

echo "all $PASS release-script tests passed"
