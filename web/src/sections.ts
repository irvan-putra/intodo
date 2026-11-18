// Generated from irvan-putra/intodo @ 0740caf by gen_sections.py - do not hand-edit.
export const COMMIT = "0740caff0bb50c174b061875ad1136882a9017ab";
export const COMMIT_SHORT = "0740caf";
export type SectionFile = { path: string; lines: number | null; note?: string };
export type Section = { id: string; title: string; tag: string; icon: string; purpose: string; link: string; digest: string; totalLines: number; files: SectionFile[]; notChecked: string[] };
export const SECTIONS: Section[] = [
  {
    "id": "list-ui",
    "title": "List screen & widget",
    "tag": "Android app - UI",
    "icon": "📱",
    "purpose": "What you see on the phone: the reminder list in the app and the home-screen widget.",
    "link": "app/src/main",
    "files": [
      {
        "path": "app/src/main/java/com/vanipy/intodo/MainActivity.java",
        "lines": 364
      },
      {
        "path": "app/src/main/java/com/vanipy/intodo/WidgetProvider.java",
        "lines": 105
      },
      {
        "path": "app/src/main/java/com/vanipy/intodo/WidgetService.java",
        "lines": 72
      },
      {
        "path": "app/src/main/res/layout/activity_main.xml",
        "lines": 87
      },
      {
        "path": "app/src/main/res/layout/widget_layout.xml",
        "lines": 23
      },
      {
        "path": "app/src/main/res/layout/list_item.xml",
        "lines": 9
      },
      {
        "path": "app/src/main/res/layout/list_section.xml",
        "lines": 5
      },
      {
        "path": "app/src/main/res/layout/widget_item.xml",
        "lines": 9
      },
      {
        "path": "app/src/main/res/layout/widget_section.xml",
        "lines": 5
      },
      {
        "path": "app/src/main/res/values/styles.xml",
        "lines": 9
      },
      {
        "path": "app/src/main/res/xml/widget_info.xml",
        "lines": 12
      },
      {
        "path": "app/src/main/res/drawable/ic_refresh.xml",
        "lines": 6
      },
      {
        "path": "app/src/main/AndroidManifest.xml",
        "lines": 46
      }
    ],
    "notChecked": [
      "TalkBack screen-reader pass over the list",
      "Very large font sizes beyond the 1.3x spot checks"
    ],
    "digest": "6f5c95342153",
    "totalLines": 752
  },
  {
    "id": "feed-sync",
    "title": "Feed sync & decryption",
    "tag": "Android app - sync",
    "icon": "🔄",
    "purpose": "Fetches the hosted feed, decrypts it with the paired key, and turns it into list items.",
    "link": "app/src/main/java/com/vanipy/intodo",
    "files": [
      {
        "path": "app/src/main/java/com/vanipy/intodo/SyncEngine.java",
        "lines": 287
      },
      {
        "path": "app/src/main/java/com/vanipy/intodo/FeedParser.java",
        "lines": 46
      },
      {
        "path": "app/src/main/java/com/vanipy/intodo/Crypto.java",
        "lines": 79
      },
      {
        "path": "app/src/main/java/com/vanipy/intodo/FeedSyncWorker.java",
        "lines": 46
      },
      {
        "path": "app/src/main/java/com/vanipy/intodo/FeedSyncScheduler.java",
        "lines": 36
      },
      {
        "path": "app/src/main/java/com/vanipy/intodo/Prefs.java",
        "lines": 51
      },
      {
        "path": "app/src/main/java/com/vanipy/intodo/Freshness.java",
        "lines": 26
      },
      {
        "path": "app/src/main/java/com/vanipy/intodo/ProgressPump.java",
        "lines": 36
      },
      {
        "path": "app/src/main/java/com/vanipy/intodo/SyncStatusText.java",
        "lines": 61
      },
      {
        "path": "app/src/main/java/com/vanipy/intodo/InstallStatusReceiver.java",
        "lines": 47
      }
    ],
    "notChecked": [
      "Malformed or hostile feed handling beyond the parser tests",
      "Battery cost of the 30-minute schedule over a full week"
    ],
    "digest": "67e8edfb6edd",
    "totalLines": 715
  },
  {
    "id": "self-update",
    "title": "Self-update & resume",
    "tag": "Android app - updates",
    "icon": "⬇️",
    "purpose": "Checks GitHub for a newer APK, downloads it with resume after interruptions, and installs it.",
    "link": "app/src/main/java/com/vanipy/intodo",
    "files": [
      {
        "path": "app/src/main/java/com/vanipy/intodo/UpdateCheck.java",
        "lines": 113
      },
      {
        "path": "app/src/main/java/com/vanipy/intodo/UpdateInstall.java",
        "lines": 220
      },
      {
        "path": "app/src/main/java/com/vanipy/intodo/net/Resumable.java",
        "lines": 486
      }
    ],
    "notChecked": [
      "Interrupted-download recovery on your own phone (JVM-tested, not device-proven)",
      "The Samsung installer-permission prompt flow end to end"
    ],
    "digest": "23ed55433b81",
    "totalLines": 819
  },
  {
    "id": "native-canvas",
    "title": "Review canvas (native)",
    "tag": "Android app - canvas",
    "icon": "🖼️",
    "purpose": "The in-app canvas view. Shares its math with the webxdc canvas below.",
    "link": "app/src/main/java/com/vanipy/intodo/canvas",
    "files": [
      {
        "path": "app/src/main/java/com/vanipy/intodo/canvas/ReviewCanvasView.java",
        "lines": 637
      },
      {
        "path": "app/src/main/java/com/vanipy/intodo/canvas/CanvasMath.java",
        "lines": 234
      },
      {
        "path": "app/src/main/java/com/vanipy/intodo/canvas/CanvasContent.java",
        "lines": 107
      },
      {
        "path": "app/src/main/java/com/vanipy/intodo/canvas/ReviewCanvasActivity.java",
        "lines": 60
      }
    ],
    "notChecked": [
      "Rendered output on a real device or emulator (compiles for API 34, never drawn)",
      "Visual parity with this page"
    ],
    "digest": "a1c4475d46ca",
    "totalLines": 1038
  },
  {
    "id": "merge-core",
    "title": "Merge core (Android)",
    "tag": "Shared list - CRDT",
    "icon": "🧬",
    "purpose": "How two devices combine list edits without losing items.",
    "link": "app/src/main/java/com/vanipy/intodo/merge",
    "files": [
      {
        "path": "app/src/main/java/com/vanipy/intodo/merge/MergeDoc.java",
        "lines": 248
      }
    ],
    "notChecked": [
      "Independent re-run of the reported property tests (7 Java + 9 JS, run by the builders)"
    ],
    "digest": "0d6c9e3747ef",
    "totalLines": 248
  },
  {
    "id": "bot-app",
    "title": "Bot screens (Java/Kotlin)",
    "tag": "DeltaChat bot - host UI",
    "icon": "🤖",
    "purpose": "The bot's control screens inside the app: logs, publish dialogs, fetch progress.",
    "link": "app/src/main/java/com/vanipy/intodo/bot",
    "files": [
      {
        "path": "app/src/main/java/com/vanipy/intodo/bot/BotActivity.java",
        "lines": 854
      },
      {
        "path": "app/src/main/java/com/vanipy/intodo/bot/BotService.java",
        "lines": 101
      },
      {
        "path": "app/src/main/java/com/vanipy/intodo/bot/XdcFetch.java",
        "lines": 231
      },
      {
        "path": "app/src/main/java/com/vanipy/intodo/bot/BotImport.kt",
        "lines": 135
      },
      {
        "path": "app/src/main/java/com/vanipy/intodo/bot/BootReceiver.java",
        "lines": 31
      },
      {
        "path": "app/src/main/java/com/vanipy/intodo/bot/PublishErrors.java",
        "lines": 27
      },
      {
        "path": "app/src/main/java/com/vanipy/intodo/bot/DisplayNames.java",
        "lines": 12
      }
    ],
    "notChecked": [
      "Publish error dialogs on a fresh account with no keys configured"
    ],
    "digest": "788e2f719755",
    "totalLines": 1391
  },
  {
    "id": "bot-runtime",
    "title": "Bot runtime (Python)",
    "tag": "DeltaChat bot - core",
    "icon": "🐍",
    "purpose": "Message handling, list operations, and the restore/recovery protocol.",
    "link": "app/src/main/python",
    "files": [
      {
        "path": "app/src/main/python/bot.py",
        "lines": 400
      },
      {
        "path": "app/src/main/python/bot_runtime.py",
        "lines": 1042
      }
    ],
    "notChecked": [
      "Runtime rollback across the r13/v2 boundary with mixed history (known compatibility gap)"
    ],
    "digest": "5303b0c68690",
    "totalLines": 1442
  },
  {
    "id": "bot-delivery",
    "title": "Delivery & storage",
    "tag": "DeltaChat bot - data",
    "icon": "📦",
    "purpose": "Shipping updated mini-apps to the bot, plus where the bot keeps its data.",
    "link": "app/src/main/python",
    "files": [
      {
        "path": "app/src/main/python/delivery_bot.py",
        "lines": 1044
      },
      {
        "path": "app/src/main/python/ops_store.py",
        "lines": 357
      },
      {
        "path": "app/src/main/python/migration.py",
        "lines": 337
      },
      {
        "path": "app/src/main/python/digest-fixtures.json",
        "lines": 336
      }
    ],
    "notChecked": [],
    "digest": "f170ab67208a",
    "totalLines": 2074
  },
  {
    "id": "witness",
    "title": "Witness & validation",
    "tag": "DeltaChat bot - proof",
    "icon": "👁️",
    "purpose": "Independent confirmation that group messages actually land on other devices.",
    "link": "app/src/main/python",
    "files": [
      {
        "path": "app/src/main/python/witness.py",
        "lines": 724
      },
      {
        "path": "app/src/main/python/bot_validation.py",
        "lines": 147
      }
    ],
    "notChecked": [
      "The independent group-chat witness on your phone (built and JVM-tested, not device-proven)"
    ],
    "digest": "1a860a1cf2e2",
    "totalLines": 871
  },
  {
    "id": "xdc-app",
    "title": "Shared list (webxdc)",
    "tag": "DeltaChat mini-app",
    "icon": "💬",
    "purpose": "The list that syncs inside a DeltaChat group.",
    "link": "webxdc",
    "files": [
      {
        "path": "webxdc/index.html",
        "lines": 80
      },
      {
        "path": "webxdc/js/app.js",
        "lines": 520
      },
      {
        "path": "webxdc/js/digest.js",
        "lines": 143
      },
      {
        "path": "webxdc/js/mergedoc.js",
        "lines": 159
      },
      {
        "path": "webxdc/js/restoregen.js",
        "lines": 221
      },
      {
        "path": "webxdc/js/seedfix.js",
        "lines": 70
      },
      {
        "path": "webxdc/js/webxdc-mock.js",
        "lines": 50
      }
    ],
    "notChecked": [
      "Edit migration between app versions - a new version is a new instance, old bubbles do not heal"
    ],
    "digest": "9fd493ccbab2",
    "totalLines": 1243
  },
  {
    "id": "xdc-canvas",
    "title": "Canvas (webxdc)",
    "tag": "DeltaChat mini-app - canvas",
    "icon": "🗺️",
    "purpose": "This canvas, living inside DeltaChat.",
    "link": "webxdc",
    "files": [
      {
        "path": "webxdc/canvas.html",
        "lines": 108
      },
      {
        "path": "webxdc/js/canvas.js",
        "lines": 408
      },
      {
        "path": "webxdc/js/canvas-math.js",
        "lines": 197
      }
    ],
    "notChecked": [
      "Open/reopen persistence inside a real DeltaChat client (Chrome smoke test only)"
    ],
    "digest": "76d5f0580113",
    "totalLines": 713
  },
  {
    "id": "this-page",
    "title": "This page (intodo)",
    "tag": "Review surface",
    "icon": "🌐",
    "purpose": "The page you are reading - its own source is in the repo, so it reviews itself.",
    "link": "intodo",
    "files": [
      {
        "path": "web/README.md",
        "lines": 16
      },
      {
        "path": "web/src/App.tsx",
        "lines": 474
      },
      {
        "path": "web/src/main.tsx",
        "lines": 7
      },
      {
        "path": "web/src/style.css",
        "lines": 105
      }
    ],
    "notChecked": [
      "Whether your saved layout survives a signed-in reopen on your phone (implemented, not yet verified)"
    ],
    "digest": "9bf2c0b4fe2b",
    "totalLines": 602
  },
  {
    "id": "tests-android",
    "title": "Android tests",
    "tag": "Test suites - JVM",
    "icon": "✅",
    "purpose": "Render and unit tests for the phone app. These run in CI on every release.",
    "link": "app/src/test",
    "files": [
      {
        "path": "app/src/test/java/com/vanipy/intodo/MainActivityRenderTest.java",
        "lines": 265
      },
      {
        "path": "app/src/test/java/com/vanipy/intodo/MainActivityProgressRenderTest.java",
        "lines": 176
      },
      {
        "path": "app/src/test/java/com/vanipy/intodo/WidgetRemoteApplyTest.java",
        "lines": 67
      },
      {
        "path": "app/src/test/java/com/vanipy/intodo/SyncStatusTextTest.java",
        "lines": 94
      },
      {
        "path": "app/src/test/java/com/vanipy/intodo/PairingCopyTest.java",
        "lines": 93
      },
      {
        "path": "app/src/test/java/com/vanipy/intodo/canvas/CanvasMathTest.java",
        "lines": 344
      },
      {
        "path": "app/src/test/java/com/vanipy/intodo/merge/MergeDocTest.java",
        "lines": 174
      },
      {
        "path": "app/src/test/java/com/vanipy/intodo/net/ResumableTest.java",
        "lines": 620
      },
      {
        "path": "app/src/test/java/com/vanipy/intodo/bot/BotActivityRenderTest.java",
        "lines": 201
      },
      {
        "path": "app/src/test/java/com/vanipy/intodo/bot/BotPublishIpcTest.java",
        "lines": 72
      },
      {
        "path": "app/src/test/java/com/vanipy/intodo/bot/PublishDialogRenderTest.java",
        "lines": 104
      },
      {
        "path": "app/src/test/java/com/vanipy/intodo/bot/PublishErrorsTest.java",
        "lines": 25
      },
      {
        "path": "app/src/test/java/com/vanipy/intodo/bot/DisplayNamesTest.java",
        "lines": 16
      },
      {
        "path": "app/src/test/java/com/vanipy/intodo/bot/XdcFetchBarRenderTest.java",
        "lines": 154
      },
      {
        "path": "app/src/test/java/com/vanipy/intodo/bot/XdcFetchTest.java",
        "lines": 109
      }
    ],
    "notChecked": [
      "The widget inflation test guards the v0.5.6 Samsung failure - keep it when editing"
    ],
    "digest": "99332b12a595",
    "totalLines": 2514
  },
  {
    "id": "tests-bot",
    "title": "Bot tests",
    "tag": "Test suites - Python",
    "icon": "🧪",
    "purpose": "Recovery, migration, witness and runtime suites.",
    "link": "app/src/main/python",
    "files": [
      {
        "path": "app/src/main/python/test_bot_runtime.py",
        "lines": 1636
      },
      {
        "path": "app/src/main/python/test_witness.py",
        "lines": 452
      },
      {
        "path": "app/src/main/python/test_restore_recovery.py",
        "lines": 505
      },
      {
        "path": "app/src/main/python/test_migration.py",
        "lines": 327
      }
    ],
    "notChecked": [
      "No accepted recovery candidate has been phone-tested"
    ],
    "digest": "ca7dd74caf9f",
    "totalLines": 2920
  },
  {
    "id": "tests-xdc",
    "title": "webxdc tests",
    "tag": "Test suites - Node",
    "icon": "🧪",
    "purpose": "Merge, restore, seed, packaging and compatibility suites, with pinned fixtures.",
    "link": "webxdc/test",
    "files": [
      {
        "path": "webxdc/test/canvas-math.test.mjs",
        "lines": 247
      },
      {
        "path": "webxdc/test/compat-v21.test.mjs",
        "lines": 108
      },
      {
        "path": "webxdc/test/mergedoc.test.mjs",
        "lines": 170
      },
      {
        "path": "webxdc/test/notice-route.test.mjs",
        "lines": 25
      },
      {
        "path": "webxdc/test/package.test.mjs",
        "lines": 39
      },
      {
        "path": "webxdc/test/restoregen.test.mjs",
        "lines": 221
      },
      {
        "path": "webxdc/test/seedfix.test.mjs",
        "lines": 130
      },
      {
        "path": "webxdc/test/fixtures/app-0.4.3.js",
        "lines": 444
      },
      {
        "path": "webxdc/test/fixtures/envs-new-bot.json",
        "lines": 1
      },
      {
        "path": "webxdc/test/fixtures/envs-old-bot.json",
        "lines": 1
      }
    ],
    "notChecked": [
      "Fixtures pin the 0.4.3 app bytes - they need refreshing per release"
    ],
    "digest": "36b26f37e072",
    "totalLines": 1386
  },
  {
    "id": "build-ci",
    "title": "Build & CI",
    "tag": "Repo plumbing",
    "icon": "🔧",
    "purpose": "Gradle config, the packaging script, and the two GitHub workflows (both manual-trigger only).",
    "link": ".github/workflows",
    "files": [
      {
        "path": ".github/workflows/release.yml",
        "lines": 171
      },
      {
        "path": ".github/workflows/render-check.yml",
        "lines": 51
      },
      {
        "path": "build.gradle",
        "lines": 5
      },
      {
        "path": "settings.gradle",
        "lines": 4
      },
      {
        "path": "gradle.properties",
        "lines": 3
      },
      {
        "path": "app/build.gradle",
        "lines": 50
      },
      {
        "path": "gradle/wrapper/gradle-wrapper.properties",
        "lines": 7
      },
      {
        "path": "webxdc/package.sh",
        "lines": 21
      },
      {
        "path": "webxdc/manifest.toml",
        "lines": 3
      },
      {
        "path": ".gitignore",
        "lines": 11
      }
    ],
    "notChecked": [
      "Nothing runs on its own - both workflows wait for someone to press the button"
    ],
    "digest": "4f3816e143a5",
    "totalLines": 326
  },
  {
    "id": "feed-tool",
    "title": "Feed encryption tool",
    "tag": "Tools",
    "icon": "🔐",
    "purpose": "Encrypts the reminder feed so only the paired phone can read it. Print-only: emitting the payload and publishing the feed page are separate manual steps.",
    "link": "tools",
    "files": [
      {
        "path": "tools/encrypt_feed.py",
        "lines": 36
      }
    ],
    "notChecked": [
      "Key custody - which phone public key the feed encrypts to, and how that key rotates"
    ],
    "digest": "975ee7ca3a15",
    "totalLines": 36
  },
  {
    "id": "dc-relay",
    "title": "Durable reply ledger",
    "tag": "Developer tooling - Python",
    "icon": "🔁",
    "purpose": "Reusable local library for recording inbound messages and reply attempts before sending; uncertain outcomes remain blocked rather than retried blindly.",
    "link": "tools/dc_relay",
    "files": [
      {
        "path": "tools/dc_relay/README.md",
        "lines": 180
      },
      {
        "path": "tools/dc_relay/reply_ledger.py",
        "lines": 473
      },
      {
        "path": "tools/dc_relay/test_reply_ledger.py",
        "lines": 467
      }
    ],
    "notChecked": [
      "Transport integration, storage permissions and lifecycle are caller responsibilities; this library is not wired into the experimental chat bridge"
    ],
    "digest": "08825e280b6c",
    "totalLines": 1120
  },
  {
    "id": "assets-docs",
    "title": "Docs & binary assets",
    "tag": "Non-code",
    "icon": "📄",
    "purpose": "README, icons, the launcher art - and the prebuilt RPC library the bot runs on.",
    "link": ".",
    "files": [
      {
        "path": "README.md",
        "lines": 112
      },
      {
        "path": "app/src/main/jniLibs/arm64-v8a/librpcsrv.so",
        "lines": null,
        "note": "21.7 MB binary"
      },
      {
        "path": "web/src/photos/review-concept-hero.png",
        "lines": null,
        "note": "1.3 MB binary"
      },
      {
        "path": "app/src/main/res/mipmap-hdpi/ic_launcher.png",
        "lines": null,
        "note": "3 KB binary"
      },
      {
        "path": "app/src/main/res/mipmap-mdpi/ic_launcher.png",
        "lines": null,
        "note": "2 KB binary"
      },
      {
        "path": "app/src/main/res/mipmap-xhdpi/ic_launcher.png",
        "lines": null,
        "note": "3 KB binary"
      },
      {
        "path": "app/src/main/res/mipmap-xxhdpi/ic_launcher.png",
        "lines": null,
        "note": "5 KB binary"
      },
      {
        "path": "app/src/main/res/mipmap-xxxhdpi/ic_launcher.png",
        "lines": null,
        "note": "8 KB binary"
      },
      {
        "path": "webxdc/icon.png",
        "lines": null,
        "note": "4 KB binary"
      },
      {
        "path": "gradle/wrapper/gradle-wrapper.jar",
        "lines": null,
        "note": "43 KB binary"
      },
      {
        "path": "gradlew",
        "lines": 249
      },
      {
        "path": "gradlew.bat",
        "lines": 92
      }
    ],
    "notChecked": [
      "librpcsrv.so is a ~21 MB prebuilt binary - its source and build recipe are not in this repo"
    ],
    "digest": "6f184dc39bbf",
    "totalLines": 453
  },
  {
    "id": "review-src",
    "title": "Review canvas source",
    "tag": "Review surface - source",
    "icon": "🧱",
    "purpose": "The static Pages variant of this review: its own app, build script, and the generator that produced these cards.",
    "link": "web/review",
    "files": [
      {
        "path": "web/review/App.tsx",
        "lines": 1990
      },
      {
        "path": "web/review/static.css",
        "lines": 711
      },
      {
        "path": "web/review/main.tsx",
        "lines": 7
      },
      {
        "path": "web/review/files-shim.tsx",
        "lines": 68
      },
      {
        "path": "web/review/index.html",
        "lines": 14
      },
      {
        "path": "web/review/build.sh",
        "lines": 31
      },
      {
        "path": "web/review/package.json",
        "lines": 13
      },
      {
        "path": "web/review/package-lock.json",
        "lines": 530
      },
      {
        "path": "web/review/README.md",
        "lines": 50
      },
      {
        "path": "web/review/.gitignore",
        "lines": 2
      },
      {
        "path": "web/tools/gen_sections.py",
        "lines": 305
      },
      {
        "path": "web/src/sections.ts",
        "lines": null,
        "note": "generated - self-size excluded to avoid self-reference"
      }
    ],
    "notChecked": [],
    "digest": "2b86ba05cf15",
    "totalLines": 3721
  }
];
