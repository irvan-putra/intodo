# Changelog

Per-version changes, newest first. "Phone-verified" means the behavior has
run on a real phone; anything not marked that way is verified only by build,
tests, code review or local harnesses. See README.md "What is verified".

## itdapk (Android app + home-screen widget)

- **v0.5.12 (source preparation, not yet released)** - Use `com.vanipy.intodo` consistently for the current Android application ID, namespace and source/test packages. Fresh-start install beside the old app, no migration or reset. Keep existing app names, versioned downloads and same-package/signer update checks. Update active CI selectors/source generators and the icon version. Refresh persisted background sync to the renamed worker without resetting its schedule. Existing home-screen widgets from the earlier new-ID candidate may need re-adding because their provider class changed. Previous source remains in Git history.

- **v0.5.7** - Fix widget host inflation and list copying.
- **v0.5.6** - List view fills the app and the widget.
- **v0.5.5** - Remove "Instinct" wording from visible app copy.
- **v0.5.4** - Download and list-sync progress with byte counts; interrupted
  downloads resume from partial data (covered by automated tests).
- **v0.5.3** - On-device witness for bot observations: an independent bot can
  compare what arrived in a DeltaChat group with what the publisher claims.
  Off by default; not yet exercised in a real group.
- **v0.5.2** - Launcher icon version badge update.
- **v0.5.1** - Sync footer shows last sync and last list change, and surfaces
  failures (phone-verified). Bot display-name rename.
- **v0.5.0** - 30-minute periodic background feed sync (WorkManager); one
  scheduled-interval test passed on a phone, long-term reliability unverified.
- **v0.4.9** - Compact bot controls and status.
- **v0.4.8** - Bot publish and restore flow in the app.
- **v0.4.7** - r12 bot runtime, app-side publishing, ownerless trust model.
- **v0.4.3 - v0.4.6** - Iterated bot runtime, publishing and widget fixes;
  see git history.
- **v0.4-test and earlier** - Initial pairing, encrypted feed sync and widget
  layout; see git history.

## itdxdc (webxdc shared list for DeltaChat)

- **v0.4.7** - Icon version badge correction. Separate app instance from
  0.4.6; existing instances and their edit histories are unchanged.
- **v0.4.6** - Fix chat notice links so tapping a notice opens the webxdc app
  entry instead of requesting a missing root path.
- **v0.4.5** - Seed timestamp ordering fix; safe restore and render checks.
  Verified with local test harnesses and render checks; not tested on a phone.

## Tools and review site (not versioned releases)

- `tools/dc_relay`: durable reply ledger library (unit-tested) so bot replies
  are not duplicated across crashes and restarts.
- `tools/feed-consistency`: generic checker comparing a published projection
  against its source list.
- `tools/encrypt_feed.mjs`: Node twin of the Python feed encryptor.
- itdweb review canvas: C4 views (context/container/component, dynamic and
  deployment), Diataxis labels, published through GitHub Pages Actions.
- Removed the unused Turso storage adapter.
