import argparse, json, os, re, subprocess

# Generate code.ts: C4 component + code facts for the review canvas.
# Curated architecture judgments (component names, file->component
# assignment, tech labels) live ONLY in CONFIG below. Everything else is
# extracted mechanically from the pinned commit: file header sentences
# verbatim, component responsibilities curated (RESP, interpretation),
# declarations come from a syntax
# scan, relationships from a static reference scan (a referenced NAME, not
# a proven runtime call - the UI labels them "references").
#
#   python3 gen_code.py --repo /path/to/intodo --out ../src/code.ts

ap = argparse.ArgumentParser()
ap.add_argument('--repo', default='.')
ap.add_argument('--out', default='-')
ap.add_argument('--commit', default=None)
args = ap.parse_args()
REPO = os.path.abspath(args.repo)
COMMIT = args.commit or subprocess.check_output(
    ['git', '-C', REPO, 'rev-parse', 'HEAD'], text=True).strip()
SHORT = COMMIT[:7]

J = 'app/src/main/java/com/instinct/widget/'
P = 'app/src/main/python/'
W = 'webxdc/'
R = 'itdweb/'

# id, container, name, tech, principal (responsibility source), files,
# sections (review slices covering these files), shared/dev_only flags.
CONFIG = [
 dict(id='cp-list-screen', container='c-app', name='List screen',
      tech='Java - Android Activity',
      principal=J+'MainActivity.java',
      files=[J+'MainActivity.java', 'app/src/main/AndroidManifest.xml',
             'app/src/main/res/layout/activity_main.xml',
             'app/src/main/res/layout/list_item.xml',
             'app/src/main/res/layout/list_section.xml',
             'app/src/main/res/values/styles.xml'],
      sections=['list-ui']),
 dict(id='cp-widget', container='c-app', name='Home-screen widget',
      tech='AppWidgetProvider + RemoteViewsService',
      principal=J+'WidgetProvider.java',
      files=[J+'WidgetProvider.java', J+'WidgetService.java',
             'app/src/main/res/layout/widget_layout.xml',
             'app/src/main/res/layout/widget_item.xml',
             'app/src/main/res/layout/widget_section.xml',
             'app/src/main/res/xml/widget_info.xml',
             'app/src/main/res/drawable/ic_refresh.xml'],
      sections=['list-ui']),
 dict(id='cp-sync-sched', container='c-app', name='Sync scheduling',
      tech='Java - WorkManager',
      principal=J+'FeedSyncScheduler.java',
      files=[J+'FeedSyncScheduler.java', J+'FeedSyncWorker.java'],
      sections=['feed-sync']),
 dict(id='cp-feed-pipeline', container='c-app', name='Feed fetch & parse',
      tech='Java - hidden WebView, IFW1 decrypt',
      principal=J+'SyncEngine.java',
      files=[J+'SyncEngine.java', J+'Crypto.java', J+'FeedParser.java',
             J+'Freshness.java'],
      sections=['feed-sync']),
 dict(id='cp-self-update', container='c-app', name='Self-update',
      tech='Java - APK download + install',
      principal=J+'UpdateCheck.java',
      files=[J+'UpdateCheck.java', J+'UpdateInstall.java',
             J+'InstallStatusReceiver.java'],
      sections=['self-update', 'feed-sync']),
 dict(id='cp-bot-host', container='c-app', name='Bot host',
      tech='Java/Kotlin - foreground Service + Activity',
      principal=J+'bot/BotService.java',
      files=[J+'bot/BotService.java', J+'bot/BotActivity.java',
             J+'bot/BootReceiver.java', J+'bot/BotImport.kt',
             J+'bot/PublishErrors.java', J+'bot/XdcFetch.java'],
      sections=['bot-app']),
 dict(id='cp-bot-bridge', container='c-app', name='Bot bridge & control',
      tech='Python (Chaquopy) - deltachat-rpc-client',
      principal=P+'bot_runtime.py',
      files=[P+'bot_runtime.py', P+'bot.py'],
      sections=['bot-runtime']),
 dict(id='cp-delivery-ops', container='c-app', name='Delivery & op store',
      tech='Python - SQLite op log',
      principal=P+'delivery_bot.py',
      files=[P+'delivery_bot.py', P+'ops_store.py', P+'digest-fixtures.json'],
      sections=['bot-delivery']),
 dict(id='cp-witness', container='c-app', name='Witness & validation',
      tech='Python - append-only witness log',
      principal=P+'witness.py',
      files=[P+'witness.py', P+'bot_validation.py'],
      sections=['witness']),
 dict(id='cp-migration', container='c-app', name='State migration',
      tech='Python',
      principal=P+'migration.py',
      files=[P+'migration.py'],
      sections=['bot-delivery']),
 dict(id='cp-native-canvas', container='c-app', name='Native review canvas',
      tech='Java - Android Canvas View',
      principal=J+'canvas/ReviewCanvasActivity.java',
      files=[J+'canvas/ReviewCanvasActivity.java',
             J+'canvas/ReviewCanvasView.java', J+'canvas/CanvasContent.java',
             J+'canvas/CanvasMath.java'],
      sections=['native-canvas']),
 dict(id='cp-merge', container='c-app', name='Merge core (CRDT)',
      tech='Java - LWW registers, HLC',
      principal=J+'merge/MergeDoc.java',
      files=[J+'merge/MergeDoc.java'],
      sections=['merge-core']),
 dict(id='cp-shared', container='c-app', name='Shared support',
      tech='Java - used across components',
      principal=J+'Prefs.java', shared=True,
      files=[J+'Prefs.java', J+'ProgressPump.java', J+'SyncStatusText.java',
             J+'net/Resumable.java'],
      sections=['feed-sync', 'self-update', 'bot-app']),
 dict(id='cx-app', container='c-xdc', name='List app UI',
      tech='JavaScript module (IIFE)',
      principal=W+'js/app.js',
      files=[W+'index.html', W+'js/app.js'],
      sections=['xdc-app']),
 dict(id='cx-merge', container='c-xdc', name='MergeDoc JS port',
      tech='JavaScript module',
      principal=W+'js/mergedoc.js',
      files=[W+'js/mergedoc.js'],
      sections=['xdc-app']),
 dict(id='cx-restore', container='c-xdc', name='Restore & digest support',
      tech='JavaScript modules',
      principal=W+'js/restoregen.js',
      files=[W+'js/restoregen.js', W+'js/seedfix.js', W+'js/digest.js'],
      sections=['xdc-app']),
 dict(id='cx-mock', container='c-xdc', name='webxdc dev mock',
      tech='JavaScript - development only', dev_only=True,
      principal=W+'js/webxdc-mock.js',
      files=[W+'js/webxdc-mock.js'],
      sections=['xdc-app']),
 dict(id='cx-canvas', container='c-xdc', name='Canvas page',
      tech='JavaScript modules + canvas.html',
      principal=W+'js/canvas.js',
      files=[W+'canvas.html', W+'js/canvas.js', W+'js/canvas-math.js'],
      sections=['xdc-canvas']),
 dict(id='cw-landing', container='c-web', name='Landing page',
      tech='React/TypeScript',
      principal=R+'src/App.tsx',
      files=[R+'src/App.tsx', R+'src/main.tsx', R+'src/style.css',
             R+'README.md'],
      sections=['this-page']),
 dict(id='cw-review-map', container='c-web', name='Review map',
      tech='React/TypeScript - this canvas',
      principal=R+'review/App.tsx',
      files=[R+'review/App.tsx', R+'review/static.css', R+'review/main.tsx',
             R+'review/files-shim.tsx', R+'review/index.html',
             R+'review/build.sh', R+'review/package.json',
             R+'review/package-lock.json', R+'review/README.md',
             R+'review/.gitignore'],
      sections=['review-src']),
 dict(id='cw-gen', container='c-web', name='Section inventory generator',
      tech='Python - generates sections.ts',
      principal=R+'tools/gen_sections.py',
      files=[R+'tools/gen_sections.py', R+'src/sections.ts'],
      sections=['review-src']),
 dict(id='cf-cli', container='c-feedtool', name='Feed encryption CLI',
      tech='Python - stdin to stdout',
      principal='tools/encrypt_feed.py',
      files=['tools/encrypt_feed.py'],
      sections=['feed-tool']),
]

BYID = {c['id']: c for c in CONFIG}
FILE2COMP = {}
for c in CONFIG:
    for f in c['files']:
        FILE2COMP[f] = c['id']

# Universe of scannable names: java/kt class basenames, python module names,
# JS cross-file globals.
JAVA_CLASSES = set()
PY_MODS = set()
for c in CONFIG:
    for f in c['files']:
        b = os.path.basename(f)
        if f.endswith(('.java', '.kt')):
            JAVA_CLASSES.add(b.rsplit('.', 1)[0])
        elif f.endswith('.py'):
            PY_MODS.add(b[:-3])
JS_GLOBALS = ['MergeDoc', 'CanvasMath']


def read(path):
    with open(os.path.join(REPO, path), encoding='utf-8', errors='strict') as fh:
        return fh.read()


def role_line(path, src):
    """Verbatim first SENTENCE of the file's top header comment/docstring
    (after shebang/package/import lines), never a mid-file line and never
    cut mid-sentence. Caps at 220 chars."""
    ls = src.split('\n')
    i = 0
    while i < len(ls):
        t = ls[i].strip()
        if (not t or t.startswith('#!') or t in ("'use strict';", '"use strict";')
                or re.match(r'(import|from|package)\s', t)):
            i += 1
            continue
        break
    if i >= len(ls):
        return None
    t = ls[i].strip()
    dq3 = chr(34) * 3
    q3 = chr(39) * 3
    block = None
    if t.startswith(dq3) or t.startswith(q3):
        q = t[:3]
        parts = [t[3:]]
        if q not in t[3:]:
            for j in range(i + 1, min(i + 10, len(ls))):
                parts.append(ls[j])
                if q in ls[j]:
                    break
        block = ' '.join(parts).split(q)[0]
    elif t.startswith('/*'):
        parts = []
        for j in range(i, min(i + 10, len(ls))):
            parts.append(ls[j])
            if '*/' in ls[j]:
                break
        block = re.sub(r'/\*+|\*/', '', ' '.join(parts))
    elif t.startswith('//'):
        parts = []
        for j in range(i, min(i + 6, len(ls))):
            if not ls[j].strip().startswith('//'):
                break
            parts.append(ls[j].strip()[2:])
        block = ' '.join(parts)
    elif t.startswith('#'):
        parts = []
        for j in range(i, min(i + 6, len(ls))):
            if not ls[j].strip().startswith('#'):
                break
            parts.append(ls[j].strip()[1:])
        block = ' '.join(parts)
    if not block:
        return None
    text = re.sub(r'\s+', ' ', block).strip().lstrip('*# ').strip()
    if len(text) <= 14:
        return None
    m = re.search(r'[.!?](?:\s|$)', text)
    return (text[:m.end()].strip() if m else text)[:220]


def decls(path, src):
    """Top-level declarations: (name, kind)."""
    out = []
    if path.endswith(('.java', '.kt')):
        for m in re.finditer(
                r'^\s*(?:public |private |protected |internal |abstract |final |open |static |sealed )*'
                r'(class|interface|enum|object)\s+([A-Za-z_]\w*)', src, re.M):
            kind, name = m.group(1), m.group(2)
            if kind == 'object':
                kind = 'object (singleton)'
            if (name, kind) not in out:
                out.append((name, kind))
    elif path.endswith('.py'):
        for m in re.finditer(r'^(?:class|def)\s+([A-Za-z_]\w*)', src, re.M):
            kind = 'class' if src[m.start():].startswith('class') else 'def'
            name = m.group(1)
            if (name, kind) not in out:
                out.append((name, kind))
    elif path.endswith(('.js', '.mjs')):
        for m in re.finditer(r'^\s*(?:function\s+([A-Za-z_]\w*)|class\s+([A-Za-z_]\w*)|'
                             r'(?:const|let|var)\s+([A-Za-z_]\w*)\s*=\s*(?:async\s*)?(?:\(|function))',
                             src, re.M):
            name = m.group(1) or m.group(2) or m.group(3)
            kind = 'class' if m.group(2) else 'function'
            if (name, kind) not in out:
                out.append((name, kind))
    elif path.endswith(('.ts', '.tsx')):
        for m in re.finditer(r'^(?:export\s+)?(?:default\s+)?(?:function\s+([A-Za-z_]\w*)|'
                             r'class\s+([A-Za-z_]\w*)|(?:const|let)\s+([A-Za-z_]\w*)\s*[:=])',
                             src, re.M):
            name = m.group(1) or m.group(2) or m.group(3)
            kind = 'class' if m.group(2) else ('function' if m.group(1) else 'const')
            if (name, kind) not in out:
                out.append((name, kind))
    return out[:12]


def file_refs(path, src):
    """Static NAME references to other scanned units: [(target_file, via)]."""
    own_comp = FILE2COMP.get(path)
    out = []
    if path.endswith(('.java', '.kt')):
        own = os.path.basename(path).rsplit('.', 1)[0]
        for cls in sorted(JAVA_CLASSES):
            if cls == own:
                continue
            if re.search(r'\b%s\b' % re.escape(cls), src):
                tgt = next((f for f, cid in FILE2COMP.items()
                            if os.path.basename(f).rsplit('.', 1)[0] == cls), None)
                if tgt:
                    out.append((tgt, '%s -> %s' % (os.path.basename(path), cls)))
    elif path.endswith('.py'):
        own = os.path.basename(path)[:-3]
        for mod in sorted(PY_MODS):
            if mod == own:
                continue
            if re.search(r'^\s*(?:from|import)\s+%s\b' % re.escape(mod), src, re.M):
                tgt = next((f for f in FILE2COMP if os.path.basename(f) == mod + '.py'), None)
                if tgt:
                    out.append((tgt, '%s imports %s' % (os.path.basename(path), mod)))
    elif path.endswith(('.js', '.mjs')):
        for g in JS_GLOBALS:
            if re.search(r'\b%s\b' % g, src):
                tgt = next((f for f in FILE2COMP
                            if os.path.basename(f).rsplit('.', 1)[0].replace('-', '') == g.lower()), None)
                if tgt and tgt != path:
                    out.append((tgt, '%s uses %s' % (os.path.basename(path), g)))
    return out


code_files = {}
# Curated concise component responsibilities: architectural interpretation
# grounded in the listed files (mechanical verbatim fragments read poorly).
RESP = {
    'cp-list-screen': "Shows the reminder list and hosts the app's menu actions (sync, bot, canvas, self-update).",
    'cp-widget': "Home-screen widget rendering the cached list; taps refresh via the sync scheduler.",
    'cp-sync-sched': "Schedules and runs periodic background feed syncs (WorkManager), then refreshes the widget.",
    'cp-feed-pipeline': "Fetches the feed page through a hidden WebView, decrypts IFW1, parses items.",
    'cp-self-update': "Checks GitHub Releases for a newer itdapk APK, downloads and installs it.",
    'cp-bot-host': "Runs the embedded DeltaChat bot (foreground service) plus its setup and publish UI.",
    'cp-bot-bridge': "Bot runtime: serves each accepted chat an isolated list stack; routes events to bridges.",
    'cp-delivery-ops': "Publishes new .xdc versions to served chats and persists list ops (SQLite).",
    'cp-witness': "Append-only witness log for chat observation, plus offline validation tooling.",
    'cp-migration': "Phone-local export/import bundle moving the bot between Android installs.",
    'cp-native-canvas': "Native Android host and WebView bridge for the review canvas page.",
    'cp-merge': "CRDT merge core: per-field last-writer-wins registers with HLC ordering.",
    'cp-shared': "Shared prefs/progress/status helpers used across components (no feature of its own).",
    'cx-app': "Shared-list webxdc UI: turns user actions into ops and renders the merged list.",
    'cx-merge': "JS port of the Android MergeDoc CRDT; same semantics, property-tested.",
    'cx-restore': "Tracks restore generations, computes canonical digests, fixes item seeds.",
    'cx-mock': "Dev-only simulation of the webxdc messaging layer for local tests.",
    'cx-canvas': "Plain-DOM canvas page port of the review canvas for the mini-app.",
    'cw-landing': "Static landing page for the repo, served on GitHub Pages.",
    'cw-review-map': "This review canvas: zoom-banded C4 map plus the section review workflow.",
    'cw-gen': "Generates sections.ts (file inventory and digests) from a repo checkout.",
    'cf-cli': "Encrypts a feed JSON to the phone's public key (IFW1) for publishing.",
}

comp_refs = {}

def kind_note(rp):
    if rp.endswith('AndroidManifest.xml'):
        return 'manifest'
    if '/res/layout/' in rp:
        return 'layout resource'
    if '/res/drawable' in rp:
        return 'drawable resource'
    if '/res/xml/' in rp:
        return 'xml resource'
    if rp.endswith('.json'):
        return 'data'
    if rp.endswith('.css'):
        return 'stylesheet'
    if rp.endswith('.html'):
        return 'page'
    if rp.endswith('.md'):
        return 'doc'
    if rp.endswith(('.gradle', '.properties')):
        return 'build config'
    if rp.endswith('.toml'):
        return 'config'
    if rp.endswith(('.png', '.jpg', '.webp')):
        return 'binary asset'
    if rp.endswith('.sh'):
        return 'shell script'
    return None

missing = []
for c in CONFIG:
    for f in c['files']:
        full = os.path.join(REPO, f)
        if not os.path.isfile(full):
            missing.append(f)
            continue
        src = read(f)
        lines = src.count('\n') + (0 if src.endswith('\n') else 1)
        ds = decls(f, src)
        refs = file_refs(f, src)
        # intra-component refs attach to the file's elements (code view);
        # cross-component refs aggregate to COMPONENT_REFS (component view).
        intra = sorted({os.path.basename(t).rsplit('.', 1)[0]
                        for t, _v in refs if FILE2COMP.get(t) == c['id']})
        for t, via in refs:
            other = FILE2COMP.get(t)
            if other and other != c['id']:
                comp_refs.setdefault((c['id'], other), []).append(via)
        code_files[f] = dict(path=f, lines=lines, role=role_line(f, src),
                             els=[dict(name=n, kind=k) for n, k in ds],
                             kindNote=None if ds else kind_note(f),
                             intraRefs=[x for x in intra if x != os.path.basename(f).rsplit('.', 1)[0]])

if missing:
    raise SystemExit('CONFIG files missing from worktree: %s' % missing)

components = []
for c in CONFIG:
    princ = code_files.get(c['principal'], {})
    components.append(dict(
        id=c['id'], container=c['container'], name=c['name'], tech=c['tech'],
        resp=RESP.get(c['id']) or princ.get('role'), files=c['files'], sections=c['sections'],
        shared=bool(c.get('shared')), devOnly=bool(c.get('dev_only'))))

crefs = [dict(a=a, b=b, via=sorted(set(v))[:6]) for (a, b), v in sorted(comp_refs.items())]

header = ('// Generated from irvan-putra/intodo @ %s by gen_code.py - do not hand-edit.\n'
          '// Component names/file assignment/tech labels are the generator\'s curated CONFIG;\n'
          '// responsibilities are CURATED concise summaries (architectural interpretation);\n'
          '// file lines are verbatim first header sentences; declarations and references\n'
          '// come from a static syntax scan (a reference is a NAME match, not a proven call).\n'
          % SHORT)
out = [header]
out.append('export const CODE_COMMIT = "%s";' % COMMIT)
out.append('export const CODE_COMMIT_SHORT = "%s";' % SHORT)
out.append('export type CodeEl = { name: string; kind: string };')
out.append('export type CodeFile = { path: string; lines: number | null; role?: string | null; els: CodeEl[]; kindNote?: string | null; intraRefs: string[] };')
out.append('export type C4Component = { id: string; container: string; name: string; tech: string; resp?: string | null; files: string[]; sections: string[]; shared: boolean; devOnly: boolean };')
out.append('export type ComponentRef = { a: string; b: string; via: string[] };')
out.append('export const COMPONENTS: C4Component[] = %s;' % json.dumps(components, indent=2, ensure_ascii=False))
out.append('export const COMPONENT_REFS: ComponentRef[] = %s;' % json.dumps(crefs, indent=2, ensure_ascii=False))
out.append('export const CODE_FILES: Record<string, CodeFile> = %s;' % json.dumps(code_files, indent=2, ensure_ascii=False))
text = '\n'.join(out) + '\n'
if args.out == '-':
    print(text)
else:
    with open(args.out, 'w', encoding='utf-8') as fh:
        fh.write(text)
    print('wrote %s: %d components, %d refs, %d files @%s'
          % (args.out, len(components), len(crefs), len(code_files), SHORT))
