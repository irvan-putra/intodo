# Separate-install smoke candidate

`-PphoneSmoke` builds `com.vanipy.intodo`, labeled Intodo, version
`0.5.11-smoke.1` (26), with the `0.5.11 S1` icon. It is unconfigured.
The default build identity and existing release workflow are unchanged.
Do not combine this property with `emuAppId`.

The `phone-smoke-candidate` workflow runs on pushes to the dedicated
`qualification/intodo-phone-smoke` branch (and supports manual dispatch
when registered by GitHub). Review before pushing: the push starts a build.
It runs host tests, builds the
application release APK, signs with the retained key and compares its
verified certificate with the public v0.5.10 release. Missing signing
secrets or a mismatch fail the job. It has no release/tag/push step and
uploads only the candidate APK, checksum and source provenance. Review
the signed result before distribution. Never distribute an instrumentation
APK or substitute an automatically generated debug key.

This is only an empty, separate-install trial: check installation, icon,
empty main screen and bot setup screen. Do not pair, import an account,
create an account, start the bot, or change the existing app or its data.
Keep the existing app installed. No account or migration safety claim is
made. There is no network sandbox: the app retains its normal features
and permissions. The old release updater is not a test-candidate channel.

The icon generator requires Pillow and DejaVu Sans Bold. Generated PNGs
are checked in so the build needs neither dependency.
