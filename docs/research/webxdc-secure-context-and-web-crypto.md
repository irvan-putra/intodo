# Can a webxdc app use the Web Crypto API (crypto.subtle)?

Status: pages read 2026-11-16. Documentation and source reading only; no webxdc app was run. The answer differs by client, and a source read does not prove runtime behaviour.

## What the sources show
- `crypto.subtle` is "available only in secure contexts (HTTPS), in some or all supporting browsers". https://developer.mozilla.org/en-US/docs/Web/API/Crypto/subtle
- MDN lists what makes an origin potentially trustworthy, including a scheme of `https`, and a host of `localhost` or ending with `.localhost`. https://developer.mozilla.org/en-US/docs/Web/Security/Secure_Contexts
- Desktop, historically: the webxdc docs issue lists "Things that require secure context don't work on Desktop (e.g. Geolocation, Device Orientation, Web Crypto API)". A participant there says "`crypto` works on iOS, so it is really implementation specific" (an attributed statement; iOS was not checked here). https://github.com/webxdc/webxdc_docs/issues/66
- Desktop, change: deltachat-desktop pull request #3413, "webxdc: make `isSecureContext === true`", merged 2023-09-27. Which releases contain it was not checked, so no version cutoff is claimed. https://github.com/deltachat/deltachat-desktop/pull/3413
- Android: in deltachat-android's `WebxdcActivity.java` (main branch), the app's base URL is built as `"https://acc" + accountId + "-msg" + appMessageId + ".localhost"`. Combined with the MDN origin list this points to a secure context on Android, but the build in a user's hands was not tested. https://raw.githubusercontent.com/deltachat/deltachat-android/main/src/main/java/org/thoughtcrime/securesms/WebxdcActivity.java

## For an app that depends on it
Feature-detect the specific operation needed (for example `globalThis.crypto?.subtle`) before relying on it. If security depends on that operation, fail closed and mark the feature unsupported on that client rather than substituting something else. A non-security checksum is a separate question and is not addressed here.
