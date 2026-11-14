# tools/release - checked review-canvas publication

Small scripts that make publishing the itdweb review canvas to GitHub
Pages repeatable and safe. They encode the two lessons from manual
publication mistakes:

1. **Check the repository before pushing.** Publishing from a checkout
   whose `origin` is a local review clone instead of GitHub must fail
   before anything is committed.
2. **Pushed is not live.** A push to `gh-pages` only starts a Pages
   deployment; the old page is served until it finishes. Always confirm
   what the public URL actually serves.

Normally you do not run these by hand: `.github/workflows/review-pages.yml`
builds, stages, publishes and live-verifies on every push to `main` that
touches `itdweb/review/`. The workflow is split in two for security: the
`build` job (contents: read only) runs dependency install with
`npm ci --ignore-scripts` (locked to `package-lock.json`) and esbuild, so
no dependency lifecycle script ever runs in a privileged job; the `deploy`
job (pages: write + id-token: write) only deploys the uploaded artifact
and verifies the live bytes. The scripts stay usable standalone for a
manual publish or for testing.

## stage_pages.sh DIST_DIR PAGES_CHECKOUT

Replaces `PAGES_CHECKOUT/review/` with the built bundle in `DIST_DIR`
(from `itdweb/review/build.sh`), then refuses to proceed unless:

- the checkout's `origin` is the intodo GitHub repository (a local path
  or any other remote is rejected), and
- the pending changes touch only `review/` - the demo page at the branch
  root, `photos/`, and everything else on gh-pages must be untouched.

## verify_live.sh PAGE_URL DIST_DIR [TIMEOUT_SECONDS]

Polls `PAGE_URL` until its `index.html` is byte-identical to the build (which also proves the exact fingerprinted `app.<hash>.js` / `app.<hash>.css` references), and the fetched assets are byte-identical too. An HTTP 200 alone is never enough. Exits non-zero on timeout, so a failed job means "pushed but not live", never silent success.

## Tests

`tools/release/test_release_scripts.sh` exercises both scripts offline:
staging into a local-origin or same-named-fork checkout is rejected, a
wrong branch or a pre-dirty tree is rejected, changes outside `review/`
are rejected, a clean stage is accepted and stale files inside `review/`
are replaced, and `verify_live.sh` passes only for the exact index + asset bytes, fails on
an index that merely mentions the new asset names, times out on an old page,

None of these scripts use or store credentials, tokens or secrets.
Publishing uses the workflow's own `GITHUB_TOKEN`; a manual run uses the
operator's existing git login.
