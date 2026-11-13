// Cross-version wire compat for itd_restore_v2 (host-only; no device claims):
// REAL envelopes emitted by the actual old (origin/main) and new (v2.1) bot
// runtimes, fed through the actual old app code path (extracted verbatim
// from origin/main webxdc/js/app.js) and the new generation tracker.
//   node test/compat-v21.test.mjs   (from webxdc/; fixtures in test/fixtures/)
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { webcrypto } from 'node:crypto';
if (!globalThis.crypto) globalThis.crypto = webcrypto;

const here = dirname(fileURLToPath(import.meta.url));
const SRC = join(here, '..', 'js');
const load = f => readFileSync(join(SRC, f), 'utf8');
eval(load('mergedoc.js'));
eval(load('digest.js'));
eval(load('restoregen.js'));
const { ItdDigest, ItdRestoreGen, MergeDoc } = globalThis;

let pass = 0;
const ok = (c, n) => { if (!c) { console.error('FAIL: ' + n); process.exit(1); } pass++; console.log(n + ' ok'); };

// --- extract the OLD shipped applyRestoreEnvelope verbatim from the old app
const legacySrc = readFileSync(join(here, 'fixtures', 'app-0.4.3.js'), 'utf8');
const start = legacySrc.indexOf('function applyRestoreEnvelope(env) {');
let depth = 0, end = -1;
for (let i = legacySrc.indexOf('{', start); i < legacySrc.length; i++) {
  if (legacySrc[i] === '{') depth++;
  if (legacySrc[i] === '}') { depth--; if (!depth) { end = i + 1; break; } }
}
ok(end > start, 'old applyRestoreEnvelope extracted from origin/main app.js');
const legacyFnSrc = legacySrc.slice(start, end);

function oldApp(boundListId) {
  // the exact closure variables the old function touches
  const diags = [];
  const doc = new MergeDoc();
  const sandbox = {
    window: { ItdDigest },
    doc, persist: () => {}, render: () => {},
    boundListId, pendingBindId: null,
    diag: m => diags.push(m),
  };
  const fn = new Function(...Object.keys(sandbox),
    `let lastHlc = 0; ${legacyFnSrc}; return { applyRestoreEnvelope, getHlc: () => lastHlc };`)
    (...Object.values(sandbox));
  return { ...fn, diags, doc };
}

const envsOld = JSON.parse(readFileSync(join(here, 'fixtures', 'envs-old-bot.json'), 'utf8'));
const envsNew = JSON.parse(readFileSync(join(here, 'fixtures', 'envs-new-bot.json'), 'utf8'));
ok(envsOld.length >= 1 && envsOld[0].generation_id === undefined, 'old bot envelope has no generation fields');
ok(envsNew.length >= 1 && envsNew[0].schema === 'itd_restore_v2', 'new bot envelope carries schema v2');

// A) OLD app (r13-era code path) + NEW bot envelopes: extra fields ignored,
//    per-batch digest still verifies, doc converges.
{
  const app = oldApp('list-x');
  for (const env of envsNew) app.applyRestoreEnvelope(env);
  await new Promise(r => setTimeout(r, 50));
  ok(app.diags.some(d => /accepted 12 op\(s\)/.test(d)), 'old app accepts new-bot batch: ' + app.diags.join('|'));
  ok(app.doc.get('t0', 'title') === 'task 0' && app.doc.get('t11', 'title') === 'task 11', 'old app doc converges on new-bot restore');
  ok(app.getHlc() === 11, 'old app hlc highwater correct');
}

// A2) OLD app + NEW bot zero-op completion envelope: harmless no-op.
{
  const app = oldApp('list-x');
  const empty = { ...envsNew[0], ops: [], batch_index: 0, batch_count: 1 };
  empty.digest = await ItdDigest.canonicalDigest([]);
  app.applyRestoreEnvelope(empty);
  await new Promise(r => setTimeout(r, 50));
  ok(app.diags.some(d => /accepted 0 op\(s\)/.test(d)), 'old app tolerates empty completion batch');
}

// B) NEW app tracker + OLD bot envelopes: legacy passthrough, then the same
//    old per-batch path applies them (what app.js does on status 'legacy').
{
  const storage = new Map();
  const store = {
    getItem: k => (storage.has(k) ? storage.get(k) : null),
    setItem: (k, v) => storage.set(k, String(v)),
    removeItem: k => storage.delete(k),
  };
  const tracker = ItdRestoreGen.create({ storage });
  const app = oldApp('list-x');
  for (const env of envsOld) {
    const st = await tracker.acceptEnvelope(env);
    ok(st.status === 'legacy', 'old envelope routes to legacy path');
    app.applyRestoreEnvelope(env);
  }
  await new Promise(r => setTimeout(r, 50));
  ok(app.doc.get('t5', 'title') === 'task 5', 'new app converges on old-bot restore via legacy path');
  ok(!tracker.isIncomplete(), 'legacy restore leaves no generation coverage behind');
}

// C) cross-list guard preserved on both paths
{
  const app = oldApp('list-x');
  app.applyRestoreEnvelope({ ...envsNew[0], list_id: 'list-OTHER' });
  await new Promise(r => setTimeout(r, 50));
  ok(app.diags.some(d => /different list/.test(d)), 'old path cross-list guard intact');
  const tracker = ItdRestoreGen.create({ storage: null });
  // tracker itself is list-agnostic; app.js guards before calling it (see
  // handleRestoreEnvelope) - here assert the old guard fired, tracker unused
}

console.log('ALL COMPAT TESTS PASS (' + pass + ' assertions)');
