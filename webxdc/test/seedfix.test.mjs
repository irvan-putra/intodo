// seedfix-repro.mjs - independent repro + fix verification for the
// cross-client divergence (seed poisoning), run with: node seedfix-repro.mjs
//
// Reproduces the exact 23:22 (client A) vs 23:25 (client B) phone state with
// the OLD clock-stamped seed behavior, then verifies the fix (js/seedfix.js):
// repair of ALREADY-persisted docs plus fixed-stamp seeding for new clients.
// Uses the candidate's own js/mergedoc.js and js/seedfix.js, not copies.

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const SRC = process.env.SEEDFIX_SRC || join(here, "..");
const load = (f) => {
  const g = globalThis;
  new Function('globalThis', readFileSync(join(SRC, f), 'utf8'))(g);
  return g;
};
load('js/mergedoc.js');
load('js/seedfix.js');
const MergeDoc = globalThis.MergeDoc;
const SEEDFIX = globalThis.SEEDFIX;

let failures = 0;
function check(name, cond) {
  console.log((cond ? 'PASS' : 'FAIL') + ' ' + name);
  if (!cond) failures++;
}

// --- timeline (epoch ms, matching the phone evidence) ----------------------
const T_SEED_A = Date.UTC(2026, 10, 12, 13, 48, 0);  // A first open ~20:49 WIB, seed at -60s
const T_OP = Date.UTC(2026, 10, 12, 13, 52, 0);      // user's edits ~20:52 WIB
const T_SEED_B = Date.UTC(2026, 10, 12, 16, 23, 0);  // B first open ~23:24 WIB, seed at -60s
const DEV_A = '525e3a...4ce5';
const DEV_B = '1094a6...abc8';

// OLD behavior, inline on purpose: this is the bug being reproduced.
function oldSeed(d, tSeed) {
  const s = { hlc: tSeed, device: 'sample' };
  const items = [
    ['sample-1', 'Buy oat milk', ''],
    ['sample-2', 'Pay electricity bill', 'Due Friday'],
    ['sample-3', 'Call Mom', ''],
  ];
  for (const [id, title, note] of items) {
    d.apply(id, 'title', title, s);
    if (note) d.apply(id, 'note', note, s);
    d.apply(id, 'done', 'false', s);
  }
}

// The user's real ops (device A), HLC stamps as made; Call Dad id as issued.
const CALL_DAD = 'item-m' + 'x1abc';
function userOps() {
  let h = T_OP;
  const st = (dev) => ({ hlc: ++h, device: dev });
  return [
    ['sample-2', 'deleted', 'true', st(DEV_A)],   // Removed item: Pay electricity bill
    [CALL_DAD, 'title', 'Call Dad', st(DEV_A)],   // Added item: Call Dad (title)
    [CALL_DAD, 'done', 'false', st(DEV_A)],       // Added item: Call Dad (initial state)
    ['sample-1', 'done', 'true', st(DEV_A)],      // Marked done: Buy oat milk
    [CALL_DAD, 'done', 'true', st(DEV_A)],        // Marked done: Call Dad
  ];
}
function applyOps(d, ops) { for (const [id, f, v, s] of ops) d.apply(id, f, v, s); }

// app.js render ordering: title-stamp ascending, item-id tie-break
function visibleOrder(d) {
  const ids = [...d.items.keys()].filter((id) => d.isVisible(id));
  ids.sort((a, b) => (((d.getStamp(a, 'title') || {}).hlc || 0) - ((d.getStamp(b, 'title') || {}).hlc || 0))
    || (a < b ? -1 : a > b ? 1 : 0));
  return ids;
}

// --- PRE-FIX: reproduce the observed divergence ----------------------------
const ops = userOps();
const A = new MergeDoc(); oldSeed(A, T_SEED_A); applyOps(A, ops);       // edits applied live
const B = new MergeDoc(); oldSeed(B, T_SEED_B); applyOps(B, ops);       // late first open + replay

check('pre-fix A: Buy oat milk done (observed 23:22)', A.get('sample-1', 'done') === 'true');
check('pre-fix A: pick-one badge on sample-1 (observed)', A.conflicts('sample-1').length === 1);
check('pre-fix A: order [sample-1, sample-3, Call Dad] (observed)',
  JSON.stringify(visibleOrder(A)) === JSON.stringify(['sample-1', 'sample-3', CALL_DAD]));
check('pre-fix B: Buy oat milk NOT done (observed 23:25)', B.get('sample-1', 'done') === 'false');
check('pre-fix B: no badge on sample-1 (observed)', B.conflicts('sample-1').length === 0);
check('pre-fix B: order [Call Dad, sample-1, sample-3] (observed)',
  JSON.stringify(visibleOrder(B)) === JSON.stringify([CALL_DAD, 'sample-1', 'sample-3']));
check('pre-fix: A and B diverge', !A.equals(B));
check('pre-fix: B still holds the true op (history, no loss)',
  B.history('sample-1').some((h) => h.field === 'done' && h.value === 'true' && h.stamp.device === DEV_A));

// --- POST-FIX: repair already-persisted docs -------------------------------
const rA = SEEDFIX.repairSeedStamps(MergeDoc, A);
const rB = SEEDFIX.repairSeedStamps(MergeDoc, B);
const fA = rA.doc, fB = rB.doc;

check('repair: both docs changed', rA.repaired && rB.repaired);
check('repair: B shows Buy oat milk done', fB.get('sample-1', 'done') === 'true');
check('repair: winner stamp is the real op, untouched',
  (() => { const s = fB.getStamp('sample-1', 'done'); return s.device === DEV_A && s.hlc > T_OP && s.hlc < T_SEED_B; })());
check('repair: A and B converge (identical docs)', fA.equals(fB));
check('repair: no spurious badge on either client', fA.conflicts('sample-1').length === 0 && fB.conflicts('sample-1').length === 0);
check('repair: identical order on both',
  JSON.stringify(visibleOrder(fA)) === JSON.stringify(visibleOrder(fB))
  && JSON.stringify(visibleOrder(fA)) === JSON.stringify(['sample-1', 'sample-3', CALL_DAD]));
check('repair: tombstone preserved (bill archived)', !fA.isVisible('sample-2') && !fB.isVisible('sample-2'));
check('repair: seed loser kept in history (history preserved)',
  fA.history('sample-1').some((h) => h.field === 'done' && h.value === 'false' && h.stamp.device === 'sample' && h.stamp.hlc === 0));
check('repair: Call Dad still done, Call Mom still open',
  fA.get(CALL_DAD, 'done') === 'true' && fA.get('sample-3', 'done') === 'false');
check('repair: idempotent', (() => { const again = SEEDFIX.repairSeedStamps(MergeDoc, fA); return !again.repaired && again.doc.equals(fA); })());

// --- POST-FIX: fresh client seeds with the fixed stamp ---------------------
const C = new MergeDoc(); SEEDFIX.seedDoc(C); applyOps(C, userOps());
check('fresh client: fixed-stamp seed converges with repaired docs', C.equals(fA));
check('fresh client: untouched seed fields carry hlc 0', C.getStamp('sample-1', 'title').hlc === 0 && C.getStamp('sample-3', 'done').hlc === 0);
check('fresh client: real op outranks seed on sample-1 done', C.get('sample-1', 'done') === 'true' && C.getStamp('sample-1', 'done').device === DEV_A);

// --- regression: genuine multi-device conflict still surfaces --------------
const X = new MergeDoc(); SEEDFIX.seedDoc(X);
X.apply('sample-3', 'done', 'true', { hlc: T_OP + 1000, device: DEV_A });
X.apply('sample-3', 'done', 'false', { hlc: T_OP + 2000, device: DEV_B });
const rX = SEEDFIX.repairSeedStamps(MergeDoc, X).doc;
check('regression: genuine cross-device conflict still badges after repair', rX.conflicts('sample-3').length === 1);
check('regression: no seed entries were remapped (nothing to repair in X) is false-safe',
  SEEDFIX.repairSeedStamps(MergeDoc, X).repaired === false);

console.log(failures ? ('\n' + failures + ' FAILURE(S)') : '\nALL PASS');
process.exit(failures ? 1 : 0);
