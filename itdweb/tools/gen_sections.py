import argparse, hashlib, json, os, sys

# Portable: point at any local intodo checkout and any output path.
#   python3 gen_sections.py --repo /path/to/intodo --out src/sections.ts
# Regenerate after any repo change so cards match the pinned commit.
ap = argparse.ArgumentParser(description='Generate sections.ts for the itdweb review canvas from a local intodo checkout.')
ap.add_argument('--repo', default='.', help='path to the intodo repository root (default: current directory)')
ap.add_argument('--out', default='-', help='output path for sections.ts (default: stdout)')
ap.add_argument('--commit', default=None, help='commit hash the cards pin; default: git rev-parse HEAD in --repo')
args = ap.parse_args()

REPO = os.path.abspath(args.repo)
if args.commit:
    COMMIT = args.commit
else:
    import subprocess
    COMMIT = subprocess.check_output(['git', '-C', REPO, 'rev-parse', 'HEAD'], text=True).strip()
SHORT = COMMIT[:7]

def f(path, note=None):
    full = os.path.join(REPO, path)
    assert os.path.isfile(full), 'missing: ' + path
    if note == 'binary':
        return {'path': path, 'lines': None, 'note': '%.1f MB binary' % (os.path.getsize(full)/1e6)}
    with open(full, 'r', encoding='utf-8', errors='strict') as fh:
        n = sum(1 for _ in fh)
    return {'path': path, 'lines': n}

SECTIONS = [
 dict(id='list-ui', title='List screen & widget', tag='Android app - UI', icon='\U0001F4F1',
      purpose='What you see on the phone: the reminder list in the app and the home-screen widget.',
      link='app/src/main',
      files=[f('app/src/main/java/com/instinct/widget/MainActivity.java'),
             f('app/src/main/java/com/instinct/widget/WidgetProvider.java'),
             f('app/src/main/java/com/instinct/widget/WidgetService.java'),
             f('app/src/main/res/layout/activity_main.xml'),
             f('app/src/main/res/layout/widget_layout.xml'),
             f('app/src/main/res/layout/list_item.xml'),
             f('app/src/main/res/layout/list_section.xml'),
             f('app/src/main/res/layout/widget_item.xml'),
             f('app/src/main/res/layout/widget_section.xml'),
             f('app/src/main/res/values/styles.xml'),
             f('app/src/main/res/xml/widget_info.xml'),
             f('app/src/main/res/drawable/ic_refresh.xml'),
             f('app/src/main/AndroidManifest.xml')],
      notChecked=['TalkBack screen-reader pass over the list',
                  'Very large font sizes beyond the 1.3x spot checks']),
 dict(id='feed-sync', title='Feed sync & decryption', tag='Android app - sync', icon='\U0001F504',
      purpose='Fetches the hosted feed, decrypts it with the paired key, and turns it into list items.',
      link='app/src/main/java/com/instinct/widget',
      files=[f('app/src/main/java/com/instinct/widget/SyncEngine.java'),
             f('app/src/main/java/com/instinct/widget/FeedParser.java'),
             f('app/src/main/java/com/instinct/widget/Crypto.java'),
             f('app/src/main/java/com/instinct/widget/FeedSyncWorker.java'),
             f('app/src/main/java/com/instinct/widget/FeedSyncScheduler.java'),
             f('app/src/main/java/com/instinct/widget/Prefs.java'),
             f('app/src/main/java/com/instinct/widget/Freshness.java'),
             f('app/src/main/java/com/instinct/widget/ProgressPump.java'),
             f('app/src/main/java/com/instinct/widget/SyncStatusText.java'),
             f('app/src/main/java/com/instinct/widget/InstallStatusReceiver.java')],
      notChecked=['Malformed or hostile feed handling beyond the parser tests',
                  'Battery cost of the 30-minute schedule over a full week']),
 dict(id='self-update', title='Self-update & resume', tag='Android app - updates', icon='\u2B07\uFE0F',
      purpose='Checks GitHub for a newer APK, downloads it with resume after interruptions, and installs it.',
      link='app/src/main/java/com/instinct/widget',
      files=[f('app/src/main/java/com/instinct/widget/UpdateCheck.java'),
             f('app/src/main/java/com/instinct/widget/UpdateInstall.java'),
             f('app/src/main/java/com/instinct/widget/net/Resumable.java')],
      notChecked=['Interrupted-download recovery on your own phone (JVM-tested, not device-proven)',
                  'The Samsung installer-permission prompt flow end to end']),
 dict(id='native-canvas', title='Review canvas (native)', tag='Android app - canvas', icon='\U0001F5BC\uFE0F',
      purpose='The in-app canvas view. Shares its math with the webxdc canvas below.',
      link='app/src/main/java/com/instinct/widget/canvas',
      files=[f('app/src/main/java/com/instinct/widget/canvas/ReviewCanvasView.java'),
             f('app/src/main/java/com/instinct/widget/canvas/CanvasMath.java'),
             f('app/src/main/java/com/instinct/widget/canvas/CanvasContent.java'),
             f('app/src/main/java/com/instinct/widget/canvas/ReviewCanvasActivity.java')],
      notChecked=['Rendered output on a real device or emulator (compiles for API 34, never drawn)',
                  'Visual parity with this page']),
 dict(id='merge-core', title='Merge core (Android)', tag='Shared list - CRDT', icon='\U0001F9EC',
      purpose='How two devices combine list edits without losing items.',
      link='app/src/main/java/com/instinct/widget/merge',
      files=[f('app/src/main/java/com/instinct/widget/merge/MergeDoc.java')],
      notChecked=['Independent re-run of the reported property tests (7 Java + 9 JS, run by the builders)']),
 dict(id='bot-app', title='Bot screens (Java/Kotlin)', tag='DeltaChat bot - host UI', icon='\U0001F916',
      purpose="The bot's control screens inside the app: logs, publish dialogs, fetch progress.",
      link='app/src/main/java/com/instinct/widget/bot',
      files=[f('app/src/main/java/com/instinct/widget/bot/BotActivity.java'),
             f('app/src/main/java/com/instinct/widget/bot/BotService.java'),
             f('app/src/main/java/com/instinct/widget/bot/XdcFetch.java'),
             f('app/src/main/java/com/instinct/widget/bot/BotImport.kt'),
             f('app/src/main/java/com/instinct/widget/bot/BootReceiver.java'),
             f('app/src/main/java/com/instinct/widget/bot/PublishErrors.java')],
      notChecked=['Publish error dialogs on a fresh account with no keys configured']),
 dict(id='bot-runtime', title='Bot runtime (Python)', tag='DeltaChat bot - core', icon='\U0001F40D',
      purpose='Message handling, list operations, and the restore/recovery protocol.',
      link='app/src/main/python',
      files=[f('app/src/main/python/bot.py'), f('app/src/main/python/bot_runtime.py')],
      notChecked=['Runtime rollback across the r13/v2 boundary with mixed history (known compatibility gap)']),
 dict(id='bot-delivery', title='Delivery & storage', tag='DeltaChat bot - data', icon='\U0001F4E6',
      purpose='Shipping updated mini-apps to the bot, plus where the bot keeps its data.',
      link='app/src/main/python',
      files=[f('app/src/main/python/delivery_bot.py'),
             f('app/src/main/python/ops_store.py'),
             f('app/src/main/python/migration.py'),
             f('app/src/main/python/turso_store.py'),
             f('app/src/main/python/digest-fixtures.json')],
      notChecked=['Turso cloud adapter tested only against a local stub - no real cloud database yet']),
 dict(id='witness', title='Witness & validation', tag='DeltaChat bot - proof', icon='\U0001F441\uFE0F',
      purpose='Independent confirmation that group messages actually land on other devices.',
      link='app/src/main/python',
      files=[f('app/src/main/python/witness.py'), f('app/src/main/python/bot_validation.py')],
      notChecked=['The independent group-chat witness on your phone (built and JVM-tested, not device-proven)']),
 dict(id='xdc-app', title='Shared list (webxdc)', tag='DeltaChat mini-app', icon='\U0001F4AC',
      purpose='The list that syncs inside a DeltaChat group.',
      link='webxdc',
      files=[f('webxdc/index.html'),
             f('webxdc/js/app.js'),
             f('webxdc/js/digest.js'),
             f('webxdc/js/mergedoc.js'),
             f('webxdc/js/restoregen.js'),
             f('webxdc/js/seedfix.js'),
             f('webxdc/js/webxdc-mock.js')],
      notChecked=['Edit migration between app versions - a new version is a new instance, old bubbles do not heal']),
 dict(id='xdc-canvas', title='Canvas (webxdc)', tag='DeltaChat mini-app - canvas', icon='\U0001F5FA\uFE0F',
      purpose='This canvas, living inside DeltaChat.',
      link='webxdc',
      files=[f('webxdc/canvas.html'),
             f('webxdc/js/canvas.js'),
             f('webxdc/js/canvas-math.js')],
      notChecked=['Open/reopen persistence inside a real DeltaChat client (Chrome smoke test only)']),
 dict(id='this-page', title='This page (itdweb)', tag='Review surface', icon='\U0001F310',
      purpose="The page you are reading - its own source is in the repo, so it reviews itself.",
      link='itdweb',
      files=[f('itdweb/README.md'),
             f('itdweb/src/App.tsx'),
             f('itdweb/src/main.tsx'),
             f('itdweb/src/style.css')],
      notChecked=['Whether your saved layout survives a signed-in reopen on your phone (implemented, not yet verified)']),
 dict(id='tests-android', title='Android tests', tag='Test suites - JVM', icon='\u2705',
      purpose='Render and unit tests for the phone app. These run in CI on every release.',
      link='app/src/test',
      files=[f('app/src/test/java/com/instinct/widget/MainActivityRenderTest.java'),
             f('app/src/test/java/com/instinct/widget/MainActivityProgressRenderTest.java'),
             f('app/src/test/java/com/instinct/widget/WidgetRemoteApplyTest.java'),
             f('app/src/test/java/com/instinct/widget/SyncStatusTextTest.java'),
             f('app/src/test/java/com/instinct/widget/PairingCopyTest.java'),
             f('app/src/test/java/com/instinct/widget/canvas/CanvasMathTest.java'),
             f('app/src/test/java/com/instinct/widget/merge/MergeDocTest.java'),
             f('app/src/test/java/com/instinct/widget/net/ResumableTest.java'),
             f('app/src/test/java/com/instinct/widget/bot/BotActivityRenderTest.java'),
             f('app/src/test/java/com/instinct/widget/bot/BotPublishIpcTest.java'),
             f('app/src/test/java/com/instinct/widget/bot/PublishDialogRenderTest.java'),
             f('app/src/test/java/com/instinct/widget/bot/PublishErrorsTest.java'),
             f('app/src/test/java/com/instinct/widget/bot/XdcFetchBarRenderTest.java'),
             f('app/src/test/java/com/instinct/widget/bot/XdcFetchTest.java')],
      notChecked=['The widget inflation test guards the v0.5.6 Samsung failure - keep it when editing']),
 dict(id='tests-bot', title='Bot tests', tag='Test suites - Python', icon='\U0001F9EA',
      purpose='Recovery, migration, witness and runtime suites.',
      link='app/src/main/python',
      files=[f('app/src/main/python/test_bot_runtime.py'),
             f('app/src/main/python/test_witness.py'),
             f('app/src/main/python/test_restore_recovery.py'),
             f('app/src/main/python/test_migration.py')],
      notChecked=['No accepted recovery candidate has been phone-tested']),
 dict(id='tests-xdc', title='webxdc tests', tag='Test suites - Node', icon='\U0001F9EA',
      purpose='Merge, restore, seed, packaging and compatibility suites, with pinned fixtures.',
      link='webxdc/test',
      files=[f('webxdc/test/canvas-math.test.mjs'),
             f('webxdc/test/compat-v21.test.mjs'),
             f('webxdc/test/mergedoc.test.mjs'),
             f('webxdc/test/notice-route.test.mjs'),
             f('webxdc/test/package.test.mjs'),
             f('webxdc/test/restoregen.test.mjs'),
             f('webxdc/test/seedfix.test.mjs'),
             f('webxdc/test/fixtures/app-0.4.3.js'),
             f('webxdc/test/fixtures/envs-new-bot.json'),
             f('webxdc/test/fixtures/envs-old-bot.json')],
      notChecked=['Fixtures pin the 0.4.3 app bytes - they need refreshing per release']),
 dict(id='build-ci', title='Build & CI', tag='Repo plumbing', icon='\U0001F527',
      purpose='Gradle config, the packaging script, and the two GitHub workflows (both manual-trigger only).',
      link='.github/workflows',
      files=[f('.github/workflows/release.yml'),
             f('.github/workflows/render-check.yml'),
             f('build.gradle'), f('settings.gradle'), f('gradle.properties'),
             f('app/build.gradle'),
             f('gradle/wrapper/gradle-wrapper.properties'),
             f('webxdc/package.sh'),
             f('webxdc/manifest.toml'),
             f('.gitignore')],
      notChecked=['Nothing runs on its own - both workflows wait for someone to press the button']),
 dict(id='feed-tool', title='Feed signing tool', tag='Tools', icon='\U0001F510',
      purpose='Encrypts the reminder feed so only the paired phone can read it.',
      link='tools',
      files=[f('tools/encrypt_feed.py')],
      notChecked=['Key custody - where the signing key pair lives and who can rotate it']),
 dict(id='assets-docs', title='Docs & binary assets', tag='Non-code', icon='\U0001F4C4',
      purpose='README, icons, the launcher art - and the prebuilt RPC library the bot runs on.',
      link='.',
      files=[f('README.md'),
             f('app/src/main/jniLibs/arm64-v8a/librpcsrv.so', 'binary'),
             f('itdweb/src/photos/review-concept-hero.png', 'binary'),
             f('app/src/main/res/mipmap-xxhdpi/ic_launcher.png', 'binary'),
             f('webxdc/icon.png', 'binary'),
             f('gradle/wrapper/gradle-wrapper.jar', 'binary'),
             f('gradlew'), f('gradlew.bat')],
      notChecked=['librpcsrv.so is a ~21 MB prebuilt binary - its source and build recipe are not in this repo']),
]

for s in SECTIONS:
    h = hashlib.sha256()
    for fl in sorted(s['files'], key=lambda x: x['path']):
        with open(os.path.join(REPO, fl['path']), 'rb') as fh:
            h.update(('%s:%s\n' % (fl['path'], hashlib.sha256(fh.read()).hexdigest())).encode())
    s['digest'] = h.hexdigest()[:12]
    s['totalLines'] = sum(fl['lines'] or 0 for fl in s['files'])

out = []
out.append('// Generated from irvan-putra/intodo @ %s by gen_sections.py - do not hand-edit.' % SHORT)
out.append('export const COMMIT = %s;' % json.dumps(COMMIT))
out.append('export const COMMIT_SHORT = %s;' % json.dumps(SHORT))
out.append('export type SectionFile = { path: string; lines: number | null; note?: string };')
out.append('export type Section = { id: string; title: string; tag: string; icon: string; purpose: string; link: string; digest: string; totalLines: number; files: SectionFile[]; notChecked: string[] };')
out.append('export const SECTIONS: Section[] = %s;' % json.dumps(SECTIONS, indent=2, ensure_ascii=False))
text = '\n'.join(out) + '\n'
if args.out == '-':
    sys.stdout.write(text)
else:
    with open(args.out, 'w') as fh:
        fh.write(text)
total_files = sum(len(s['files']) for s in SECTIONS)
total_lines = sum(s['totalLines'] for s in SECTIONS)
print('sections:', len(SECTIONS), 'files:', total_files, 'text lines:', total_lines)
for s in SECTIONS:
    print(' %-13s %3d files %6d lines %s' % (s['id'], len(s['files']), s['totalLines'], s['digest']))
