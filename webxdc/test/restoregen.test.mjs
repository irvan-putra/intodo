// Host-only tests for js/restoregen.js (itd_restore_v2 app-side tracker).
// Run: node test_restoregen.mjs   (no browser, no device claims)
import { readFileSync } from 'fs';
import { webcrypto } from 'crypto';
if (!globalThis.crypto) globalThis.crypto = webcrypto;

eval(readFileSync(new URL('../js/digest.js', import.meta.url), 'utf8'));
eval(readFileSync(new URL('../js/restoregen.js', import.meta.url), 'utf8'));

const { ItdDigest, ItdRestoreGen } = globalThis;

function memStorage() {
  const m = new Map();
  return {
    getItem: k => (m.has(k) ? m.get(k) : null),
    setItem: (k, v) => m.set(k, String(v)),
    removeItem: k => m.delete(k),
    _map: m,
  };
}

const mkOp = (i, device = 'dev1') => ({
  item: 't' + i, field: 'title', value: 'task ' + i,
  stamp: { hlc: i, device },
});

// a bot-side equivalent: freeze ops into batches and envelope them
async function makeGen(ops, per, gen = 'a'.repeat(64)) {
  const batches = [];
  for (let i = 0; i < ops.length; i += per) batches.push(ops.slice(i, i + per));
  if (!batches.length) batches.push([]);
  const generation_digest = await ItdDigest.canonicalDigest(ops);
  const envs = [];
  for (let i = 0; i < batches.length; i++) {
    envs.push({
      itd_restore_v1: true, schema: 'itd_restore_v2', list_id: 'list-x',
      generation_id: gen, generation_digest,
      batch_index: i, batch_count: batches.length, highwater: ops.length,
      digest: await ItdDigest.canonicalDigest(batches[i]),
      ops: batches[i], nonce: gen.slice(0, 12) + ':' + i,
    });
  }
  return { envs, generation_digest };
}

function mkTracker(storage) {
  const applied = [];
  const t = ItdRestoreGen.create({ storage });
  return { t, applied, apply: ops => applied.push(...ops) };
}

let pass = 0;
function ok(cond, name) {
  if (!cond) { console.error('FAIL: ' + name); process.exit(1); }
  pass++; console.log(name + ' ok');
}

// out-of-order + duplicate + completion gating
{
  const { t, applied, apply } = mkTracker(memStorage());
  const { envs } = await makeGen(Array.from({ length: 12 }, (_, i) => mkOp(i)), 5);
  let st = await t.acceptEnvelope(envs[1], apply);
  ok(st.status === 'accepted' && st.received === 1 && st.total === 3, 'out-of-order first');
  st = await t.acceptEnvelope(envs[1], apply);
  ok(st.status === 'duplicate' && applied.length === 5, 'duplicate no double-apply');
  st = await t.acceptEnvelope(envs[0], apply);
  ok(st.status === 'accepted' && st.received === 2, 'second batch');
  st = await t.acceptEnvelope(envs[2], apply);
  ok(st.status === 'complete' && st.ops === 12, 'completion gated on all batches');
  ok(applied.length === 12 && !t.isIncomplete(), 'all ops applied, coverage dropped');
}

// corrupt batch rejected, nothing applied
{
  const { t, applied, apply } = mkTracker(memStorage());
  const { envs } = await makeGen([mkOp(1), mkOp(2)], 5);
  const bad = { ...envs[0], ops: [mkOp(1), { ...mkOp(2), value: 'tampered' }] };
  const st = await t.acceptEnvelope(bad, apply);
  ok(st.status === 'rejected' && applied.length === 0, 'corrupt batch rejected');
}

// mixed generations: newer supersedes coverage
{
  const { t, applied, apply } = mkTracker(memStorage());
  const g1 = await makeGen([mkOp(1)], 5, 'a'.repeat(64));
  const g2 = await makeGen([mkOp(1), mkOp(2)], 5, 'b'.repeat(64));
  await t.acceptEnvelope(g1.envs[0], apply); // complete gen a
  const st = await t.acceptEnvelope(g2.envs[0], apply);
  ok(st.status === 'complete' && t.currentGeneration() === null, 'supersede + complete');
}

// newer gen arriving while old is incomplete drops old coverage
{
  const { t, apply } = mkTracker(memStorage());
  const g1 = await makeGen(Array.from({ length: 8 }, (_, i) => mkOp(i)), 5, 'a'.repeat(64));
  const g2 = await makeGen([mkOp(1)], 5, 'c'.repeat(64));
  await t.acceptEnvelope(g1.envs[0], apply);
  ok(t.isIncomplete() && t.currentGeneration() === 'a'.repeat(64), 'incomplete gen tracked');
  const st = await t.acceptEnvelope(g2.envs[0], apply);
  ok(st.status === 'complete', 'newer gen supersedes incomplete coverage');
}

// coverage reload: restart resumes, received batches not re-applied
{
  const storage = memStorage();
  const { t, applied, apply } = mkTracker(storage);
  const { envs } = await makeGen(Array.from({ length: 12 }, (_, i) => mkOp(i)), 5);
  await t.acceptEnvelope(envs[0], apply);
  await t.acceptEnvelope(envs[1], apply);
  ok(storage._map.has(ItdRestoreGen.STORAGE_KEY), 'coverage persisted');
  // restart: fresh tracker over the same storage
  const r = mkTracker(storage);
  ok(r.t.isIncomplete() && JSON.stringify(r.t.missingIndices()) === '[2]',
     'coverage reloaded, missing=[2]');
  let st = await r.t.acceptEnvelope(envs[0], r.apply);
  ok(st.status === 'duplicate' && r.applied.length === 0, 'no re-apply after reload');
  st = await r.t.acceptEnvelope(envs[2], r.apply);
  ok(st.status === 'complete' && r.applied.length === 2, 'completion after reload');
  ok(storage._map.get(ItdRestoreGen.STORAGE_KEY) === undefined, 'coverage dropped on completion');
}

// empty completion: zero-op generation completes on its single empty batch
{
  const { t, applied, apply } = mkTracker(memStorage());
  const { envs } = await makeGen([], 5);
  const st = await t.acceptEnvelope(envs[0], apply);
  ok(st.status === 'complete' && st.ops === 0 && applied.length === 0,
     'empty completion');
}

// legacy envelope passes through
{
  const { t, applied, apply } = mkTracker(memStorage());
  const st = await t.acceptEnvelope({ itd_restore_v1: true, list_id: 'list-x', ops: [mkOp(1)] }, apply);
  ok(st.status === 'legacy' && applied.length === 0, 'legacy passthrough');
}

// malformed shapes
{
  const { t, applied: _a, apply } = mkTracker(memStorage());
  for (const bad of [null, 42, {}, { generation_id: 'zz' },
       { generation_id: 'a'.repeat(64), batch_index: 5, batch_count: 3, ops: [] },
       { generation_id: 'a'.repeat(64), batch_index: 0, batch_count: 0, ops: [] },
       { generation_id: 'a'.repeat(64), batch_index: 0, batch_count: 9999, ops: [] }]) {
    const st = await t.acceptEnvelope(bad, apply);
    ok(st.status === 'malformed' || st.status === 'legacy', 'malformed refused: ' + JSON.stringify(bad));
  }
}

// op-identity dedup across redelivery within a generation
{
  const { t, applied, apply } = mkTracker(memStorage());
  const ops = [mkOp(1), mkOp(2), mkOp(2)]; // dup op in stream
  const { envs } = await makeGen(ops, 5, 'd'.repeat(64));
  // batch digest covers the raw ops; accumulation dedups by identity
  const genDigest = await ItdDigest.canonicalDigest(ops);
  envs[0].generation_digest = genDigest;
  const st = await t.acceptEnvelope(envs[0], apply);
  ok(st.status === 'digest-mismatch' || (st.status === 'complete' && st.ops === 2),
     'identity dedup consistent (got ' + st.status + ')');
}

// opKey field-boundary collision: ("a b","c") vs ("a","b c") must NOT merge
{
  const { t, applied, apply } = mkTracker(memStorage());
  const ops = [
    { item: 'a b', field: 'c', value: 'v1', stamp: { hlc: 1, device: 'd' } },
    { item: 'a', field: 'b c', value: 'v1', stamp: { hlc: 1, device: 'd' } },
  ];
  const { envs } = await makeGen(ops, 5, 'e'.repeat(64));
  const st = await t.acceptEnvelope(envs[0], apply);
  ok(st.status === 'complete' && st.ops === 2 && applied.length === 2,
     'opKey collision pair kept distinct');
}

// bounded PERSISTED resume-hint budget: 3 per 30min window, restart-proof
{
  const storage = memStorage();
  const { t, apply } = mkTracker(storage);
  const { envs } = await makeGen(Array.from({ length: 8 }, (_, i) => mkOp(i)), 5);
  await t.acceptEnvelope(envs[0], apply); // incomplete: batch 1 missing
  const W = 1800000;
  const t0 = 5000000;
  ok(t.resumeHintAllowed(t0, 3, W) === true, 'hint 1 allowed');
  ok(t.resumeHintAllowed(t0 + 1, 3, W) === true, 'hint 2 allowed');
  // restart: fresh tracker over the same storage keeps the budget
  const r = mkTracker(storage);
  ok(r.t.resumeHintAllowed(t0 + 2, 3, W) === true, 'hint 3 allowed after restart');
  ok(r.t.resumeHintAllowed(t0 + 3, 3, W) === false, 'hint 4 refused (budget)');
  ok(r.t.resumeHintAllowed(t0 + W + 4, 3, W) === true, 'fresh window after cooldown');
  // completion drops the budget with the coverage
  const r2 = mkTracker(storage);
  await r2.t.acceptEnvelope(envs[1], r2.apply);
  ok(r2.t.resumeHintAllowed(t0 + W + 5, 3, W) === false, 'no hint after completion');
}

// verified-complete record: written on completion, SEPARATE from dropped
// coverage, survives reload, replaced by the next completed generation
{
  const storage = memStorage();
  const { t, apply } = mkTracker(storage);
  const g1 = await makeGen([mkOp(1)], 5, 'a'.repeat(64));
  await t.acceptEnvelope(g1.envs[0], apply);
  ok(t.latestComplete() === 'a'.repeat(64), 'complete record written');
  ok(storage._map.get(ItdRestoreGen.STORAGE_KEY) === undefined &&
     storage._map.has(ItdRestoreGen.COMPLETE_KEY), 'separate from dropped coverage');
  const r = mkTracker(storage); // reload
  ok(r.t.latestComplete() === 'a'.repeat(64), 'complete record survives reload');
  const g2 = await makeGen([mkOp(1), mkOp(2)], 5, 'b'.repeat(64));
  await r.t.acceptEnvelope(g2.envs[0], r.apply);
  ok(r.t.latestComplete() === 'b'.repeat(64), 'complete record replaced by newer gen');
  // no completion -> no claim
  const s2 = memStorage();
  const h = mkTracker(s2);
  ok(h.t.latestComplete() === null, 'no claim before first completion');
  const g3 = await makeGen(Array.from({ length: 8 }, (_, i) => mkOp(i)), 5);
  await h.t.acceptEnvelope(g3.envs[0], h.apply); // incomplete
  ok(h.t.latestComplete() === null, 'no claim while incomplete');
}

console.log('ALL RESTOREGEN TESTS PASS (' + pass + ' assertions)');
