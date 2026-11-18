# intodo

Personal reminder/todo tooling: **intodo for Android** (Android app + home-screen widget, `app/`), **intodo for DeltaChat** (webxdc shared-list mini-app for DeltaChat, `webxdc/`), and **intodo for web** (hosted review-canvas page source, `itdweb/`).

## intodo - Android widget

A home-screen widget and companion app that shows my Instinct reminders and todos on my phone. The list syncs through an encrypted page that only the paired phone can read.

## Status

Active test builds, published on this repository's Releases page. Per-version changes are in [CHANGELOG.md](CHANGELOG.md).

- **intodo for Android**: current source prepares **v0.5.12**, a fresh-start build using `com.vanipy.intodo`. Source preparation is not a published release. See Releases for actual APK availability. Public builds are unconfigured; enter your feed address after pairing.
- **intodo for DeltaChat**: latest release **v0.4.7** (`.xdc` attachment for DeltaChat).
- **intodo for web**: review canvas published through GitHub Pages Actions.
- Branch `unfinished/log-access-v3` holds a partial, unfinished step in the wider log-access work: selectable bot-log text, nested scrolling that preserves vertical drags, and opening at the newest lines. It has been compiled but not run on a device, and is not part of any release - see `UNFINISHED-log-access-v3.md` on that branch.

## Android identity and fresh setup

Current source uses `com.vanipy.intodo` for the application ID, namespace and Android source packages. Display names stay unchanged. It is separate from old `com.instinct.widget` installs, whose source remains in Git history. No export/import bridge or automatic data migration is added. Existing background feed work is updated to the current worker class without clearing app data. A home-screen widget added from the earlier new-ID candidate may need to be removed from the home screen and added again because its provider class name changed; the old app's widgets are separate and unchanged. Recreate an existing new-ID launcher shortcut as well if the launcher retains its old activity component. This does not require clearing app data. Keep an old installation and its data unless you choose otherwise.

A first installation starts empty. If `com.vanipy.intodo` is already installed (including the earlier side-by-side candidate), a same-signer update keeps that installation's existing data; it does not reset it. Do not clear data or uninstall as part of setup. Create a pairing key only if the app is unpaired, share only its public key with your feed publisher, enter the resulting encrypted feed address and sync. The optional bot connection is set up separately; never run two copies against the same bot account.

The existing release naming (`itdapk-v<version>.apk`) and in-app update path remain. The installer checks this app's package name and signing certificate before offering Android installation. Older-ID apps refuse a new-ID APK rather than replacing themselves.

## What is verified

Released code and phone-proven behavior are not the same thing; this section records which is which. "Phone-verified" means the behavior has run on a real phone.

- Feed sync, HTTPS fetch and on-device decryption: phone-verified since v0.3-test.
- App and widget list view: phone-verified.
- Background sync (30-minute WorkManager schedule, since v0.5.0): one scheduled-interval test passed on a phone; long-term reliability unverified.
- Sync footer showing last sync and last list change (v0.5.1): phone-verified.
- Download/list-sync progress and resume (v0.5.4): covered by automated tests; not separately phone-verified.
- Embedded DeltaChat bot runtime: basic operation phone-verified with its own bot account; the publish and restore flows are implemented and covered by tests.
- On-device witness for bot observations (v0.5.3): off by default; not yet exercised in a real group.
- In-app update check/install: not yet confirmed end-to-end on a phone.
- `tools/dc_relay` reply ledger: covered by unit tests; not yet proven against a live DeltaChat account.
- itdxdc: local test harnesses and render checks only; not tested on a phone.

## How it works

- The app pairs once: it creates an EC P-256 key pair on the phone and shows the public key. The private key never leaves the phone.
- A feed producer publishes the current reminders and todos to a link-public page, encrypted to that public key (ECDH + HKDF-SHA256 + AES-256-GCM, see `tools/encrypt_feed.py`). Anyone with the link can fetch the page, but the content is ciphertext.
- The app loads the page in a hidden WebView, pulls the encrypted payload out of the page bundle, decrypts it on-device, and displays it in the app and the home-screen widget.
- Every publish is stamped with a fresh UTC generation time; the phone rejects a feed that is not strictly newer (replay/stale protection).
- Sync runs on a 30-minute background schedule (WorkManager) and on demand: tap Sync in the app or the refresh button on the widget.
- The app also embeds a Python DeltaChat bot runtime (`app/src/main/python`) that can publish the list to DeltaChat chats and serve list commands. It runs under its own bot account.

## Configuration

The personal feed URL is **not in this repository**. The app can read it at build time from `local.properties` (gitignored):

```
FEED_URL=https://your-own-encrypted-feed-page
```

Building without it works fine - the release APKs are unconfigured public builds of this source and let you enter a feed address in the app after install. A configured build differs only by the endpoint setting.

## Layout

- `app/` - the Android app (plain Java, minSdk 26). `SyncEngine` fetches the feed, `Crypto` decrypts it, `FeedParser` parses it, `WidgetProvider`/`WidgetService` render the home-screen widget. `app/src/main/python/` holds the embedded DeltaChat bot runtime.
- `webxdc/` - itdxdc, the webxdc shared-list mini-app for DeltaChat groups (`package.sh` builds the `.xdc`).
- `itdweb/` - authored source of the review-canvas page (React + TypeScript), published through GitHub Pages.
- `tools/encrypt_feed.py` - the publisher side: encrypts a feed JSON to the phone's public key and stamps the generation time. `tools/encrypt_feed.mjs` is the Node twin.
- `tools/dc_relay/` - durable reply ledger library for bots (duplicate-safe replies across crashes and restarts), with tests.
- `tools/feed-consistency/` - generic consistency checks comparing a published projection against its source list.

## Build

Requires JDK 17, Android SDK (platform 34), and Gradle 8.5.

```
gradle assembleDebug
```

The debug APK lands in `app/build/outputs/apk/debug/` and is debug-signed. Public, unconfigured release APKs are attached to each GitHub release; they are signed with the project keystore (same signer across releases), so an update with the same package ID keeps that installation's data (pairing key and cached list). The package-ID change to `com.vanipy.intodo` is a separate install from the older ID, not an in-place upgrade.

## Known limits

- Test build; not Play-store hardened. No ProGuard.
- Background sync runs at roughly 30-minute intervals set by WorkManager; Android may delay or skip runs. Manual sync is still available in the app and on the widget.
- If a feed cannot be parsed at all, the freshness check cannot prove staleness (tracked for a future fix).
- The link-public feed page is ciphertext, but its existence and update cadence are visible to anyone who has the link.
- Public source aids inspection; it is not by itself a security guarantee. See "What is verified" for which behaviors have actually run on a phone.

## Privacy

- Feed sync only fetches the encrypted page; the app does not upload list data for sync. When the optional embedded bot is started, it publishes list data to the DeltaChat chats it serves, through its own bot account.
- No reminders, todos, pairing keys, or personal endpoints are in this repository.

## Browser simulation demos

- [Two-phone shared-list simulation](https://vanipy.com/intodo/shared-list-simulation/) - a browser-local, invented-data demo of the original merge engine. It does not connect to DeltaChat or any real phone. Its controls do not generate a same-field conflict; see the limitations on the page and in the [source README](demos-shared-list-simulation/README.md). The archive download is historical v0.4, not a current release or an upgrade to an existing list.



- [Can a webxdc app use the Web Crypto API (`crypto.subtle`)?](docs/research/webxdc-secure-context-and-web-crypto.md) - source reading across clients; runtime behavior remains unverified.

- [GitHub as storage has limits](docs/research/github-storage-limits.md) - a brief on current repository-size and bandwidth policies, not a blanket endorsement of GitHub for encrypted storage.
- [Cloudflare Free plan and a long-lived chat bot](docs/research/cloudflare-free-plan-long-lived-bot.md) - current documented limits and open questions; nothing deployed or tested.
- [Free hosting for an always-on chat bot](docs/research/free-hosting-for-an-always-on-chat-bot.md) - dated vendor-page comparison; no candidate deployed or tested end to end.
- [Third-party licence notices for an Android app that bundles Python and a native Rust library](docs/research/third-party-notices-for-android-python-and-native-rpc.md) - a dated checklist, not a release inventory or legal minimum.
- [Upgrading a webxdc app and its state](docs/research/upgrading-a-webxdc-app-and-its-state.md) - forum reports and open questions; no Delta Chat version tested.

## Family naming and compatibility

Current display name across Android, webxdc and web is **intodo**. Android remains `com.vanipy.intodo`. Existing storage keys, web paths, instance IDs and published download files are preserved. New downloads use `intodo-v<version>` with the previous filename as a byte-identical compatibility alias where needed. Existing bot accounts are not recreated or restarted by a source rename. Source changes are not a new signed release.
