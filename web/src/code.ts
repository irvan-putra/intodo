// Generated from irvan-putra/tindak @ 6b540c1 by gen_code.py - do not hand-edit.
// Component names/file assignment/tech labels are the generator's curated CONFIG;
// responsibilities are CURATED concise summaries (architectural interpretation);
// file lines are verbatim first header sentences; declarations and references
// come from a static syntax scan (a reference is a NAME match, not a proven call).

export const CODE_COMMIT = "6b540c186f2502ff9a8e7da1ece1b41b0957e3dc";
export const CODE_COMMIT_SHORT = "6b540c1";
export type CodeEl = { name: string; kind: string };
export type CodeFile = { path: string; lines: number | null; role?: string | null; els: CodeEl[]; kindNote?: string | null; intraRefs: string[] };
export type C4Component = { id: string; container: string; name: string; tech: string; resp?: string | null; files: string[]; sections: string[]; shared: boolean; devOnly: boolean };
export type ComponentRef = { a: string; b: string; via: string[] };
export const COMPONENTS: C4Component[] = [
  {
    "id": "cp-list-screen",
    "container": "c-app",
    "name": "List screen",
    "tech": "Java - Android Activity",
    "resp": "Shows the reminder list and hosts the app's menu actions (sync, bot, canvas, self-update).",
    "files": [
      "app/src/main/java/com/vanipy/tindak/MainActivity.java",
      "app/src/main/AndroidManifest.xml",
      "app/src/main/res/layout/activity_main.xml",
      "app/src/main/res/layout/list_item.xml",
      "app/src/main/res/layout/list_section.xml",
      "app/src/main/res/values/styles.xml"
    ],
    "sections": [
      "list-ui"
    ],
    "shared": false,
    "devOnly": false
  },
  {
    "id": "cp-widget",
    "container": "c-app",
    "name": "Home-screen widget",
    "tech": "AppWidgetProvider + RemoteViewsService",
    "resp": "Home-screen widget rendering the cached list; taps refresh via the sync scheduler.",
    "files": [
      "app/src/main/java/com/vanipy/tindak/WidgetProvider.java",
      "app/src/main/java/com/vanipy/tindak/WidgetService.java",
      "app/src/main/res/layout/widget_layout.xml",
      "app/src/main/res/layout/widget_item.xml",
      "app/src/main/res/layout/widget_section.xml",
      "app/src/main/res/xml/widget_info.xml",
      "app/src/main/res/drawable/ic_refresh.xml"
    ],
    "sections": [
      "list-ui"
    ],
    "shared": false,
    "devOnly": false
  },
  {
    "id": "cp-sync-sched",
    "container": "c-app",
    "name": "Sync scheduling",
    "tech": "Java - WorkManager",
    "resp": "Schedules and runs periodic background feed syncs (WorkManager), then refreshes the widget.",
    "files": [
      "app/src/main/java/com/vanipy/tindak/FeedSyncScheduler.java",
      "app/src/main/java/com/vanipy/tindak/FeedSyncWorker.java"
    ],
    "sections": [
      "feed-sync"
    ],
    "shared": false,
    "devOnly": false
  },
  {
    "id": "cp-feed-pipeline",
    "container": "c-app",
    "name": "Feed fetch & parse",
    "tech": "Java - hidden WebView, IFW1 decrypt",
    "resp": "Fetches the feed page through a hidden WebView, decrypts IFW1, parses items.",
    "files": [
      "app/src/main/java/com/vanipy/tindak/SyncEngine.java",
      "app/src/main/java/com/vanipy/tindak/Crypto.java",
      "app/src/main/java/com/vanipy/tindak/FeedParser.java",
      "app/src/main/java/com/vanipy/tindak/Freshness.java"
    ],
    "sections": [
      "feed-sync"
    ],
    "shared": false,
    "devOnly": false
  },
  {
    "id": "cp-self-update",
    "container": "c-app",
    "name": "Self-update",
    "tech": "Java - APK download + install",
    "resp": "Checks GitHub Releases for a newer tindak APK, downloads and installs it.",
    "files": [
      "app/src/main/java/com/vanipy/tindak/UpdateCheck.java",
      "app/src/main/java/com/vanipy/tindak/UpdateInstall.java",
      "app/src/main/java/com/vanipy/tindak/InstallStatusReceiver.java"
    ],
    "sections": [
      "self-update",
      "feed-sync"
    ],
    "shared": false,
    "devOnly": false
  },
  {
    "id": "cp-bot-host",
    "container": "c-app",
    "name": "Bot host",
    "tech": "Java/Kotlin - foreground Service + Activity",
    "resp": "Runs the embedded DeltaChat bot (foreground service) plus its setup and publish UI.",
    "files": [
      "app/src/main/java/com/vanipy/tindak/bot/BotService.java",
      "app/src/main/java/com/vanipy/tindak/bot/BotActivity.java",
      "app/src/main/java/com/vanipy/tindak/bot/BootReceiver.java",
      "app/src/main/java/com/vanipy/tindak/bot/BotImport.kt",
      "app/src/main/java/com/vanipy/tindak/bot/PublishErrors.java",
      "app/src/main/java/com/vanipy/tindak/bot/DisplayNames.java",
      "app/src/main/java/com/vanipy/tindak/bot/XdcFetch.java"
    ],
    "sections": [
      "bot-app"
    ],
    "shared": false,
    "devOnly": false
  },
  {
    "id": "cp-bot-bridge",
    "container": "c-app",
    "name": "Bot bridge & control",
    "tech": "Python (Chaquopy) - deltachat-rpc-client",
    "resp": "Bot runtime: serves each accepted chat an isolated list stack; routes events to bridges.",
    "files": [
      "app/src/main/python/bot_runtime.py",
      "app/src/main/python/bot.py"
    ],
    "sections": [
      "bot-runtime"
    ],
    "shared": false,
    "devOnly": false
  },
  {
    "id": "cp-delivery-ops",
    "container": "c-app",
    "name": "Delivery & op store",
    "tech": "Python - SQLite op log",
    "resp": "Publishes new .xdc versions to served chats and persists list ops (SQLite).",
    "files": [
      "app/src/main/python/delivery_bot.py",
      "app/src/main/python/ops_store.py",
      "app/src/main/python/digest-fixtures.json"
    ],
    "sections": [
      "bot-delivery"
    ],
    "shared": false,
    "devOnly": false
  },
  {
    "id": "cp-witness",
    "container": "c-app",
    "name": "Witness & validation",
    "tech": "Python - append-only witness log",
    "resp": "Append-only witness log for chat observation, plus offline validation tooling.",
    "files": [
      "app/src/main/python/witness.py",
      "app/src/main/python/bot_validation.py"
    ],
    "sections": [
      "witness"
    ],
    "shared": false,
    "devOnly": false
  },
  {
    "id": "cp-migration",
    "container": "c-app",
    "name": "State migration",
    "tech": "Python",
    "resp": "Phone-local export/import bundle moving the bot between Android installs.",
    "files": [
      "app/src/main/python/migration.py"
    ],
    "sections": [
      "bot-delivery"
    ],
    "shared": false,
    "devOnly": false
  },
  {
    "id": "cp-native-canvas",
    "container": "c-app",
    "name": "Native review canvas",
    "tech": "Java - Android Canvas View",
    "resp": "Native Android host and WebView bridge for the review canvas page.",
    "files": [
      "app/src/main/java/com/vanipy/tindak/canvas/ReviewCanvasActivity.java",
      "app/src/main/java/com/vanipy/tindak/canvas/ReviewCanvasView.java",
      "app/src/main/java/com/vanipy/tindak/canvas/CanvasContent.java",
      "app/src/main/java/com/vanipy/tindak/canvas/CanvasMath.java"
    ],
    "sections": [
      "native-canvas"
    ],
    "shared": false,
    "devOnly": false
  },
  {
    "id": "cp-merge",
    "container": "c-app",
    "name": "Merge core (CRDT)",
    "tech": "Java - LWW registers, HLC",
    "resp": "CRDT merge core: per-field last-writer-wins registers with HLC ordering.",
    "files": [
      "app/src/main/java/com/vanipy/tindak/merge/MergeDoc.java"
    ],
    "sections": [
      "merge-core"
    ],
    "shared": false,
    "devOnly": false
  },
  {
    "id": "cp-shared",
    "container": "c-app",
    "name": "Shared support",
    "tech": "Java - used across components",
    "resp": "Shared prefs/progress/status helpers used across components (no feature of its own).",
    "files": [
      "app/src/main/java/com/vanipy/tindak/Prefs.java",
      "app/src/main/java/com/vanipy/tindak/ProgressPump.java",
      "app/src/main/java/com/vanipy/tindak/SyncStatusText.java",
      "app/src/main/java/com/vanipy/tindak/net/Resumable.java"
    ],
    "sections": [
      "feed-sync",
      "self-update",
      "bot-app"
    ],
    "shared": true,
    "devOnly": false
  },
  {
    "id": "cx-app",
    "container": "c-xdc",
    "name": "List app UI",
    "tech": "JavaScript module (IIFE)",
    "resp": "Shared-list webxdc UI: turns user actions into ops and renders the merged list.",
    "files": [
      "webxdc/index.html",
      "webxdc/js/app.js"
    ],
    "sections": [
      "xdc-app"
    ],
    "shared": false,
    "devOnly": false
  },
  {
    "id": "cx-merge",
    "container": "c-xdc",
    "name": "MergeDoc JS port",
    "tech": "JavaScript module",
    "resp": "JS port of the Android MergeDoc CRDT; same semantics, property-tested.",
    "files": [
      "webxdc/js/mergedoc.js"
    ],
    "sections": [
      "xdc-app"
    ],
    "shared": false,
    "devOnly": false
  },
  {
    "id": "cx-restore",
    "container": "c-xdc",
    "name": "Restore & digest support",
    "tech": "JavaScript modules",
    "resp": "Tracks restore generations, computes canonical digests, fixes item seeds.",
    "files": [
      "webxdc/js/restoregen.js",
      "webxdc/js/seedfix.js",
      "webxdc/js/digest.js"
    ],
    "sections": [
      "xdc-app"
    ],
    "shared": false,
    "devOnly": false
  },
  {
    "id": "cx-mock",
    "container": "c-xdc",
    "name": "webxdc dev mock",
    "tech": "JavaScript - development only",
    "resp": "Dev-only simulation of the webxdc messaging layer for local tests.",
    "files": [
      "webxdc/js/webxdc-mock.js"
    ],
    "sections": [
      "xdc-app"
    ],
    "shared": false,
    "devOnly": true
  },
  {
    "id": "cx-canvas",
    "container": "c-xdc",
    "name": "Canvas page",
    "tech": "JavaScript modules + canvas.html",
    "resp": "Plain-DOM canvas page port of the review canvas for the mini-app.",
    "files": [
      "webxdc/canvas.html",
      "webxdc/js/canvas.js",
      "webxdc/js/canvas-math.js"
    ],
    "sections": [
      "xdc-canvas"
    ],
    "shared": false,
    "devOnly": false
  },
  {
    "id": "cw-landing",
    "container": "c-web",
    "name": "Landing page",
    "tech": "React/TypeScript",
    "resp": "Static landing page for the repo, served on GitHub Pages.",
    "files": [
      "web/src/App.tsx",
      "web/src/main.tsx",
      "web/src/style.css",
      "web/README.md"
    ],
    "sections": [
      "this-page"
    ],
    "shared": false,
    "devOnly": false
  },
  {
    "id": "cw-review-map",
    "container": "c-web",
    "name": "Review map",
    "tech": "React/TypeScript - this canvas",
    "resp": "This review canvas: zoom-banded C4 map plus the section review workflow.",
    "files": [
      "web/review/App.tsx",
      "web/review/static.css",
      "web/review/main.tsx",
      "web/review/files-shim.tsx",
      "web/review/index.html",
      "web/review/build.sh",
      "web/review/package.json",
      "web/review/package-lock.json",
      "web/review/README.md",
      "web/review/.gitignore"
    ],
    "sections": [
      "review-src"
    ],
    "shared": false,
    "devOnly": false
  },
  {
    "id": "cw-gen",
    "container": "c-web",
    "name": "Section inventory generator",
    "tech": "Python - generates sections.ts",
    "resp": "Generates sections.ts (file inventory and digests) from a repo checkout.",
    "files": [
      "web/tools/gen_sections.py",
      "web/src/sections.ts"
    ],
    "sections": [
      "review-src"
    ],
    "shared": false,
    "devOnly": false
  },
  {
    "id": "cf-cli",
    "container": "c-feedtool",
    "name": "Feed encryption CLI",
    "tech": "Python - stdin to stdout",
    "resp": "Encrypts a feed JSON to the phone's public key (IFW1) for publishing.",
    "files": [
      "tools/encrypt_feed.py"
    ],
    "sections": [
      "feed-tool"
    ],
    "shared": false,
    "devOnly": false
  }
];
export const COMPONENT_REFS: ComponentRef[] = [
  {
    "a": "cp-bot-bridge",
    "b": "cp-delivery-ops",
    "via": [
      "bot.py imports delivery_bot",
      "bot.py imports ops_store",
      "bot_runtime.py imports delivery_bot",
      "bot_runtime.py imports ops_store"
    ]
  },
  {
    "a": "cp-bot-bridge",
    "b": "cp-migration",
    "via": [
      "bot_runtime.py imports migration"
    ]
  },
  {
    "a": "cp-bot-bridge",
    "b": "cp-witness",
    "via": [
      "bot_runtime.py imports witness"
    ]
  },
  {
    "a": "cp-bot-host",
    "b": "cp-self-update",
    "via": [
      "XdcFetch.java -> UpdateCheck",
      "XdcFetch.java -> UpdateInstall"
    ]
  },
  {
    "a": "cp-bot-host",
    "b": "cp-shared",
    "via": [
      "BotActivity.java -> ProgressPump",
      "XdcFetch.java -> Resumable"
    ]
  },
  {
    "a": "cp-feed-pipeline",
    "b": "cp-shared",
    "via": [
      "SyncEngine.java -> Prefs"
    ]
  },
  {
    "a": "cp-list-screen",
    "b": "cp-bot-host",
    "via": [
      "MainActivity.java -> BotActivity"
    ]
  },
  {
    "a": "cp-list-screen",
    "b": "cp-feed-pipeline",
    "via": [
      "MainActivity.java -> Crypto",
      "MainActivity.java -> FeedParser",
      "MainActivity.java -> SyncEngine"
    ]
  },
  {
    "a": "cp-list-screen",
    "b": "cp-native-canvas",
    "via": [
      "MainActivity.java -> ReviewCanvasActivity"
    ]
  },
  {
    "a": "cp-list-screen",
    "b": "cp-self-update",
    "via": [
      "MainActivity.java -> UpdateCheck",
      "MainActivity.java -> UpdateInstall"
    ]
  },
  {
    "a": "cp-list-screen",
    "b": "cp-shared",
    "via": [
      "MainActivity.java -> Prefs",
      "MainActivity.java -> SyncStatusText"
    ]
  },
  {
    "a": "cp-list-screen",
    "b": "cp-sync-sched",
    "via": [
      "MainActivity.java -> FeedSyncScheduler"
    ]
  },
  {
    "a": "cp-list-screen",
    "b": "cp-widget",
    "via": [
      "MainActivity.java -> WidgetProvider"
    ]
  },
  {
    "a": "cp-migration",
    "b": "cp-bot-bridge",
    "via": [
      "migration.py imports bot_runtime"
    ]
  },
  {
    "a": "cp-self-update",
    "b": "cp-shared",
    "via": [
      "InstallStatusReceiver.java -> Prefs",
      "UpdateInstall.java -> Prefs",
      "UpdateInstall.java -> ProgressPump",
      "UpdateInstall.java -> Resumable"
    ]
  },
  {
    "a": "cp-shared",
    "b": "cp-bot-host",
    "via": [
      "Resumable.java -> XdcFetch"
    ]
  },
  {
    "a": "cp-shared",
    "b": "cp-self-update",
    "via": [
      "Resumable.java -> UpdateInstall"
    ]
  },
  {
    "a": "cp-sync-sched",
    "b": "cp-feed-pipeline",
    "via": [
      "FeedSyncWorker.java -> SyncEngine"
    ]
  },
  {
    "a": "cp-sync-sched",
    "b": "cp-shared",
    "via": [
      "FeedSyncWorker.java -> Prefs"
    ]
  },
  {
    "a": "cp-sync-sched",
    "b": "cp-widget",
    "via": [
      "FeedSyncWorker.java -> WidgetProvider"
    ]
  },
  {
    "a": "cp-widget",
    "b": "cp-feed-pipeline",
    "via": [
      "WidgetProvider.java -> FeedParser",
      "WidgetService.java -> FeedParser"
    ]
  },
  {
    "a": "cp-widget",
    "b": "cp-list-screen",
    "via": [
      "WidgetProvider.java -> MainActivity"
    ]
  },
  {
    "a": "cp-widget",
    "b": "cp-shared",
    "via": [
      "WidgetProvider.java -> Prefs",
      "WidgetService.java -> Prefs"
    ]
  },
  {
    "a": "cp-widget",
    "b": "cp-sync-sched",
    "via": [
      "WidgetProvider.java -> FeedSyncScheduler"
    ]
  },
  {
    "a": "cp-witness",
    "b": "cp-delivery-ops",
    "via": [
      "bot_validation.py imports delivery_bot",
      "bot_validation.py imports ops_store"
    ]
  },
  {
    "a": "cx-app",
    "b": "cx-merge",
    "via": [
      "app.js uses MergeDoc"
    ]
  },
  {
    "a": "cx-restore",
    "b": "cx-merge",
    "via": [
      "restoregen.js uses MergeDoc",
      "seedfix.js uses MergeDoc"
    ]
  }
];
export const CODE_FILES: Record<string, CodeFile> = {
  "app/src/main/java/com/vanipy/tindak/MainActivity.java": {
    "path": "app/src/main/java/com/vanipy/tindak/MainActivity.java",
    "lines": 364,
    "role": null,
    "els": [
      {
        "name": "MainActivity",
        "kind": "class"
      },
      {
        "name": "FeedAdapter",
        "kind": "class"
      }
    ],
    "kindNote": null,
    "intraRefs": []
  },
  "app/src/main/AndroidManifest.xml": {
    "path": "app/src/main/AndroidManifest.xml",
    "lines": 46,
    "role": null,
    "els": [],
    "kindNote": "manifest",
    "intraRefs": []
  },
  "app/src/main/res/layout/activity_main.xml": {
    "path": "app/src/main/res/layout/activity_main.xml",
    "lines": 87,
    "role": null,
    "els": [],
    "kindNote": "layout resource",
    "intraRefs": []
  },
  "app/src/main/res/layout/list_item.xml": {
    "path": "app/src/main/res/layout/list_item.xml",
    "lines": 9,
    "role": null,
    "els": [],
    "kindNote": "layout resource",
    "intraRefs": []
  },
  "app/src/main/res/layout/list_section.xml": {
    "path": "app/src/main/res/layout/list_section.xml",
    "lines": 5,
    "role": null,
    "els": [],
    "kindNote": "layout resource",
    "intraRefs": []
  },
  "app/src/main/res/values/styles.xml": {
    "path": "app/src/main/res/values/styles.xml",
    "lines": 9,
    "role": null,
    "els": [],
    "kindNote": null,
    "intraRefs": []
  },
  "app/src/main/java/com/vanipy/tindak/WidgetProvider.java": {
    "path": "app/src/main/java/com/vanipy/tindak/WidgetProvider.java",
    "lines": 105,
    "role": null,
    "els": [
      {
        "name": "WidgetProvider",
        "kind": "class"
      }
    ],
    "kindNote": null,
    "intraRefs": [
      "WidgetService"
    ]
  },
  "app/src/main/java/com/vanipy/tindak/WidgetService.java": {
    "path": "app/src/main/java/com/vanipy/tindak/WidgetService.java",
    "lines": 72,
    "role": null,
    "els": [
      {
        "name": "WidgetService",
        "kind": "class"
      },
      {
        "name": "Factory",
        "kind": "class"
      }
    ],
    "kindNote": null,
    "intraRefs": []
  },
  "app/src/main/res/layout/widget_layout.xml": {
    "path": "app/src/main/res/layout/widget_layout.xml",
    "lines": 23,
    "role": null,
    "els": [],
    "kindNote": "layout resource",
    "intraRefs": []
  },
  "app/src/main/res/layout/widget_item.xml": {
    "path": "app/src/main/res/layout/widget_item.xml",
    "lines": 9,
    "role": null,
    "els": [],
    "kindNote": "layout resource",
    "intraRefs": []
  },
  "app/src/main/res/layout/widget_section.xml": {
    "path": "app/src/main/res/layout/widget_section.xml",
    "lines": 5,
    "role": null,
    "els": [],
    "kindNote": "layout resource",
    "intraRefs": []
  },
  "app/src/main/res/xml/widget_info.xml": {
    "path": "app/src/main/res/xml/widget_info.xml",
    "lines": 12,
    "role": null,
    "els": [],
    "kindNote": "xml resource",
    "intraRefs": []
  },
  "app/src/main/res/drawable/ic_refresh.xml": {
    "path": "app/src/main/res/drawable/ic_refresh.xml",
    "lines": 6,
    "role": null,
    "els": [],
    "kindNote": "drawable resource",
    "intraRefs": []
  },
  "app/src/main/java/com/vanipy/tindak/FeedSyncScheduler.java": {
    "path": "app/src/main/java/com/vanipy/tindak/FeedSyncScheduler.java",
    "lines": 36,
    "role": "Schedules the existing periodic feed sync.",
    "els": [
      {
        "name": "FeedSyncScheduler",
        "kind": "class"
      }
    ],
    "kindNote": null,
    "intraRefs": [
      "FeedSyncWorker"
    ]
  },
  "app/src/main/java/com/vanipy/tindak/FeedSyncWorker.java": {
    "path": "app/src/main/java/com/vanipy/tindak/FeedSyncWorker.java",
    "lines": 46,
    "role": "Periodic background feed sync.",
    "els": [
      {
        "name": "FeedSyncWorker",
        "kind": "class"
      }
    ],
    "kindNote": null,
    "intraRefs": []
  },
  "app/src/main/java/com/vanipy/tindak/SyncEngine.java": {
    "path": "app/src/main/java/com/vanipy/tindak/SyncEngine.java",
    "lines": 287,
    "role": "Fetches the Instinct Files feed page.",
    "els": [
      {
        "name": "SyncEngine",
        "kind": "class"
      },
      {
        "name": "Callback",
        "kind": "interface"
      }
    ],
    "kindNote": null,
    "intraRefs": [
      "Crypto",
      "FeedParser",
      "Freshness"
    ]
  },
  "app/src/main/java/com/vanipy/tindak/Crypto.java": {
    "path": "app/src/main/java/com/vanipy/tindak/Crypto.java",
    "lines": 79,
    "role": "ECIES (P-256 + ECDH + HKDF-SHA256 + AES-256-GCM) for the widget feed.",
    "els": [
      {
        "name": "Crypto",
        "kind": "class"
      }
    ],
    "kindNote": null,
    "intraRefs": []
  },
  "app/src/main/java/com/vanipy/tindak/FeedParser.java": {
    "path": "app/src/main/java/com/vanipy/tindak/FeedParser.java",
    "lines": 46,
    "role": "Parses the decrypted feed JSON.",
    "els": [
      {
        "name": "FeedParser",
        "kind": "class"
      },
      {
        "name": "Item",
        "kind": "class"
      },
      {
        "name": "Section",
        "kind": "class"
      },
      {
        "name": "Feed",
        "kind": "class"
      }
    ],
    "kindNote": null,
    "intraRefs": []
  },
  "app/src/main/java/com/vanipy/tindak/Freshness.java": {
    "path": "app/src/main/java/com/vanipy/tindak/Freshness.java",
    "lines": 26,
    "role": "Feed freshness comparison on parsed epoch instants, not raw strings.",
    "els": [
      {
        "name": "Freshness",
        "kind": "class"
      },
      {
        "name": "Result",
        "kind": "enum"
      }
    ],
    "kindNote": null,
    "intraRefs": []
  },
  "app/src/main/java/com/vanipy/tindak/UpdateCheck.java": {
    "path": "app/src/main/java/com/vanipy/tindak/UpdateCheck.java",
    "lines": 112,
    "role": "Checks the public tindak repo's GitHub Releases for a strictly newer tindak build.",
    "els": [
      {
        "name": "UpdateCheck",
        "kind": "class"
      },
      {
        "name": "Callback",
        "kind": "interface"
      }
    ],
    "kindNote": null,
    "intraRefs": []
  },
  "app/src/main/java/com/vanipy/tindak/UpdateInstall.java": {
    "path": "app/src/main/java/com/vanipy/tindak/UpdateInstall.java",
    "lines": 220,
    "role": "Downloads a release APK and hands it to the Android package installer, which shows * the system's own install confirmation - the user always approves the install.",
    "els": [
      {
        "name": "UpdateInstall",
        "kind": "class"
      },
      {
        "name": "Callback",
        "kind": "interface"
      }
    ],
    "kindNote": null,
    "intraRefs": [
      "InstallStatusReceiver"
    ]
  },
  "app/src/main/java/com/vanipy/tindak/InstallStatusReceiver.java": {
    "path": "app/src/main/java/com/vanipy/tindak/InstallStatusReceiver.java",
    "lines": 47,
    "role": "Receives PackageInstaller session results.",
    "els": [
      {
        "name": "InstallStatusReceiver",
        "kind": "class"
      }
    ],
    "kindNote": null,
    "intraRefs": []
  },
  "app/src/main/java/com/vanipy/tindak/bot/BotService.java": {
    "path": "app/src/main/java/com/vanipy/tindak/bot/BotService.java",
    "lines": 101,
    "role": null,
    "els": [
      {
        "name": "BotService",
        "kind": "class"
      }
    ],
    "kindNote": null,
    "intraRefs": []
  },
  "app/src/main/java/com/vanipy/tindak/bot/BotActivity.java": {
    "path": "app/src/main/java/com/vanipy/tindak/bot/BotActivity.java",
    "lines": 854,
    "role": null,
    "els": [
      {
        "name": "BotActivity",
        "kind": "class"
      }
    ],
    "kindNote": null,
    "intraRefs": [
      "BotImport",
      "BotService",
      "DisplayNames",
      "PublishErrors",
      "XdcFetch"
    ]
  },
  "app/src/main/java/com/vanipy/tindak/bot/BootReceiver.java": {
    "path": "app/src/main/java/com/vanipy/tindak/bot/BootReceiver.java",
    "lines": 31,
    "role": "Restarts the bot after reboot when it was running, mirroring the official * DeltaChat-Android BootReceiver + KeepAliveService.maybeStartSelf path * (official BootReceiver is itself a no-op; receiving the broadcast starts",
    "els": [
      {
        "name": "BootReceiver",
        "kind": "class"
      }
    ],
    "kindNote": null,
    "intraRefs": [
      "BotService"
    ]
  },
  "app/src/main/java/com/vanipy/tindak/bot/BotImport.kt": {
    "path": "app/src/main/java/com/vanipy/tindak/bot/BotImport.kt",
    "lines": 135,
    "role": "Drop-in \"Import bot backup\" flow for BotActivity (tindak 0.4.5).",
    "els": [
      {
        "name": "BotImport",
        "kind": "class"
      }
    ],
    "kindNote": null,
    "intraRefs": [
      "BotActivity",
      "BotService"
    ]
  },
  "app/src/main/java/com/vanipy/tindak/bot/PublishErrors.java": {
    "path": "app/src/main/java/com/vanipy/tindak/bot/PublishErrors.java",
    "lines": 27,
    "role": "Turns raw publish fan-out errors into a plain-language lead for the * publish result dialog.",
    "els": [
      {
        "name": "PublishErrors",
        "kind": "class"
      }
    ],
    "kindNote": null,
    "intraRefs": [
      "DisplayNames"
    ]
  },
  "app/src/main/java/com/vanipy/tindak/bot/DisplayNames.java": {
    "path": "app/src/main/java/com/vanipy/tindak/bot/DisplayNames.java",
    "lines": 12,
    "role": "Presentation names only.",
    "els": [
      {
        "name": "DisplayNames",
        "kind": "class"
      }
    ],
    "kindNote": null,
    "intraRefs": []
  },
  "app/src/main/java/com/vanipy/tindak/bot/XdcFetch.java": {
    "path": "app/src/main/java/com/vanipy/tindak/bot/XdcFetch.java",
    "lines": 231,
    "role": "Manual-tap \"get the latest app version (.xdc) from GitHub\" for the bot * publish flow.",
    "els": [
      {
        "name": "XdcFetch",
        "kind": "class"
      },
      {
        "name": "Found",
        "kind": "class"
      },
      {
        "name": "FetchException",
        "kind": "class"
      },
      {
        "name": "ProgressListener",
        "kind": "interface"
      }
    ],
    "kindNote": null,
    "intraRefs": []
  },
  "app/src/main/python/bot_runtime.py": {
    "path": "app/src/main/python/bot_runtime.py",
    "lines": 1042,
    "role": "LIVE bot runtime (scratch 0.3 r12).",
    "els": [
      {
        "name": "_account_addr",
        "kind": "def"
      },
      {
        "name": "_parse_membership",
        "kind": "def"
      },
      {
        "name": "_redact_template",
        "kind": "def"
      },
      {
        "name": "_state_path",
        "kind": "def"
      },
      {
        "name": "load_state",
        "kind": "def"
      },
      {
        "name": "save_state",
        "kind": "def"
      },
      {
        "name": "_rpc",
        "kind": "def"
      },
      {
        "name": "provision",
        "kind": "def"
      },
      {
        "name": "_account",
        "kind": "def"
      },
      {
        "name": "_ensure_display_name",
        "kind": "def"
      },
      {
        "name": "read_list_identity",
        "kind": "def"
      },
      {
        "name": "ChatRuntime",
        "kind": "class"
      }
    ],
    "kindNote": null,
    "intraRefs": [
      "bot"
    ]
  },
  "app/src/main/python/bot.py": {
    "path": "app/src/main/python/bot.py",
    "lines": 400,
    "role": "webxdc storage bridge bot (v3, gated, ack-free).",
    "els": [
      {
        "name": "_account_addr",
        "kind": "def"
      },
      {
        "name": "BridgeBot",
        "kind": "class"
      }
    ],
    "kindNote": null,
    "intraRefs": []
  },
  "app/src/main/python/delivery_bot.py": {
    "path": "app/src/main/python/delivery_bot.py",
    "lines": 1044,
    "role": "Latest-version delivery + bot-synced continuity for tindak (PixelSocial pattern).",
    "els": [
      {
        "name": "_version_from_toml",
        "kind": "def"
      },
      {
        "name": "parse_manifest_version",
        "kind": "def"
      },
      {
        "name": "parse_manifest_version_from_blob",
        "kind": "def"
      },
      {
        "name": "pack_list_identity",
        "kind": "def"
      },
      {
        "name": "CandidateUncertainError",
        "kind": "class"
      },
      {
        "name": "_is_missing_entry_error",
        "kind": "def"
      },
      {
        "name": "DeliveryBot",
        "kind": "class"
      }
    ],
    "kindNote": null,
    "intraRefs": [
      "ops_store"
    ]
  },
  "app/src/main/python/ops_store.py": {
    "path": "app/src/main/python/ops_store.py",
    "lines": 357,
    "role": "Storage adapter #1 (v2): durable, multi-instance webxdc ops log.",
    "els": [
      {
        "name": "is_bot_envelope",
        "kind": "def"
      },
      {
        "name": "is_boot",
        "kind": "def"
      },
      {
        "name": "is_resume_hint",
        "kind": "def"
      },
      {
        "name": "validate_op",
        "kind": "def"
      },
      {
        "name": "canonical_op",
        "kind": "def"
      },
      {
        "name": "op_key",
        "kind": "def"
      },
      {
        "name": "extract_ops",
        "kind": "def"
      },
      {
        "name": "OpsStore",
        "kind": "class"
      }
    ],
    "kindNote": null,
    "intraRefs": []
  },
  "app/src/main/python/digest-fixtures.json": {
    "path": "app/src/main/python/digest-fixtures.json",
    "lines": 336,
    "role": null,
    "els": [],
    "kindNote": "data",
    "intraRefs": []
  },
  "app/src/main/python/witness.py": {
    "path": "app/src/main/python/witness.py",
    "lines": 724,
    "role": "Witness module: independent observation of user-selected chats for false-positive/false-negative REVIEW (never automatic verdicts).",
    "els": [
      {
        "name": "WitnessStore",
        "kind": "class"
      },
      {
        "name": "BotWitness",
        "kind": "class"
      }
    ],
    "kindNote": null,
    "intraRefs": []
  },
  "app/src/main/python/bot_validation.py": {
    "path": "app/src/main/python/bot_validation.py",
    "lines": 147,
    "role": "scratch-0.2 on-device validation probe (NO account, NO network).",
    "els": [
      {
        "name": "_gate_probe",
        "kind": "def"
      },
      {
        "name": "run",
        "kind": "def"
      }
    ],
    "kindNote": null,
    "intraRefs": []
  },
  "app/src/main/python/migration.py": {
    "path": "app/src/main/python/migration.py",
    "lines": 337,
    "role": "Migration bundle: secure phone-local handoff of the bot between Android packages (scratch test app -> tindak).",
    "els": [
      {
        "name": "MigrationError",
        "kind": "class"
      },
      {
        "name": "generate_transfer_code",
        "kind": "def"
      },
      {
        "name": "_fernet",
        "kind": "def"
      },
      {
        "name": "_encrypt",
        "kind": "def"
      },
      {
        "name": "_decrypt",
        "kind": "def"
      },
      {
        "name": "_sha256",
        "kind": "def"
      },
      {
        "name": "state_members",
        "kind": "def"
      },
      {
        "name": "build_state_tar",
        "kind": "def"
      },
      {
        "name": "stage_state",
        "kind": "def"
      },
      {
        "name": "commit_state",
        "kind": "def"
      },
      {
        "name": "build_bundle",
        "kind": "def"
      },
      {
        "name": "parse_bundle",
        "kind": "def"
      }
    ],
    "kindNote": null,
    "intraRefs": []
  },
  "app/src/main/java/com/vanipy/tindak/canvas/ReviewCanvasActivity.java": {
    "path": "app/src/main/java/com/vanipy/tindak/canvas/ReviewCanvasActivity.java",
    "lines": 60,
    "role": "Hosts the review canvas: a small header, the canvas fitted to the available * screen height (ReviewCanvasView caps itself at 600dp, min 320dp), and the * gesture caption.",
    "els": [
      {
        "name": "ReviewCanvasActivity",
        "kind": "class"
      }
    ],
    "kindNote": null,
    "intraRefs": [
      "ReviewCanvasView"
    ]
  },
  "app/src/main/java/com/vanipy/tindak/canvas/ReviewCanvasView.java": {
    "path": "app/src/main/java/com/vanipy/tindak/canvas/ReviewCanvasView.java",
    "lines": 637,
    "role": "Native review-canvas view for tindak: a bounded, pannable/zoomable canvas of * review cards with a minimap - the Android port of the tindak page canvas * (web/src/App.tsx) and the tindak port (webxdc/js/canvas.js).",
    "els": [
      {
        "name": "ReviewCanvasView",
        "kind": "class"
      },
      {
        "name": "BlockLayout",
        "kind": "class"
      },
      {
        "name": "CardLayout",
        "kind": "class"
      }
    ],
    "kindNote": null,
    "intraRefs": [
      "CanvasContent",
      "CanvasMath"
    ]
  },
  "app/src/main/java/com/vanipy/tindak/canvas/CanvasContent.java": {
    "path": "app/src/main/java/com/vanipy/tindak/canvas/CanvasContent.java",
    "lines": 107,
    "role": "Card content for the review canvas, verbatim from the tindak page source * (web/src/App.tsx).",
    "els": [
      {
        "name": "CanvasContent",
        "kind": "class"
      },
      {
        "name": "BlockKind",
        "kind": "enum"
      },
      {
        "name": "Chip",
        "kind": "enum"
      },
      {
        "name": "Segment",
        "kind": "class"
      },
      {
        "name": "Block",
        "kind": "class"
      },
      {
        "name": "Card",
        "kind": "class"
      }
    ],
    "kindNote": null,
    "intraRefs": []
  },
  "app/src/main/java/com/vanipy/tindak/canvas/CanvasMath.java": {
    "path": "app/src/main/java/com/vanipy/tindak/canvas/CanvasMath.java",
    "lines": 234,
    "role": "Pure camera/layout math for the tindak review canvas.",
    "els": [
      {
        "name": "CanvasMath",
        "kind": "class"
      },
      {
        "name": "Pos",
        "kind": "class"
      },
      {
        "name": "Cam",
        "kind": "class"
      },
      {
        "name": "Rect",
        "kind": "class"
      },
      {
        "name": "Saved",
        "kind": "class"
      }
    ],
    "kindNote": null,
    "intraRefs": []
  },
  "app/src/main/java/com/vanipy/tindak/merge/MergeDoc.java": {
    "path": "app/src/main/java/com/vanipy/tindak/merge/MergeDoc.java",
    "lines": 248,
    "role": "Merge engine for the local document.",
    "els": [
      {
        "name": "MergeDoc",
        "kind": "class"
      },
      {
        "name": "Stamp",
        "kind": "class"
      },
      {
        "name": "Reg",
        "kind": "class"
      },
      {
        "name": "HistoryEntry",
        "kind": "class"
      },
      {
        "name": "Item",
        "kind": "class"
      }
    ],
    "kindNote": null,
    "intraRefs": []
  },
  "app/src/main/java/com/vanipy/tindak/Prefs.java": {
    "path": "app/src/main/java/com/vanipy/tindak/Prefs.java",
    "lines": 51,
    "role": null,
    "els": [
      {
        "name": "Prefs",
        "kind": "class"
      }
    ],
    "kindNote": null,
    "intraRefs": []
  },
  "app/src/main/java/com/vanipy/tindak/ProgressPump.java": {
    "path": "app/src/main/java/com/vanipy/tindak/ProgressPump.java",
    "lines": 36,
    "role": "Coalesces a firehose of byte-count progress events onto the main thread: * at most one queued runnable, always carrying the newest values.",
    "els": [
      {
        "name": "ProgressPump",
        "kind": "class"
      },
      {
        "name": "Listener",
        "kind": "interface"
      }
    ],
    "kindNote": null,
    "intraRefs": []
  },
  "app/src/main/java/com/vanipy/tindak/SyncStatusText.java": {
    "path": "app/src/main/java/com/vanipy/tindak/SyncStatusText.java",
    "lines": 61,
    "role": "Single source for the sync footer text in the activity and the widget, * showing both ages the user asked for: when the list last synced (any * successful check, content new or unchanged) and when the list content * last",
    "els": [
      {
        "name": "SyncStatusText",
        "kind": "class"
      }
    ],
    "kindNote": null,
    "intraRefs": [
      "Prefs"
    ]
  },
  "app/src/main/java/com/vanipy/tindak/net/Resumable.java": {
    "path": "app/src/main/java/com/vanipy/tindak/net/Resumable.java",
    "lines": 486,
    "role": "Verified resumable-download core shared by the APK updater (UpdateInstall) * and the .xdc fetcher (XdcFetch).",
    "els": [
      {
        "name": "Resumable",
        "kind": "class"
      },
      {
        "name": "Conn",
        "kind": "interface"
      },
      {
        "name": "Opener",
        "kind": "interface"
      },
      {
        "name": "Progress",
        "kind": "interface"
      },
      {
        "name": "Verifier",
        "kind": "interface"
      },
      {
        "name": "Refused",
        "kind": "class"
      },
      {
        "name": "Incomplete",
        "kind": "class"
      },
      {
        "name": "Spec",
        "kind": "class"
      },
      {
        "name": "Result",
        "kind": "class"
      },
      {
        "name": "Meta",
        "kind": "class"
      }
    ],
    "kindNote": null,
    "intraRefs": []
  },
  "webxdc/index.html": {
    "path": "webxdc/index.html",
    "lines": 80,
    "role": null,
    "els": [],
    "kindNote": "page",
    "intraRefs": []
  },
  "webxdc/js/app.js": {
    "path": "webxdc/js/app.js",
    "lines": 520,
    "role": "app.js - tindak shared-list webxdc prototype.",
    "els": [
      {
        "name": "diag",
        "kind": "function"
      },
      {
        "name": "renderDiag",
        "kind": "function"
      },
      {
        "name": "loadBinding",
        "kind": "function"
      },
      {
        "name": "saveBinding",
        "kind": "function"
      },
      {
        "name": "loadPackagedBinding",
        "kind": "function"
      },
      {
        "name": "stamp",
        "kind": "function"
      },
      {
        "name": "loadDoc",
        "kind": "function"
      },
      {
        "name": "seed",
        "kind": "function"
      },
      {
        "name": "persist",
        "kind": "function"
      },
      {
        "name": "sendOps",
        "kind": "function"
      },
      {
        "name": "opsInfo",
        "kind": "function"
      },
      {
        "name": "applyUpdate",
        "kind": "function"
      }
    ],
    "kindNote": null,
    "intraRefs": []
  },
  "webxdc/js/mergedoc.js": {
    "path": "webxdc/js/mergedoc.js",
    "lines": 159,
    "role": "mergedoc.js - JS port of the Android app's MergeDoc (com.vanipy.tindak.merge).",
    "els": [
      {
        "name": "cmpStamp",
        "kind": "function"
      },
      {
        "name": "MergeDoc",
        "kind": "class"
      },
      {
        "name": "hkeyOf",
        "kind": "function"
      }
    ],
    "kindNote": null,
    "intraRefs": []
  },
  "webxdc/js/restoregen.js": {
    "path": "webxdc/js/restoregen.js",
    "lines": 221,
    "role": "itd_restore_v2 app-side generation tracker.",
    "els": [
      {
        "name": "isHex64",
        "kind": "function"
      },
      {
        "name": "opKey",
        "kind": "function"
      },
      {
        "name": "create",
        "kind": "function"
      },
      {
        "name": "persist",
        "kind": "function"
      },
      {
        "name": "load",
        "kind": "function"
      },
      {
        "name": "resumeHintAllowed",
        "kind": "function"
      },
      {
        "name": "missingIndices",
        "kind": "function"
      },
      {
        "name": "acceptEnvelope",
        "kind": "function"
      },
      {
        "name": "latestComplete",
        "kind": "function"
      }
    ],
    "kindNote": null,
    "intraRefs": []
  },
  "webxdc/js/seedfix.js": {
    "path": "webxdc/js/seedfix.js",
    "lines": 70,
    "role": "seedfix.js - deterministic sample seed + repair for clock-stamped seeds.",
    "els": [
      {
        "name": "seedDoc",
        "kind": "function"
      },
      {
        "name": "isSeedStamp",
        "kind": "function"
      },
      {
        "name": "fixStamp",
        "kind": "function"
      },
      {
        "name": "repairSeedStamps",
        "kind": "function"
      }
    ],
    "kindNote": null,
    "intraRefs": []
  },
  "webxdc/js/digest.js": {
    "path": "webxdc/js/digest.js",
    "lines": 143,
    "role": "tindak canonical ops digest (browser).",
    "els": [
      {
        "name": "cmpCodePoint",
        "kind": "function"
      },
      {
        "name": "pyString",
        "kind": "function"
      },
      {
        "name": "stableStringify",
        "kind": "function"
      },
      {
        "name": "validateOp",
        "kind": "function"
      },
      {
        "name": "canonicalString",
        "kind": "function"
      },
      {
        "name": "sha256Sync",
        "kind": "function"
      },
      {
        "name": "padded",
        "kind": "function"
      },
      {
        "name": "rr",
        "kind": "function"
      },
      {
        "name": "ch",
        "kind": "function"
      },
      {
        "name": "t1",
        "kind": "function"
      },
      {
        "name": "maj",
        "kind": "function"
      },
      {
        "name": "t2",
        "kind": "function"
      }
    ],
    "kindNote": null,
    "intraRefs": []
  },
  "webxdc/js/webxdc-mock.js": {
    "path": "webxdc/js/webxdc-mock.js",
    "lines": 50,
    "role": "webxdc-mock.js - DEV-ONLY simulation of the webxdc messaging layer.",
    "els": [],
    "kindNote": null,
    "intraRefs": []
  },
  "webxdc/canvas.html": {
    "path": "webxdc/canvas.html",
    "lines": 108,
    "role": null,
    "els": [],
    "kindNote": "page",
    "intraRefs": []
  },
  "webxdc/js/canvas.js": {
    "path": "webxdc/js/canvas.js",
    "lines": 408,
    "role": "canvas.js - tindak review canvas.",
    "els": [
      {
        "name": "el",
        "kind": "function"
      },
      {
        "name": "renderBlock",
        "kind": "function"
      },
      {
        "name": "mount",
        "kind": "function"
      },
      {
        "name": "render",
        "kind": "function"
      },
      {
        "name": "persist",
        "kind": "function"
      },
      {
        "name": "fitView",
        "kind": "function"
      },
      {
        "name": "zoomBy",
        "kind": "function"
      },
      {
        "name": "focusCard",
        "kind": "function"
      },
      {
        "name": "localPoint",
        "kind": "function"
      },
      {
        "name": "endPointer",
        "kind": "function"
      },
      {
        "name": "safeGet",
        "kind": "function"
      },
      {
        "name": "safeSet",
        "kind": "function"
      }
    ],
    "kindNote": null,
    "intraRefs": [
      "canvas-math"
    ]
  },
  "webxdc/js/canvas-math.js": {
    "path": "webxdc/js/canvas-math.js",
    "lines": 197,
    "role": "canvas-math.js - pure camera/layout math for the tindak review canvas.",
    "els": [
      {
        "name": "clampZ",
        "kind": "function"
      },
      {
        "name": "clampCam",
        "kind": "function"
      },
      {
        "name": "center",
        "kind": "function"
      },
      {
        "name": "toWorld",
        "kind": "function"
      },
      {
        "name": "toScreen",
        "kind": "function"
      },
      {
        "name": "fitCam",
        "kind": "function"
      },
      {
        "name": "focusCam",
        "kind": "function"
      },
      {
        "name": "zoomCam",
        "kind": "function"
      },
      {
        "name": "panCam",
        "kind": "function"
      },
      {
        "name": "pinchCam",
        "kind": "function"
      },
      {
        "name": "dragPos",
        "kind": "function"
      },
      {
        "name": "miniScale",
        "kind": "function"
      }
    ],
    "kindNote": null,
    "intraRefs": []
  },
  "web/src/App.tsx": {
    "path": "web/src/App.tsx",
    "lines": 474,
    "role": null,
    "els": [
      {
        "name": "WORLD",
        "kind": "const"
      },
      {
        "name": "ZMIN",
        "kind": "const"
      },
      {
        "name": "CARD_SIZES",
        "kind": "const"
      },
      {
        "name": "ICONS",
        "kind": "const"
      },
      {
        "name": "TITLES",
        "kind": "const"
      },
      {
        "name": "COL_X",
        "kind": "const"
      },
      {
        "name": "ROW_Y",
        "kind": "const"
      },
      {
        "name": "DEFAULT_POS",
        "kind": "const"
      },
      {
        "name": "EDGES",
        "kind": "const"
      },
      {
        "name": "center",
        "kind": "function"
      },
      {
        "name": "sanePositions",
        "kind": "function"
      },
      {
        "name": "clampZ",
        "kind": "const"
      }
    ],
    "kindNote": null,
    "intraRefs": []
  },
  "web/src/main.tsx": {
    "path": "web/src/main.tsx",
    "lines": 7,
    "role": null,
    "els": [],
    "kindNote": null,
    "intraRefs": []
  },
  "web/src/style.css": {
    "path": "web/src/style.css",
    "lines": 105,
    "role": null,
    "els": [],
    "kindNote": "stylesheet",
    "intraRefs": []
  },
  "web/README.md": {
    "path": "web/README.md",
    "lines": 16,
    "role": null,
    "els": [],
    "kindNote": "doc",
    "intraRefs": []
  },
  "web/review/App.tsx": {
    "path": "web/review/App.tsx",
    "lines": 1990,
    "role": null,
    "els": [
      {
        "name": "WORLD",
        "kind": "const"
      },
      {
        "name": "ZMIN",
        "kind": "const"
      },
      {
        "name": "SYS_Z",
        "kind": "const"
      },
      {
        "name": "GROUP_Z",
        "kind": "const"
      },
      {
        "name": "COMP_Z",
        "kind": "const"
      },
      {
        "name": "LO_Z",
        "kind": "const"
      },
      {
        "name": "CARD_SIZES",
        "kind": "const"
      },
      {
        "name": "ICONS",
        "kind": "const"
      },
      {
        "name": "TITLES",
        "kind": "const"
      },
      {
        "name": "COL_X",
        "kind": "const"
      },
      {
        "name": "ROW_Y",
        "kind": "const"
      },
      {
        "name": "DEFAULT_POS",
        "kind": "const"
      }
    ],
    "kindNote": null,
    "intraRefs": []
  },
  "web/review/static.css": {
    "path": "web/review/static.css",
    "lines": 711,
    "role": "Static full-screen canvas styles (GitHub Pages variant).",
    "els": [],
    "kindNote": "stylesheet",
    "intraRefs": []
  },
  "web/review/main.tsx": {
    "path": "web/review/main.tsx",
    "lines": 7,
    "role": null,
    "els": [],
    "kindNote": null,
    "intraRefs": []
  },
  "web/review/files-shim.tsx": {
    "path": "web/review/files-shim.tsx",
    "lines": 68,
    "role": "Minimal local stand-ins for the @instinct/files runtime, so the same canvas source builds as a plain static page for GitHub Pages.",
    "els": [
      {
        "name": "FileCard",
        "kind": "function"
      },
      {
        "name": "Header",
        "kind": "function"
      },
      {
        "name": "Paragraph",
        "kind": "function"
      },
      {
        "name": "Text",
        "kind": "function"
      },
      {
        "name": "Group",
        "kind": "function"
      },
      {
        "name": "Caption",
        "kind": "function"
      },
      {
        "name": "Closing",
        "kind": "function"
      },
      {
        "name": "TextLink",
        "kind": "function"
      },
      {
        "name": "Photos",
        "kind": "function"
      },
      {
        "name": "useLocalState",
        "kind": "function"
      }
    ],
    "kindNote": null,
    "intraRefs": []
  },
  "web/review/index.html": {
    "path": "web/review/index.html",
    "lines": 14,
    "role": null,
    "els": [],
    "kindNote": "page",
    "intraRefs": []
  },
  "web/review/build.sh": {
    "path": "web/review/build.sh",
    "lines": 31,
    "role": "Build the static review canvas for GitHub Pages.",
    "els": [],
    "kindNote": "shell script",
    "intraRefs": []
  },
  "web/review/package.json": {
    "path": "web/review/package.json",
    "lines": 13,
    "role": null,
    "els": [],
    "kindNote": "data",
    "intraRefs": []
  },
  "web/review/package-lock.json": {
    "path": "web/review/package-lock.json",
    "lines": 530,
    "role": null,
    "els": [],
    "kindNote": "data",
    "intraRefs": []
  },
  "web/review/README.md": {
    "path": "web/review/README.md",
    "lines": 50,
    "role": "Review canvas (static, GitHub Pages)",
    "els": [],
    "kindNote": "doc",
    "intraRefs": []
  },
  "web/review/.gitignore": {
    "path": "web/review/.gitignore",
    "lines": 2,
    "role": null,
    "els": [],
    "kindNote": null,
    "intraRefs": []
  },
  "web/tools/gen_sections.py": {
    "path": "web/tools/gen_sections.py",
    "lines": 305,
    "role": "Portable: point at any local tindak checkout and any output path.",
    "els": [
      {
        "name": "_size_and_lines",
        "kind": "def"
      },
      {
        "name": "f",
        "kind": "def"
      },
      {
        "name": "_read_bytes",
        "kind": "def"
      }
    ],
    "kindNote": null,
    "intraRefs": []
  },
  "web/src/sections.ts": {
    "path": "web/src/sections.ts",
    "lines": 825,
    "role": "Generated from irvan-putra/tindak @ 0740caf by gen_sections.py - do not hand-edit.",
    "els": [
      {
        "name": "COMMIT",
        "kind": "const"
      },
      {
        "name": "COMMIT_SHORT",
        "kind": "const"
      },
      {
        "name": "SECTIONS",
        "kind": "const"
      }
    ],
    "kindNote": null,
    "intraRefs": []
  },
  "tools/encrypt_feed.py": {
    "path": "tools/encrypt_feed.py",
    "lines": 36,
    "role": "Encrypt a widget feed JSON to the phone's public key.",
    "els": [
      {
        "name": "b64d",
        "kind": "def"
      },
      {
        "name": "encrypt",
        "kind": "def"
      }
    ],
    "kindNote": null,
    "intraRefs": []
  }
};
