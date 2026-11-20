# Browser-local tasks

A usable task list, separate from the historical review canvas and DeltaChat mini-app.
The deployed route is `/intodo/tasks/`. No account, server-side task database,
telemetry, phone pairing, feed key or automatic cross-device sync is included.
Do not put private tasks into this repository. Tasks entered in the page stay in
that browser's storage. The public page ships no personal task data.

## What it does

- Create lists and tasks, edit title/note/date, check and reopen tasks.
- Archive and restore, never permanently delete tasks from the UI.
- Keep fictional Demo tasks in a separate in-memory workspace.
- Download a JSON backup, then import it as extra lists after a preview.
- Reject invalid backups and preserve unreadable stored bytes rather than silently
  replacing them. Report failed writes instead of claiming they were saved.
- Notice other-tab changes and refuse to overwrite a task from a stale open editor.

Dates are labels, not timed alerts. Browser storage is not encrypted by this app.
Backups contain task text, unencrypted. Clearing browser data, private-browsing
session expiry or browser eviction can lose local tasks. Keep backup files private.
This first page is not an installed/offline PWA and does not claim reliable loading
without a network connection. After loading, task operations use no server.

The stable storage key and backup format are independent of the displayed app
name. Existing Android storage, package identity, pairing, DeltaChat instances,
review layouts and review marks are not read or changed.

## Check

`node --test browser-tasks/model.test.mjs`

The focused synthetic browser harness in `browser-tasks/ui-check.mjs` tests the
actual page in headless Chrome, checks backup download/import, storage failure
paths, stale edits and phone-width layouts. It creates fictional tasks only.

## Publish

The existing `publish review canvas` workflow stages the unchanged gh-pages site,
then the existing review bundle and historical shared-list simulation, then only
these browser assets under `tasks/`. It refuses preexisting changes outside those
additive paths. Existing review and root pages stay at their current URLs.
Naming and any future sync transport require their own decisions.
