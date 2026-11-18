# Real and Demo feeds

This development branch adds an explicit feed-mode control to the drawer. It does not create, publish, delete or take a feed offline. The two HTTPS feed addresses are configured on the phone once. No personal endpoints ship in source or builds.

- Before setup, the drawer labels the current saved feed without guessing its role. A labeled synthetic cache can prefill the demo address. The user confirms whether the previously saved feed was demo before saving both addresses.
- The existing pairing key is shared, unchanged. The feeds must be encrypted for that key. Configuration does not generate a key or clear shared lists, bot accounts, or other app data.
- Real and demo feed addresses, cache, generation/freshness, last check and error state are separate. The original cache is copied into a recovery slot during first setup. Reassigning an address clears only that address's active cache; the original migration recovery slot remains until explicit re-pairing.
- Switching blanks old visible rows and widget snapshots before selecting the other cache. It then fetches the selected feed. An offline demo with no cache stays empty rather than falling back to real items.
- Demo accepts only feeds whose sections start with `DEMO - ` and item IDs start with `demo-`. These markers catch accidentally choosing a real feed. They are not authentication, content classification, or permission to share the feed.
- Demo has a visible list banner and widget-row prefix. The bot and review-canvas shortcuts are hidden in Demo. Returning to real items requires confirmation because app and widget data will become visible.
- In-flight responses are bound to an immutable mode and epoch. Mode changes, address changes and re-pairing invalidate old responses, including a switch away and back. Background sync always reads the selected feed and cannot merge one mode into the other.
- App update download/session preferences and pairing credentials stay global. Re-pairing still needs the existing explicit warning and clears both feed caches to avoid using content from a previous key.

## Verification

`DualFeedTest` covers legacy real/demo migration, cache and freshness separation, in-flight epoch invalidation, stale-response scope, accidental real-cache refusal, legacy RemoteViews factory isolation and endpoint changes. `DualFeedRenderTest` covers the Demo transition, no-cache privacy state, synthetic list, hidden private shortcuts, return confirmation and setup dialog, with synthetic data only. Existing widget layout/RemoteViews regressions run alongside these tests.

Qualification screenshots and unsigned debug builds are test evidence, not a signed release or proof on a particular physical phone. Signing and publication require separate approval.
