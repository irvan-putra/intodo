# Instinct Widget (Android)

A home-screen widget and companion app that shows my Instinct reminders and todos on my phone. The list syncs through an encrypted page that only the paired phone can read.

## Status

Test build. v0.1-test failed its first on-device sync ("payload not found on page"); v0.2-test fixed the cause; v0.3-test moved the feed URL out of the source into build config; sync and decryption are confirmed working on a real phone. v0.4-test fixes the home-screen widget layout at narrow widths (header stayed usable, list starts at the top); that fix is verified by code review, not yet on a phone.

## How it works

- The app pairs once: it creates an EC P-256 key pair on the phone and shows the public key. The private key never leaves the phone.
- Instinct publishes the current reminders and todos to a link-public page, encrypted to that public key (ECDH + HKDF-SHA256 + AES-256-GCM, see `tools/encrypt_feed.py`). Anyone with the link can fetch the page, but the content is ciphertext.
- The app loads the page in a hidden WebView, pulls the encrypted payload out of the page bundle, decrypts it on-device, and displays it in the app and the home-screen widget.
- Every publish is stamped with a fresh UTC generation time; the phone rejects a feed that is not strictly newer (replay/stale protection).
- Sync is manual in this version: tap Sync in the app or the refresh button on the widget.

## Configuration

The personal feed URL is **not in this repository**. The app reads it at build time from `local.properties` (gitignored):

```
FEED_URL=https://your-own-encrypted-feed-page
```

Building without it works fine - the app installs and reports "feed URL not configured" on sync. The APK the author runs on his phone is built from this exact source with his own endpoint set; that endpoint is the only difference from a public-source build.

## Layout

- `app/` - the Android app (plain Java, minSdk 26). `SyncEngine` fetches the feed, `Crypto` decrypts it, `FeedParser` parses it, `WidgetProvider`/`WidgetService` render the home-screen widget.
- `tools/encrypt_feed.py` - the publisher side: encrypts a feed JSON to the phone's public key and stamps the generation time.

## Build

Requires JDK 17, Android SDK (platform 34), and Gradle 8.5.

```
gradle assembleDebug
```

The debug APK lands in `app/build/outputs/apk/debug/`. It is debug-signed; installing a newer versionCode over an older one keeps app data (pairing key and cached list). No APK binary is published in this repository: the shipped APK embeds the author's personal endpoint, so get the app from the author, not from here.

## Known limits

- Debug-signed test build; not Play-store hardened. No ProGuard, no release signing.
- Sync is manual only; no background polling or push.
- If a feed cannot be parsed at all, the freshness check cannot prove staleness (tracked for a future fix).
- The link-public feed page is ciphertext, but its existence and update cadence are visible to anyone who has the link.
- Sync/decrypt confirmed on a real Android phone (v0.3-test); the v0.4-test narrow-width widget layout fix is unconfirmed on-device. Public source aids inspection; it is not by itself a security guarantee.

## Privacy

- The app never sends list data anywhere; the only network traffic is fetching the encrypted page through the public viewer flow (files.instinct.com, api.instinct.com, *.instinctusercontent.com).
- No reminders, todos, pairing keys, or personal endpoints are in this repository.
